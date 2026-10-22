package dev.traction.core.charter;

import dev.traction.core.status.ScopeRef;
import dev.traction.core.status.StatusReader;
import dev.traction.core.topology.QueueNames;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.context.SmartLifecycle;

/**
 * The control plane's charter backstop, every {@code traction.control-plane.charter-sweep-interval}
 * over the open charters:
 * <ul>
 *   <li>an open-set entry whose charter hash is gone is abandoned (and its admission key freed);</li>
 *   <li>a charter with no progress for {@code traction.control-plane.charter-stale-after}, whose
 *       airport and gate are consuming and whose runway and holding queues are provably empty,
 *       is force-completed with {@code source=SWEEP}: its remaining flights were lost (or never
 *       dispatched), and the counts it closes with are best effort.</li>
 * </ul>
 * Anything that cannot be proven drained waits: an unknown queue depth is not empty, and a
 * grounded airport or gate waits for a human. Completion is latched, so several control planes
 * sweeping at once are harmless.
 *
 * <p>Runs on its own daemon thread, started and stopped with the bean's lifecycle. One failing
 * charter never stops the rest of the pass; a failed pass (Redis down) logs once per outage.
 */
@Slf4j
public class CharterSweep implements SmartLifecycle {

  private final CharterBoard board;
  private final StatusReader status;
  private final AmqpAdmin admin;
  private final Duration interval;
  private final Duration staleAfter;
  private final Clock clock;
  private ScheduledExecutorService executor;
  private volatile boolean failing;

  public CharterSweep(CharterBoard board, StatusReader status, AmqpAdmin admin, Duration interval,
      Duration staleAfter) {
    this(board, status, admin, interval, staleAfter, Clock.systemUTC());
  }

  CharterSweep(CharterBoard board, StatusReader status, AmqpAdmin admin, Duration interval,
      Duration staleAfter, Clock clock) {
    if (interval == null || interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException("charter sweep interval must be positive: " + interval);
    }
    if (staleAfter == null || staleAfter.isNegative()) {
      throw new IllegalArgumentException("charter stale-after must not be negative: " + staleAfter);
    }
    this.board = board;
    this.status = status;
    this.admin = admin;
    this.interval = interval;
    this.staleAfter = staleAfter;
    this.clock = clock;
  }

  /** One pass over the open charters. Never throws (a throw would cancel the scheduled task). */
  public void sweepOnce() {
    try {
      for (String ref : board.openCharterRefs()) {
        try {
          sweep(ref);
        } catch (RuntimeException e) {
          log.warn("charter sweep failed for {}", ref, e);
        }
      }
      if (failing) {
        failing = false;
        log.info("charter sweep recovered");
      }
    } catch (RuntimeException e) {
      if (!failing) {
        failing = true;
        log.warn("charter sweep failed ({}: {}); retrying every {}s until it recovers",
            e.getClass().getSimpleName(), e.getMessage(), interval.toSeconds());
      }
    }
  }

  private void sweep(String ref) {
    Optional<CharterBoard.CharterSnapshot> stored = board.snapshot(ref);
    if (stored.isEmpty()) {
      if (board.abandon(ref)) {
        log.warn("charter {} is open but its hash is gone; abandoned", ref);
      }
      return;
    }
    CharterBoard.CharterSnapshot s = stored.get();
    long lastProgress = s.lastProgressAt() != null ? s.lastProgressAt()
        : s.openedAt() != null ? s.openedAt() : 0L;
    if (lastProgress > clock.millis() - staleAfter.toMillis() || !drained(s.airportId(), s.gateId())) {
      return;
    }
    log.info("charter {} stale since {} and its gate is drained; force-completing with {}/{} accounted",
        ref, lastProgress, s.accounted(), s.expected());
    board.forceComplete(ref);
  }

  private boolean drained(String airportId, String gateId) {
    if (airportId == null || gateId == null
        || !status.get(ScopeRef.airport(airportId)).allowsConsumption()
        || !status.get(ScopeRef.gate(airportId, gateId)).allowsConsumption()) {
      return false;
    }
    QueueNames names = QueueNames.of(airportId);
    return empty(names.runway()) && empty(names.holding());
  }

  private boolean empty(String queue) {
    QueueInformation info = admin.getQueueInfo(queue);
    if (info == null) {
      log.debug("charter sweep: depth of {} unknown; not provably drained", queue);
      return false;
    }
    return info.getMessageCount() == 0;
  }

  @Override
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "traction-charter-sweep");
      t.setDaemon(true);
      return t;
    });
    executor.scheduleWithFixedDelay(this::sweepOnce, interval.toMillis(), interval.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return executor != null;
  }
}
