package dev.traction.core.controlplane;

import static dev.traction.core.controlplane.Reports.AIRPORT;
import static dev.traction.core.controlplane.Reports.airportFault;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.traction.core.api.NotifyEvent;
import dev.traction.core.api.StopReason;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.status.ScopeRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Transitions notify only when this caller's compare-and-set actually changed the status. */
class TransitionServiceTest {

  private final StatusStore store = mock(StatusStore.class);
  private final StreakTracker streaks = mock(StreakTracker.class);
  private final List<NotifyEvent> notified = new ArrayList<>();
  private final TransitionService transitions =
      new TransitionService(store, streaks, new NotifyDispatcher(List.of(notified::add)));
  private final GroundStopRules rules = new GroundStopRules(streaks, transitions);

  @Test
  void secondAirportFaultWhileGroundedDoesNotNotifyAgain() {
    ScopeRef airport = ScopeRef.airport(AIRPORT);
    // The store's atomic compare-and-set: only the first caller moves OPEN -> GROUND_STOP.
    when(store.groundStop(eq(airport), eq(StopReason.AIRPORT_FAULT), anyString(), anyLong()))
        .thenReturn(true, false);

    rules.evaluate(airportFault());
    rules.evaluate(airportFault());

    assertEquals(1, notified.size());
    NotifyEvent event = notified.get(0);
    assertEquals(NotifyEvent.Type.GROUND_STOP, event.type());
    assertEquals(AIRPORT, event.airportId());
    assertNull(event.gateId());
    assertEquals("AIRPORT_FAULT", event.reason());
    assertEquals("401", event.evidence().code());
  }

  @Test
  void reopenClearsGateStreaksAndNotifiesOnlyOnChange() {
    ScopeRef gate = ScopeRef.gate(AIRPORT, "company-sync");
    when(store.reopen(gate)).thenReturn(true, false);

    assertTrue(transitions.reopen(gate, "manual"));
    transitions.reopen(gate, "manual");

    verify(streaks).clear("crm:acme/company-sync");
    assertEquals(1, notified.size());
    assertEquals(NotifyEvent.Type.REOPEN, notified.get(0).type());
    assertEquals("company-sync", notified.get(0).gateId());
  }

  @Test
  void aThrowingHookNeitherFailsTheTransitionNorStarvesTheOthers() {
    ScopeRef airport = ScopeRef.airport(AIRPORT);
    when(store.reopen(airport)).thenReturn(true);
    TransitionService withBadHook = new TransitionService(store, streaks, new NotifyDispatcher(
        List.of(e -> { throw new IllegalStateException("hook down"); }, notified::add)));

    assertTrue(withBadHook.reopen(airport, "manual"));

    assertEquals(1, notified.size());
    verify(streaks, never()).clear(any());
  }
}
