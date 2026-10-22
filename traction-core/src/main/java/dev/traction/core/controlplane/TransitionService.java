package dev.traction.core.controlplane;

import dev.traction.core.api.Evidence;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.api.StopReason;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.status.ScopeRef;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Guarded status transitions with exactly-once notification. The status change itself is the
 * {@link StatusStore}'s atomic compare-and-set; only the caller whose call actually changed the
 * status notifies the {@link dev.traction.core.api.NotifyHook}s. A flood of AIRPORT_FAULT
 * reports racing through several tower consumers therefore yields one GROUND_STOP event, and a
 * repeated reopen yields one REOPEN.
 *
 * <p>Automatic transitions only raise severity (OPEN &rarr; GROUND_STOP); lowering it is the
 * explicit {@link #reopen} path, which also forgets a gate's streaks so a reopened gate needs a
 * fresh streak to trip again.
 */
@Slf4j
public class TransitionService {

  private final StatusStore statusStore;
  private final StreakTracker streaks;
  private final NotifyDispatcher notify;

  public TransitionService(StatusStore statusStore, StreakTracker streaks, NotifyDispatcher notify) {
    this.statusStore = statusStore;
    this.streaks = streaks;
    this.notify = notify;
  }

  /**
   * Ground-stops {@code scope} unless it is already stopped (or the report predates the last
   * reopen). True, and one GROUND_STOP notification, only when this call made the change.
   */
  public boolean groundStop(ScopeRef scope, StopReason reason, String summary, Evidence evidence,
      Map<String, String> detail, long reportedAtMillis) {
    if (!statusStore.groundStop(scope, reason, summary, reportedAtMillis)) {
      log.debug("ground stop of {} not applied (already stopped or stale report)", scope.value());
      return false;
    }
    log.warn("{} GROUND_STOP reason={}: {}", scope.value(), reason, summary);
    notify.dispatch(new NotifyEvent(NotifyEvent.Type.GROUND_STOP, scope.airportId(),
        scope.gateId(), reason.name(), evidence, detail));
    return true;
  }

  /**
   * The sanctioned way back to OPEN (an operator's reopen). True, and one REOPEN notification,
   * only when this call changed the status; {@code note} rides along as the event's reason.
   */
  public boolean reopen(ScopeRef scope, String note) {
    if (!statusStore.reopen(scope)) {
      return false;
    }
    if (scope.gateId() != null) {
      streaks.clear(scope.value());
    }
    log.info("{} REOPEN ({})", scope.value(), note);
    notify.dispatch(new NotifyEvent(NotifyEvent.Type.REOPEN, scope.airportId(), scope.gateId(),
        note, null, null));
    return true;
  }
}
