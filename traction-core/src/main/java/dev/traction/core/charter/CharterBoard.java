package dev.traction.core.charter;

import dev.traction.core.api.FlightRecord.Disposition;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.report.FlightReportMessage.RecordType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * The charter projection in Redis: its counts, its completion and its abort (key shapes on
 * {@link CharterKeys}). Every count change runs in one atomic script
 * ({@code traction/charter_tally.lua}) together with the completion check.
 *
 * <p><b>Counting.</b> Only settled outcomes count: RECORD rows of a charter's flights, never
 * INTERIM rows (holds and deferrals are still in flight). The bucket is decided by disposition
 * first, then outcome:
 * <pre>
 *   disposition PARKED               parked     (incl. SKIPPED GATE_GROUNDED, which parks)
 *   TERMINAL + LANDED                landed
 *   TERMINAL + INCIDENT              rejected   (FLIGHT_FAULT)
 *   TERMINAL + SKIPPED               skipped    (incl. CHARTER_ABORTED)
 * </pre>
 * Each flight is counted at most once: the seen hash maps every counted flightId to its bucket,
 * so a redelivered report or a duplicate RECORD PARKED row never counts twice. The seen entry
 * also lets the two operator moves be exact and idempotent:
 * <ul>
 *   <li><b>replay</b> ({@link ReplayTally}, before the runway publish): a flight counted
 *       {@code parked} is un-counted ({@code parked - 1}, seen entry removed), so its next settled
 *       outcome counts once;</li>
 *   <li><b>discard</b> ({@link DiscardTally}): a flight counted {@code parked} moves to
 *       {@code discarded}; a parked flight that was never reported (a broker or framework park)
 *       is counted {@code discarded} for the first time.</li>
 * </ul>
 * A flight counted anything else is left alone by both (a stale duplicate in the parked queue).
 *
 * <p><b>Completion.</b> A charter completes once, when
 * <pre>
 *   landed + rejected + skipped + parked + discarded &gt;= expected
 * </pre>
 * (equality in a normal run; before any discard this is
 * {@code landed + rejected + skipped + parked == expected}). Parked counts as accounted: a
 * parked flight is settled until an operator replays or discards it. The completion latch is the
 * charter's status ({@code OPEN -> COMPLETED}, or {@code ABORTED}), flipped inside the script, so
 * {@code CHARTER_COMPLETED} fires exactly once, with {@code reason} = the source:
 * {@code LATCH} (organic), {@code SWEEP} ({@link CharterSweep}, best effort) or {@code ABORT}. A
 * completed charter keeps counting (a replayed flight landing, the abort skip window) but never
 * completes again, and never reopens: that keeps {@code PER_KEY} admission exact. Completion
 * releases the admission key, leaves the open set, and starts the 7-day retention of the hash.
 *
 * <p><b>Abort</b> ({@link #abort}): writes the skip-window marker (7 days), so the data plane
 * settles the charter's remaining flights {@code SKIPPED CHARTER_ABORTED} before clearance, and
 * force-completes the charter with source {@code ABORT}: {@code CHARTER_ABORTED} then
 * {@code CHARTER_COMPLETED}. The skipped rows keep counting, so the books still add up to
 * {@code expected} once the runway has drained. Abort is a skip window, not a purge: flights
 * still queued when the marker expires fly normally.
 *
 * <p>A report or tally for a charter without a hash (never opened, or past its retention) is
 * ignored: nothing is counted and nothing is created. Notifications are best effort (a failure
 * is logged). Redis failures on counting propagate, so the tower redelivers the report; the
 * per-flight dedup makes that safe. Only {@link #isAborted}, the data plane's per-flight read,
 * fails open.
 *
 * <p>The bean lives in every process (the data plane reads the abort marker); its writes are
 * driven only by the control plane: the tower consumer, replay, discard, the sweep and the admin
 * abort.
 */
@Slf4j
public class CharterBoard implements DiscardTally, ReplayTally {

  /** Retention of a completed charter, and the abort skip window. */
  public static final Duration CLOSED_TTL = Duration.ofDays(7);

  public static final String STATUS_OPEN = "OPEN";
  public static final String SOURCE_LATCH = "LATCH";
  public static final String SOURCE_SWEEP = "SWEEP";
  public static final String SOURCE_ABORT = "ABORT";

  static final String LANDED = "landed";
  static final String REJECTED = "rejected";
  static final String SKIPPED = "skipped";
  static final String PARKED = "parked";
  static final String DISCARDED = "discarded";

  private static final long CHANGED = 1;
  private static final long COMPLETED = 2;
  private static final long UNKNOWN = -1;

  /**
   * One charter as stored. {@code status} is OPEN, COMPLETED or ABORTED; {@code source} (LATCH,
   * SWEEP, ABORT), {@code completedAt}, {@code abortedAt} and {@code abortedBy} are null until set.
   */
  public record CharterSnapshot(String charterRef, String airportId, String gateId,
      String charterId, String status, long expected, long landed, long rejected, long skipped,
      long parked, long discarded, Long openedAt, Long lastProgressAt, Long completedAt,
      String source, Long abortedAt, String abortedBy) {

    /** Every settled flight: {@code landed + rejected + skipped + parked + discarded}. */
    public long accounted() {
      return landed + rejected + skipped + parked + discarded;
    }
  }

  private final StringRedisTemplate redis;
  private final NotifyDispatcher notify;
  private final RedisScript<Long> tallyScript;

  public CharterBoard(StringRedisTemplate redis, NotifyDispatcher notify) {
    this.redis = redis;
    this.notify = notify;
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(new ResourceScriptSource(new ClassPathResource("traction/charter_tally.lua")));
    script.setResultType(Long.class);
    this.tallyScript = script;
  }

  /**
   * The control plane applies an OPENED event: {@code CHARTER_OPENED} once per charter, and a
   * charter whose expected count is 0 completes here.
   *
   * @throws IllegalArgumentException a malformed event (it can never apply)
   */
  public void opened(CharterEventMessage event) {
    if (event == null || event.type() != CharterEventMessage.Type.OPENED
        || event.charterRef() == null) {
      throw new IllegalArgumentException("not an applicable OPENED event: " + event);
    }
    String ref = event.charterRef();
    long result = tally("opened", ref);
    if (result == UNKNOWN) {
      log.warn("charter {} OPENED but no charter is stored; ignored", ref);
      return;
    }
    if ((result & CHANGED) != 0) {
      notifyFromSnapshot(NotifyEvent.Type.CHARTER_OPENED, ref, Map.of());
    }
    afterCompletion(ref, result);
  }

  /** Counts one tower report (see the class doc); a no-op for anything that does not count. */
  public void record(FlightReportMessage report) {
    if (report.recordType() != RecordType.RECORD || report.charterRef() == null) {
      return;
    }
    String bucket = bucketFor(report);
    if (report.flightId() == null || bucket == null) {
      log.warn("charter {}: report not countable (flightId={}, outcome={}, disposition={})",
          report.charterRef(), report.flightId(), report.outcome(), report.disposition());
      return;
    }
    afterCompletion(report.charterRef(),
        tally("move", report.charterRef(), report.flightId().toString(), "", bucket));
  }

  /** Discard: parked moves to discarded; a never-reported park is counted discarded. Idempotent. */
  @Override
  public void discarded(String charterRef, UUID flightId) {
    long result = tally("move", charterRef, flightId.toString(), PARKED, DISCARDED);
    if (result == 0) {
      result = tally("move", charterRef, flightId.toString(), "", DISCARDED);
    }
    afterCompletion(charterRef, result);
  }

  /** Replay, before the runway publish: un-counts a flight counted parked. Idempotent. */
  @Override
  public void replayed(String charterRef, UUID flightId) {
    tally("move", charterRef, flightId.toString(), PARKED, "");
  }

  /**
   * Aborts a charter: the 7-day skip-window marker, then a forced completion with source
   * {@code ABORT} ({@code CHARTER_ABORTED}, then {@code CHARTER_COMPLETED} if it was still
   * open). Idempotent. The marker is written even for an unknown ref, so leftover flights of an
   * expired charter are skipped too.
   *
   * @return false when no charter is stored under {@code charterRef}
   */
  public boolean abort(String charterRef, String actor) {
    String by = actor == null || actor.isBlank() ? "anonymous" : actor;
    redis.opsForValue().set(CharterKeys.abortedKey(charterRef), by, CLOSED_TTL);
    long result = tally("complete", charterRef, SOURCE_ABORT, by);
    if (result == UNKNOWN) {
      log.warn("charter {} abort by {}: no charter stored; skip-window marker written", charterRef, by);
      return false;
    }
    log.info("charter {} aborted by {}", charterRef, by);
    if ((result & CHANGED) != 0) {
      notifyFromSnapshot(NotifyEvent.Type.CHARTER_ABORTED, charterRef, Map.of("actor", by));
    }
    afterCompletion(charterRef, result);
    return true;
  }

  /** The sweep's forced completion (source {@code SWEEP}); true when this call completed it. */
  boolean forceComplete(String charterRef) {
    long result = tally("complete", charterRef, SOURCE_SWEEP, "");
    afterCompletion(charterRef, result);
    return result != UNKNOWN && (result & COMPLETED) != 0;
  }

  /** Drops an open-set entry whose hash is gone, freeing its admission key; false if the hash exists. */
  boolean abandon(String charterRef) {
    return tally("abandon", charterRef) == CHANGED;
  }

  /**
   * Whether the charter's skip window is live: the flight consumer's per-flight check. Fails
   * open (an unreadable marker never skips a flight), like the status reads.
   */
  public boolean isAborted(String charterRef) {
    try {
      return Boolean.TRUE.equals(redis.hasKey(CharterKeys.abortedKey(charterRef)));
    } catch (RuntimeException e) {
      log.warn("charter {} abort marker unreadable; treating it as not aborted", charterRef, e);
      return false;
    }
  }

  /** The refs of every open charter. Redis failures propagate. */
  public Set<String> openCharterRefs() {
    Set<String> refs = redis.opsForSet().members(CharterKeys.OPEN_SET);
    return refs == null ? Set.of() : refs;
  }

  /** The stored charter, or empty when there is none (never opened, or past its retention). */
  public Optional<CharterSnapshot> snapshot(String charterRef) {
    Map<Object, Object> raw = redis.opsForHash().entries(CharterKeys.key(charterRef));
    if (raw == null || raw.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new CharterSnapshot(charterRef, str(raw, "airportId"), str(raw, "gateId"),
        str(raw, "charterId"), str(raw, "status"), count(raw, "expected"), count(raw, LANDED),
        count(raw, REJECTED), count(raw, SKIPPED), count(raw, PARKED), count(raw, DISCARDED),
        number(raw, "openedAt"), number(raw, "lastProgressAt"), number(raw, "completedAt"),
        str(raw, "source"), number(raw, "abortedAt"), str(raw, "abortedBy")));
  }

  /** The bucket a RECORD row counts in, or null when it does not count. */
  static String bucketFor(FlightReportMessage report) {
    if (Disposition.PARKED.name().equals(report.disposition())) {
      return PARKED;
    }
    if (Disposition.RETRYING.name().equals(report.disposition()) || report.outcome() == null) {
      return null;
    }
    return switch (report.outcome()) {
      case "LANDED" -> LANDED;
      case "INCIDENT" -> REJECTED;
      case "SKIPPED" -> SKIPPED;
      default -> null;
    };
  }

  private long tally(String op, String charterRef, String... opArgs) {
    List<String> keys = List.of(CharterKeys.key(charterRef), CharterKeys.seenKey(charterRef),
        CharterKeys.OPEN_SET, CharterKeys.admissionKey(CharterKeys.scopeOf(charterRef)));
    List<Object> args = new ArrayList<>(List.of(op, charterRef,
        String.valueOf(System.currentTimeMillis()), String.valueOf(CLOSED_TTL.toSeconds())));
    args.addAll(List.of(opArgs));
    Long result = redis.execute(tallyScript, keys, args.toArray());
    return result == null ? 0 : result;
  }

  private void afterCompletion(String charterRef, long result) {
    if (result == UNKNOWN || (result & COMPLETED) == 0) {
      return;
    }
    notifyFromSnapshot(NotifyEvent.Type.CHARTER_COMPLETED, charterRef, Map.of());
  }

  /** Best effort: the transition already happened, so a failure here is only logged. */
  private void notifyFromSnapshot(NotifyEvent.Type type, String charterRef,
      Map<String, String> extra) {
    try {
      CharterSnapshot s = snapshot(charterRef).orElse(null);
      if (s == null) {
        log.warn("charter {} vanished before its {} notification", charterRef, type);
        return;
      }
      Map<String, String> detail = new LinkedHashMap<>();
      detail.put("charterRef", charterRef);
      detail.put("charterId", s.charterId());
      detail.put("expected", String.valueOf(s.expected()));
      if (type == NotifyEvent.Type.CHARTER_COMPLETED) {
        detail.put("source", s.source());
        detail.put(LANDED, String.valueOf(s.landed()));
        detail.put(REJECTED, String.valueOf(s.rejected()));
        detail.put(SKIPPED, String.valueOf(s.skipped()));
        detail.put(PARKED, String.valueOf(s.parked()));
        detail.put(DISCARDED, String.valueOf(s.discarded()));
      }
      detail.putAll(extra);
      String reason = type == NotifyEvent.Type.CHARTER_COMPLETED ? s.source() : null;
      notify.dispatch(new NotifyEvent(type, s.airportId(), s.gateId(), reason, null, detail));
    } catch (RuntimeException e) {
      log.warn("charter {} {} notification failed", charterRef, type, e);
    }
  }

  private static String str(Map<Object, Object> raw, String field) {
    Object v = raw.get(field);
    return v == null ? null : v.toString();
  }

  private static long count(Map<Object, Object> raw, String field) {
    Long v = number(raw, field);
    return v == null ? 0L : v;
  }

  private static Long number(Map<Object, Object> raw, String field) {
    Object v = raw.get(field);
    if (v == null) {
      return null;
    }
    try {
      return Long.parseLong(v.toString());
    } catch (NumberFormatException malformed) {
      return null;
    }
  }
}
