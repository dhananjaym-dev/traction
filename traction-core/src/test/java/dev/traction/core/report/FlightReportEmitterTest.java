package dev.traction.core.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.traction.core.api.Evidence;
import dev.traction.core.api.Flight;
import dev.traction.core.api.FlightEventHook;
import dev.traction.core.api.FlightRecord;
import dev.traction.core.api.FlightRecord.Disposition;
import dev.traction.core.api.FlightReport;
import dev.traction.core.api.IncidentCategory;
import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.topology.QueueNames;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FlightReportEmitterTest {

  private final ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
  private final List<FlightRecord> records = new ArrayList<>();
  private final FlightReportEmitter emitter = new FlightReportEmitter(publisher, List.of(records::add));
  private final Flight<String> flight = new Flight<>(UUID.randomUUID(), "crm:acme", "contact",
      "contact-42", "ch-1", 2, 1, "abc123", String.class.getName(), "payload");

  private FlightReportMessage sentMessage() {
    ArgumentCaptor<FlightReportMessage> msg = ArgumentCaptor.forClass(FlightReportMessage.class);
    verify(publisher).publish(eq(QueueNames.tower()), msg.capture(), isNull());
    return msg.getValue();
  }

  @Test
  void recordGoesToTheTowerQueueAndThenToHooks() {
    long before = System.currentTimeMillis();
    emitter.emitRecord(flight, FlightReport.incident(IncidentCategory.FLIGHT_FAULT,
        new Evidence("422", "invalid-email", "x".repeat(5000))), Disposition.TERMINAL);

    FlightReportMessage m = sentMessage();
    assertEquals(FlightReportMessage.RecordType.RECORD, m.recordType());
    assertEquals("INCIDENT", m.outcome());
    assertEquals("FLIGHT_FAULT", m.category());
    assertEquals("TERMINAL", m.disposition());
    assertEquals(flight.flightId(), m.flightId());
    assertEquals("abc123", m.correlationId());
    assertEquals(2, m.attemptCount());
    assertEquals(1, m.holdCount());
    assertEquals("422", m.evidenceCode());
    assertEquals(FlightReportMessage.EVIDENCE_MAX_EXCERPT_LENGTH, m.evidenceExcerpt().length()); // clamped
    assertNotNull(m.reportId());
    assertTrue(m.emittedAtEpochMillis() >= before);

    FlightRecord r = records.get(0);
    assertEquals(FlightRecord.RecordType.RECORD, r.recordType());
    assertEquals(FlightRecord.Outcome.INCIDENT, r.outcome());
    assertEquals(Disposition.TERMINAL, r.disposition());
    assertEquals(IncidentCategory.FLIGHT_FAULT, r.category());
    assertEquals("ch-1", r.charterRef());
    assertEquals(m.emittedAtEpochMillis(), r.epochMillis());
  }

  @Test
  void interimCarriesTheCallersReasonAndRetrying() {
    emitter.emitInterim(flight, (FlightReport.Incident) FlightReport.incident(
        IncidentCategory.TRANSIENT, null), "HELD_1");

    FlightReportMessage m = sentMessage();
    assertEquals(FlightReportMessage.RecordType.INTERIM, m.recordType());
    assertEquals("HELD_1", m.reason());
    assertEquals("RETRYING", m.disposition());
    assertEquals("HELD_1", records.get(0).reason());
    assertEquals(Disposition.RETRYING, records.get(0).disposition());
  }

  @Test
  void publishFailurePropagatesAndNoHookSeesTheRecord() {
    doThrow(new ConfirmedPublisher.PublishException("broker down"))
        .when(publisher).publish(any(), any(), any());

    assertThrows(ConfirmedPublisher.PublishException.class,
        () -> emitter.emitRecord(flight, FlightReport.landed(), Disposition.TERMINAL));
    assertTrue(records.isEmpty());
  }

  @Test
  void aThrowingHookNeitherPropagatesNorStarvesLaterHooks() {
    FlightEventHook broken = r -> { throw new IllegalStateException("hook bug"); };
    FlightReportEmitter withBroken = new FlightReportEmitter(publisher, List.of(broken, records::add));

    assertDoesNotThrow(() -> withBroken.emitRecord(flight, FlightReport.landed(), Disposition.TERMINAL));
    assertEquals(1, records.size());
  }
}
