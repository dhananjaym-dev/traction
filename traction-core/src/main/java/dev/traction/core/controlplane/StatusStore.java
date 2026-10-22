package dev.traction.core.controlplane;

import dev.traction.core.api.AirportStatus;
import dev.traction.core.api.StopReason;
import dev.traction.core.status.Events;
import dev.traction.core.status.ScopeRef;
import dev.traction.core.status.StatusReader;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * The control plane's status writer. Status lives only in Redis, in the hash that the data
 * plane's {@link StatusReader} reads (one hash per scope, no TTL):
 * <pre>
 *   tr:status:&lt;airportId&gt;           e.g. tr:status:crm:acme
 *   tr:status:&lt;airportId&gt;/&lt;gateId&gt;  e.g. tr:status:crm:acme/company-sync
 *     status    OPEN | GROUND_STOP | CLOSED   (the only field StatusReader reads; absent = OPEN)
 *     reason    StopReason name               (present while stopped)
 *     detail    human summary of the trigger  (present while stopped)
 *     since     epoch ms of the last change
 *     openedAt  epoch ms of the last reopen   (stale-report guard, see status_transition.lua)
 * </pre>
 *
 * <p>Every change is one atomic compare-and-set script ({@code traction/status_transition.lua}),
 * so of many tower consumers racing on the same scope exactly one sees {@code true} and goes on
 * to notify. The winner then broadcasts on {@code tr:events} ({@link Events}); a failed
 * broadcast is logged and never undoes the change, because every consumer re-reads the hash
 * per flight. Redis failures on the write itself propagate.
 */
@Slf4j
public class StatusStore {

  static final String REASON_FIELD = "reason";
  static final String DETAIL_FIELD = "detail";
  static final String SINCE_FIELD = "since";

  /** One scope's stored status. {@code reason}, {@code detail}, {@code sinceEpochMillis} may be null. */
  public record StatusSnapshot(AirportStatus status, StopReason reason, String detail,
      Long sinceEpochMillis) { }

  private final StringRedisTemplate redis;
  private final RedisScript<String> transitionScript;

  public StatusStore(StringRedisTemplate redis) {
    this.redis = redis;
    DefaultRedisScript<String> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("traction/status_transition.lua")));
    script.setResultType(String.class);
    this.transitionScript = script;
  }

  public AirportStatus status(ScopeRef scope) {
    return snapshot(scope).status();
  }

  /** The stored status hash; a missing key (or unknown value) reads as OPEN. */
  public StatusSnapshot snapshot(ScopeRef scope) {
    Map<Object, Object> hash = redis.opsForHash().entries(StatusReader.key(scope));
    AirportStatus status = parse(AirportStatus.class, hash.get(StatusReader.STATUS_FIELD));
    StopReason reason = parse(StopReason.class, hash.get(REASON_FIELD));
    Object detail = hash.get(DETAIL_FIELD);
    Object since = hash.get(SINCE_FIELD);
    return new StatusSnapshot(status == null ? AirportStatus.OPEN : status, reason,
        detail == null ? null : detail.toString(), since == null ? null : toLong(since.toString()));
  }

  /** A manual ground stop, effective now. */
  public boolean groundStop(ScopeRef scope, StopReason reason) {
    long now = System.currentTimeMillis();
    return groundStop(scope, reason, null, now);
  }

  /**
   * OPEN &rarr; GROUND_STOP. True only for the caller that made the change: false when the scope
   * is already stopped or CLOSED, or when {@code reportedAtMillis} predates the last reopen.
   */
  public boolean groundStop(ScopeRef scope, StopReason reason, String detail, long reportedAtMillis) {
    String previous = redis.execute(transitionScript, List.of(StatusReader.key(scope)), "stop",
        String.valueOf(System.currentTimeMillis()), reason.name(), detail == null ? "" : detail,
        String.valueOf(reportedAtMillis));
    if (previous == null) {
      return false;
    }
    log.info("status {} {} -> GROUND_STOP (reason={})", scope.value(), previous, reason);
    broadcast(scope, AirportStatus.GROUND_STOP, reason);
    return true;
  }

  /**
   * Any non-OPEN status &rarr; OPEN. True only when this call changed it. The OPEN broadcast goes
   * out either way: it is idempotent for listeners, and it resumes a consumer that paused itself
   * on a fault whose ground stop was never recorded.
   */
  public boolean reopen(ScopeRef scope) {
    String previous = redis.execute(transitionScript, List.of(StatusReader.key(scope)), "reopen",
        String.valueOf(System.currentTimeMillis()));
    if (previous != null) {
      log.info("status {} {} -> OPEN", scope.value(), previous);
    }
    broadcast(scope, AirportStatus.OPEN, null);
    return previous != null;
  }

  /**
   * Re-broadcasts a stored status on {@code tr:events} without writing it (the status sweep).
   * Redis failures propagate.
   */
  public void publish(ScopeRef scope, StatusSnapshot stored) {
    redis.convertAndSend(Events.CHANNEL, Events.statusChanged(scope, stored.status(), stored.reason()));
  }

  private void broadcast(ScopeRef scope, AirportStatus status, StopReason reason) {
    try {
      redis.convertAndSend(Events.CHANNEL, Events.statusChanged(scope, status, reason));
    } catch (RuntimeException e) {
      log.warn("{} broadcast failed for {}; consumers converge on their per-flight status read",
          Events.CHANNEL, scope.value(), e);
    }
  }

  private static <E extends Enum<E>> E parse(Class<E> type, Object value) {
    if (value == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.toString());
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  private static Long toLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
