package dev.traction.core.controlplane;

import static dev.traction.core.controlplane.Reports.AIRPORT;
import static dev.traction.core.controlplane.Reports.airportFault;
import static dev.traction.core.controlplane.Reports.landed;
import static dev.traction.core.controlplane.Reports.recordIncident;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.traction.core.api.AirportStatus;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.registry.AirportAnnouncement;
import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.status.ScopeRef;
import dev.traction.core.status.StatusReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TowerConsumerTest {

  private final GroundStopRules rules = mock(GroundStopRules.class);
  private final IncidentSummaryStore incidents = mock(IncidentSummaryStore.class);
  private final StatusReader status = mock(StatusReader.class);
  private final AnnouncementProjector projector = mock(AnnouncementProjector.class);
  private final List<NotifyEvent> notified = new ArrayList<>();
  private final TowerConsumer tower = new TowerConsumer(rules, incidents, status,
      new NotifyDispatcher(List.of(notified::add)), projector);

  @BeforeEach
  void setUp() {
    when(status.get(any())).thenReturn(AirportStatus.OPEN);
    when(incidents.allowNotify(anyString(), anyString())).thenReturn(true);
  }

  @Test
  void anIncidentRecordRoutesToRulesSummaryStoreAndHooks() {
    FlightReportMessage parked = recordIncident("GATE_FAULT", "company-sync", "co-1");
    FlightReportMessage oversized = new FlightReportMessage(parked.recordType(), parked.flightId(),
        parked.airportId(), parked.gateId(), parked.domainRef(), null, parked.outcome(),
        parked.category(), null, "422", null, "x".repeat(5000), 1, 0, "abc123",
        parked.reportId(), parked.emittedAtEpochMillis(), parked.disposition());

    tower.onReport(oversized);

    ArgumentCaptor<FlightReportMessage> clamped = ArgumentCaptor.forClass(FlightReportMessage.class);
    verify(rules).evaluate(clamped.capture());
    assertEquals(FlightReportMessage.EVIDENCE_MAX_EXCERPT_LENGTH,
        clamped.getValue().evidenceExcerpt().length()); // ingress re-clamp
    verify(incidents).record(clamped.getValue());
    assertEquals(1, notified.size());
    NotifyEvent event = notified.get(0);
    assertEquals(NotifyEvent.Type.FLIGHT_INCIDENT, event.type());
    assertEquals("company-sync", event.gateId());
    assertEquals("GATE_FAULT", event.reason());
    assertEquals("PARKED", event.detail().get("disposition"));
  }

  @Test
  void airportFaultReportsAreRecordedButNeverNotifiedPerFlight() {
    tower.onReport(airportFault());

    verify(rules).evaluate(any());
    verify(incidents).record(any());
    assertTrue(notified.isEmpty()); // the airport's GROUND_STOP is the one notification
  }

  @Test
  void anIncidentOnAGroundedScopeIsNotNotifiedAgain() {
    when(status.get(ScopeRef.gate(AIRPORT, "company-sync"))).thenReturn(AirportStatus.GROUND_STOP);

    tower.onReport(recordIncident("GATE_FAULT", "company-sync", "co-3"));

    verify(incidents).record(any());
    assertTrue(notified.isEmpty());
  }

  @Test
  void aRulesFailureNeverFailsTheReport() {
    doThrow(new IllegalStateException("redis down")).when(rules).evaluate(any());

    tower.onReport(recordIncident("FLIGHT_FAULT", "contact-sync", "c-1"));

    verify(incidents).record(any());
    assertEquals(1, notified.size());
  }

  @Test
  void aLandingFeedsOnlyTheRules() {
    tower.onReport(landed("contact-sync"));

    verify(rules).evaluate(any());
    verify(incidents, never()).record(any());
    assertTrue(notified.isEmpty());
  }

  @Test
  void anAnnouncementIsProjected() {
    AirportAnnouncement announcement = AirportAnnouncement.of(AIRPORT, "demo-app", "Acme CRM",
        60, 60L, 60, List.of(new AirportAnnouncement.GateAnnouncement("contact-sync", 1)));

    tower.onAnnouncement(announcement);

    verify(projector).project(announcement);
  }
}
