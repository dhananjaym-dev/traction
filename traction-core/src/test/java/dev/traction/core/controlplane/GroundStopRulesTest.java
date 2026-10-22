package dev.traction.core.controlplane;

import static dev.traction.core.controlplane.Reports.AIRPORT;
import static dev.traction.core.controlplane.Reports.EMITTED_AT;
import static dev.traction.core.controlplane.Reports.airportFault;
import static dev.traction.core.controlplane.Reports.incident;
import static dev.traction.core.controlplane.Reports.recordIncident;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.traction.core.api.Evidence;
import dev.traction.core.api.StopReason;
import dev.traction.core.report.FlightReportMessage.RecordType;
import dev.traction.core.status.ScopeRef;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroundStopRulesTest {

  private final StreakTracker streaks = mock(StreakTracker.class);
  private final TransitionService transitions = mock(TransitionService.class);
  private final GroundStopRules rules = new GroundStopRules(streaks, transitions);

  @Test
  void firstAirportFaultGroundStopsTheAirportWithoutAStreak() {
    rules.evaluate(airportFault());

    verify(transitions).groundStop(eq(ScopeRef.airport(AIRPORT)), eq(StopReason.AIRPORT_FAULT),
        startsWith("401 | token expired"), eq(new Evidence("401", null, "token expired")),
        eq(Map.of("category", "AIRPORT_FAULT", "flight", "abc123")), eq(EMITTED_AT));
    verifyNoInteractions(streaks);
  }

  @Test
  void gateFaultStreakGroundsTheGateOnly() {
    when(streaks.increment("crm:acme/company-sync", "GATE_FAULT", "co-3", Duration.ofMinutes(5)))
        .thenReturn(3L);

    rules.evaluate(recordIncident("GATE_FAULT", "company-sync", "co-3"));

    verify(transitions).groundStop(eq(ScopeRef.gate(AIRPORT, "company-sync")),
        eq(StopReason.GATE_FAULT), eq("3 distinct GATE_FAULT failures in 5m flight=abc123"),
        any(), anyMap(), eq(EMITTED_AT));
    verify(transitions, never()).groundStop(eq(ScopeRef.airport(AIRPORT)), any(), anyString(),
        any(), anyMap(), anyLong());
  }

  @Test
  void transientAndRateLimitNeverGround() {
    rules.evaluate(incident(RecordType.INTERIM, "TRANSIENT", "contact-sync", "c-1", "RETRYING"));
    rules.evaluate(recordIncident("TRANSIENT", "contact-sync", "c-1"));
    rules.evaluate(incident(RecordType.INTERIM, "RATE_LIMIT", "contact-sync", "c-2", "RETRYING"));

    verifyNoInteractions(streaks, transitions);
  }
}
