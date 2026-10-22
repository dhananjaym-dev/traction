package dev.traction.core.controlplane;

import dev.traction.core.api.AirportStatus;
import dev.traction.core.registry.AirportAnnouncement;
import dev.traction.core.registry.AirportAnnouncement.GateAnnouncement;
import dev.traction.core.status.ScopeRef;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Periodic re-broadcast of the STORED status, the convergence carrier for {@code tr:events}
 * (pub/sub is at-most-once). Every {@code traction.control-plane.status-sweep-interval} it
 * publishes each declared airport's stored status, and each ground-stopped gate's, exactly as
 * {@link StatusStore} holds them. It never writes status and never reopens anything: listeners
 * apply each event idempotently, so a consumer that paused itself (or missed a broadcast) while
 * its airport is OPEN resumes within one interval.
 *
 * <p>Runs on its own single daemon thread, started and stopped with the bean's lifecycle (no
 * {@code @EnableScheduling} needed on the host). A failed sweep (Redis down) stops at the first
 * error, logs one WARN line for the whole outage (no stack traces) and the next sweep retries.
 */
@Slf4j
public class StatusSweep implements SmartLifecycle {

  private final StatusStore statusStore;
  private final AnnouncementProjector fleet;
  private final Duration interval;
  private ScheduledExecutorService executor;
  private volatile boolean failing;

  public StatusSweep(StatusStore statusStore, AnnouncementProjector fleet, Duration interval) {
    if (interval == null || interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException("status sweep interval must be positive: " + interval);
    }
    this.statusStore = statusStore;
    this.fleet = fleet;
    this.interval = interval;
  }

  /** One pass over the fleet. Never throws (a throw would cancel the scheduled task). */
  public void sweepOnce() {
    try {
      int published = 0;
      for (String airportId : fleet.airportIds()) {
        ScopeRef airport = ScopeRef.airport(airportId);
        statusStore.publish(airport, statusStore.snapshot(airport));
        published++;
        for (GateAnnouncement gate : fleet.declaration(airportId)
            .map(AirportAnnouncement::gates).orElse(List.of())) {
          ScopeRef scope = ScopeRef.gate(airportId, gate.gateId());
          StatusStore.StatusSnapshot stored = statusStore.snapshot(scope);
          if (stored.status() != AirportStatus.OPEN) {
            statusStore.publish(scope, stored);
            published++;
          }
        }
      }
      if (failing) {
        failing = false;
        log.info("status sweep recovered");
      }
      log.debug("status sweep re-broadcast {} scopes", published);
    } catch (RuntimeException e) {
      if (!failing) {
        failing = true;
        log.warn("status sweep failed ({}: {}); retrying every {}s until it recovers",
            e.getClass().getSimpleName(), e.getMessage(), interval.toSeconds());
      }
    }
  }

  @Override
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "traction-status-sweep");
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
