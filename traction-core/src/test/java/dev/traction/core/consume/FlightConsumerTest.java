package dev.traction.core.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dev.traction.core.api.AirportSpec;
import dev.traction.core.api.AirportStatus;
import dev.traction.core.api.Evidence;
import dev.traction.core.api.Flight;
import dev.traction.core.api.FlightRecord;
import dev.traction.core.api.FlightRecord.Disposition;
import dev.traction.core.api.FlightRecord.Outcome;
import dev.traction.core.api.FlightRecord.RecordType;
import dev.traction.core.api.FlightReport;
import dev.traction.core.api.FlightReporter;
import dev.traction.core.api.IncidentCategory;
import dev.traction.core.api.Pilot;
import dev.traction.core.charter.CharterBoard;
import dev.traction.core.clearance.ClearanceResult;
import dev.traction.core.clearance.ClearanceService;
import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.report.FlightReportEmitter;
import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.status.ScopeRef;
import dev.traction.core.status.StatusReader;
import dev.traction.core.topology.QueueNames;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * One test per settle path: what the delivery settles to (ack / nack / republish target) and the
 * {@link FlightRecord} the hook receives. Emitter and holding pattern are real (over a mocked
 * confirmed publisher) so queue targets, TTLs and record shapes are asserted end to end.
 */
class FlightConsumerTest {

  record Contact(String email) { }

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String AIRPORT = "crm:acme";
  private static final QueueNames Q = QueueNames.of(AIRPORT);
  private static final long TAG = 7L;

  private final StatusReader statusReader = mock(StatusReader.class);
  private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
  private final ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
  private final AirportPauser pauser = mock(AirportPauser.class);
  private final ClearanceService clearance = mock(ClearanceService.class);
  private final GroundDelayService groundDelay = mock(GroundDelayService.class);
  private final Channel channel = mock(Channel.class);
  @SuppressWarnings("unchecked")
  private final Pilot<Contact> pilot = mock(Pilot.class);
  private final FlightReporter reporter = mock(FlightReporter.class);
  private final CharterBoard charters = mock(CharterBoard.class);
  private final List<FlightRecord> records = new ArrayList<>();
  private AirportSpec spec;
  private FlightConsumer consumer;

  @BeforeEach
  void setUp() {
    spec = AirportSpec.of(AIRPORT).slots(60, Duration.ofMinutes(1))
        .gate("contact").gate("company", 2).pilot(pilot).reporter(reporter);
    FlightReportEmitter emitter = new FlightReportEmitter(publisher, List.of(records::add));
    consumer = new FlightConsumer(spec, statusReader, emitter, rabbitTemplate, publisher, pauser,
        MAPPER, getClass().getClassLoader(), clearance, groundDelay,
        new HoldingPatternService(publisher), charters);
    when(statusReader.get(any())).thenReturn(AirportStatus.OPEN);
    when(clearance.acquire(eq(AIRPORT), any(), anyInt()))
        .thenReturn(ClearanceResult.cleared());
  }

  // ---- helpers ----

  private static Flight<Contact> flight(String gateId, int holdCount) {
    return new Flight<>(UUID.randomUUID(), AIRPORT, gateId, "contact-42", null, 0, holdCount,
        "abc123", Contact.class.getName(), new Contact("a@acme.test"));
  }

  private static Message message(Flight<?> flight) throws Exception {
    MessageProperties props = new MessageProperties();
    props.setDeliveryTag(TAG);
    return new Message(MAPPER.writeValueAsBytes(flight), props);
  }

  private static Message message(int holdCount) throws Exception {
    return message(flight("contact", holdCount));
  }

  private void reporterReturns(FlightReport report) {
    when(reporter.report(any(), any())).thenReturn(report);
  }

  private static FlightReport incident(IncidentCategory category) {
    return FlightReport.incident(category, new Evidence("503", null, "vendor said no"));
  }

  private FlightRecord onlyRecord() {
    assertEquals(1, records.size(), "expected exactly one record, got " + records);
    return records.get(0);
  }

  private static void assertRecord(FlightRecord r, RecordType type, Outcome outcome,
      Disposition disposition, IncidentCategory category, String reason) {
    assertEquals(type, r.recordType());
    assertEquals(outcome, r.outcome());
    assertEquals(disposition, r.disposition());
    assertEquals(category, r.category());
    assertEquals(reason, r.reason());
  }

  /** Exactly one settle on the delivery, and it is an ack. */
  private void assertAckedOnce() throws Exception {
    verify(channel).basicAck(TAG, false);
    verifyNoMoreInteractions(channel);
  }

  @SuppressWarnings("unchecked")
  private Flight<JsonNode> published(String queue, Duration ttl) {
    ArgumentCaptor<Flight<JsonNode>> captor = ArgumentCaptor.forClass(Flight.class);
    if (ttl == null) {
      verify(publisher).publish(eq(queue), captor.capture(), isNull());
    } else {
      verify(publisher).publish(eq(queue), captor.capture(), eq(ttl));
    }
    return captor.getValue();
  }

  private void verifyNotPublished(String queue) {
    verify(publisher, never()).publish(eq(queue), any(), any());
  }

  // ---- settle paths ----

  @Test
  void landedAcksWithFinalRecord() throws Exception {
    reporterReturns(FlightReport.landed());

    consumer.onMessage(message(flight("company", 0)), channel);

    verify(clearance).acquire(AIRPORT, spec.slots(), 2); // the gate's slot cost
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Flight<Contact>> landed = ArgumentCaptor.forClass(Flight.class);
    verify(pilot).land(landed.capture());
    assertEquals(new Contact("a@acme.test"), landed.getValue().payload()); // typed, not a JSON tree
    assertEquals(1, landed.getValue().attemptCount());
    assertRecord(onlyRecord(), RecordType.RECORD, Outcome.LANDED, Disposition.TERMINAL, null, null);
    verify(publisher).publish(eq(QueueNames.tower()), any(FlightReportMessage.class), isNull());
    assertAckedOnce();
  }

  @Test
  void transientHoldsFirstRoundWithInterimHeld1() throws Exception {
    reporterReturns(incident(IncidentCategory.TRANSIENT));

    consumer.onMessage(message(0), channel);

    Flight<JsonNode> held = published(Q.holding(), Duration.ofSeconds(30));
    assertEquals(1, held.holdCount());
    assertEquals(1, held.attemptCount());
    assertRecord(onlyRecord(), RecordType.INTERIM, Outcome.INCIDENT, Disposition.RETRYING,
        IncidentCategory.TRANSIENT, "HELD_1");
    assertAckedOnce();
  }

  @Test
  void transientPastHoldBudgetParks() throws Exception {
    reporterReturns(incident(IncidentCategory.TRANSIENT));

    consumer.onMessage(message(3), channel); // 30s, 2min, 10min already spent

    published(Q.parked(), null);
    verifyNotPublished(Q.holding());
    assertRecord(onlyRecord(), RecordType.RECORD, Outcome.INCIDENT, Disposition.PARKED,
        IncidentCategory.TRANSIENT, null);
    assertAckedOnce();
  }

  @Test
  void flightFaultIsRejectedFinalAndAcked() throws Exception {
    reporterReturns(incident(IncidentCategory.FLIGHT_FAULT));

    consumer.onMessage(message(0), channel);

    assertRecord(onlyRecord(), RecordType.RECORD, Outcome.INCIDENT, Disposition.TERMINAL,
        IncidentCategory.FLIGHT_FAULT, null);
    verifyNotPublished(Q.parked());
    verifyNotPublished(Q.holding());
    verifyNotPublished(Q.runway());
    assertAckedOnce();
  }

  @Test
  void gateFaultParks() throws Exception {
    reporterReturns(incident(IncidentCategory.GATE_FAULT));

    consumer.onMessage(message(0), channel);

    published(Q.parked(), null);
    assertRecord(onlyRecord(), RecordType.RECORD, Outcome.INCIDENT, Disposition.PARKED,
        IncidentCategory.GATE_FAULT, null);
    assertAckedOnce();
  }

  @Test
  void airportFaultDefersToRunwayTailAndPauses() throws Exception {
    reporterReturns(incident(IncidentCategory.AIRPORT_FAULT));

    consumer.onMessage(message(0), channel);

    assertEquals(1, published(Q.runway(), null).attemptCount()); // the attempted flight
    assertRecord(onlyRecord(), RecordType.INTERIM, Outcome.INCIDENT, Disposition.RETRYING,
        IncidentCategory.AIRPORT_FAULT, "DEFERRED");
    verify(pauser).requestPause(AIRPORT);
    assertAckedOnce();
  }

  @Test
  void rateLimitGroundDelaysOneWindowAndDefers() throws Exception {
    reporterReturns(incident(IncidentCategory.RATE_LIMIT));

    consumer.onMessage(message(0), channel);

    verify(groundDelay).delay(AIRPORT, Duration.ofMinutes(1)); // one slots window
    published(Q.runway(), null);
    assertRecord(onlyRecord(), RecordType.INTERIM, Outcome.INCIDENT, Disposition.RETRYING,
        IncidentCategory.RATE_LIMIT, "DEFERRED");
    verifyNoInteractions(pauser); // later deliveries sleep through the delay instead
    assertAckedOnce();
  }

  @Test
  void internalHoldsOneRoundThenParks() throws Exception {
    reporterReturns(incident(IncidentCategory.INTERNAL));

    consumer.onMessage(message(0), channel);
    published(Q.holding(), Duration.ofSeconds(30));
    assertRecord(onlyRecord(), RecordType.INTERIM, Outcome.INCIDENT, Disposition.RETRYING,
        IncidentCategory.INTERNAL, "HELD_1");

    records.clear();
    consumer.onMessage(message(1), channel); // back from its one holding round

    published(Q.parked(), null);
    assertRecord(onlyRecord(), RecordType.RECORD, Outcome.INCIDENT, Disposition.PARKED,
        IncidentCategory.INTERNAL, null);
  }

  @Test
  void skippedAcksWithSkippedRecord() throws Exception {
    reporterReturns(FlightReport.skipped("ALREADY_SYNCED"));

    consumer.onMessage(message(0), channel);

    assertRecord(onlyRecord(), RecordType.RECORD, Outcome.SKIPPED, Disposition.TERMINAL, null,
        "ALREADY_SYNCED");
    verifyNotPublished(Q.parked());
    assertAckedOnce();
  }

  @Test
  void groundStoppedGateParksAsSkippedGateGrounded() throws Exception {
    when(statusReader.get(ScopeRef.gate(AIRPORT, "contact"))).thenReturn(AirportStatus.GROUND_STOP);

    consumer.onMessage(message(0), channel);

    published(Q.parked(), null);
    assertRecord(onlyRecord(), RecordType.RECORD, Outcome.SKIPPED, Disposition.PARKED, null,
        "GATE_GROUNDED");
    verifyNoInteractions(clearance, pilot, reporter);
    assertAckedOnce();
  }

  @Test
  void anAbortedChartersFlightSkipsAsCharterAbortedBeforeClearance() throws Exception {
    Flight<Contact> flight = new Flight<>(UUID.randomUUID(), AIRPORT, "contact", "contact-42",
        "crm:acme/contact/run-1", 0, 0, "abc123", Contact.class.getName(), new Contact("a@acme.test"));
    when(charters.isAborted("crm:acme/contact/run-1")).thenReturn(true);

    consumer.onMessage(message(flight), channel);

    assertRecord(onlyRecord(), RecordType.RECORD, Outcome.SKIPPED, Disposition.TERMINAL, null,
        "CHARTER_ABORTED");
    assertEquals("crm:acme/contact/run-1", onlyRecord().charterRef());
    verifyNoInteractions(clearance, pilot, reporter); // no slot burned, nothing flown
    assertAckedOnce();
  }

  // ---- Review Focus 2: a broken reporter is INTERNAL, and the consumer survives ----

  @Test
  void reporterThrowingIsInternalAndHoldsOneRound() throws Exception {
    when(reporter.report(any(), any())).thenThrow(new IllegalStateException("classifier blew up"));

    consumer.onMessage(message(0), channel);

    published(Q.holding(), Duration.ofSeconds(30));
    assertRecord(onlyRecord(), RecordType.INTERIM, Outcome.INCIDENT, Disposition.RETRYING,
        IncidentCategory.INTERNAL, "HELD_1");
    assertAckedOnce();
  }

  @Test
  void reporterReturningNullIsInternalAndParksAfterItsRound() throws Exception {
    reporterReturns(null);

    consumer.onMessage(message(1), channel);

    published(Q.parked(), null);
    assertRecord(onlyRecord(), RecordType.RECORD, Outcome.INCIDENT, Disposition.PARKED,
        IncidentCategory.INTERNAL, null);
    assertAckedOnce();
  }

  // ---- Review Focus 1: Redis down at clearance never bypasses the limit ----

  @Test
  void clearanceRedisFailureDefersToRunwayTailWithoutLanding() throws Exception {
    when(clearance.acquire(eq(AIRPORT), any(), anyInt()))
        .thenThrow(new RedisConnectionFailureException("redis down"));

    consumer.onMessage(message(0), channel); // must not throw

    assertEquals(0, published(Q.runway(), null).attemptCount()); // never attempted
    verifyNoInteractions(pilot, reporter);
    verifyNotPublished(Q.parked());
    verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    assertTrue(records.isEmpty(), "pacing deferrals are not flight records");
    assertAckedOnce();
  }

  // ---- pacing and status paths ----

  @Test
  void groundStoppedAirportDefersAndPauses() throws Exception {
    when(statusReader.get(ScopeRef.airport(AIRPORT))).thenReturn(AirportStatus.GROUND_STOP);

    consumer.onMessage(message(0), channel);

    published(Q.runway(), null);
    verify(pauser).requestPause(AIRPORT);
    verifyNoInteractions(clearance, pilot);
    assertAckedOnce();
  }

  @Test
  void clearanceLongWaitGroundDelaysAndDefers() throws Exception {
    when(clearance.acquire(eq(AIRPORT), any(), anyInt()))
        .thenReturn(ClearanceResult.denied(90_000));

    consumer.onMessage(message(0), channel);

    verify(groundDelay).delay(AIRPORT, Duration.ofSeconds(90));
    published(Q.runway(), null);
    verifyNoInteractions(pilot, pauser);
    assertAckedOnce();
  }

  @Test
  void unresolvablePayloadTypeIsRejectedAsFlightFault() throws Exception {
    Flight<Contact> f = flight("contact", 0);
    Flight<Contact> unknownType = new Flight<>(f.flightId(), AIRPORT, "contact", f.domainRef(),
        null, 0, 0, f.correlationId(), "com.example.NoSuchPayload", f.payload());

    consumer.onMessage(message(unknownType), channel);

    assertRecord(onlyRecord(), RecordType.RECORD, Outcome.INCIDENT, Disposition.TERMINAL,
        IncidentCategory.FLIGHT_FAULT, null);
    verifyNoInteractions(clearance, pilot, reporter);
    assertAckedOnce();
  }

  @Test
  void undeserializableMessageParksRawAndAcks() throws Exception {
    MessageProperties props = new MessageProperties();
    props.setDeliveryTag(9L);
    Message garbage = new Message("not-json".getBytes(), props);

    consumer.onMessage(garbage, channel);

    verify(rabbitTemplate).send("", Q.parked(), garbage);
    verify(channel).basicAck(9L, false);
    verifyNoMoreInteractions(channel);
  }

  // ---- settle-once invariants ----

  @Test
  void reportEmitFailureParksOnceWithoutDoubleSettle() throws Exception {
    reporterReturns(FlightReport.landed());
    doThrow(new ConfirmedPublisher.PublishException("broker did not confirm"))
        .when(publisher).publish(eq(QueueNames.tower()), any(), any());

    consumer.onMessage(message(0), channel);

    verify(rabbitTemplate).convertAndSend(eq(""), eq(Q.parked()), any(Flight.class));
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    assertAckedOnce();
  }

  @Test
  void deferralPublishFailureFallsBackToNackRequeue() throws Exception {
    reporterReturns(incident(IncidentCategory.AIRPORT_FAULT));
    doThrow(new ConfirmedPublisher.PublishException("nack"))
        .when(publisher).publish(eq(Q.runway()), any(), any());

    consumer.onMessage(message(0), channel);

    verify(channel).basicNack(TAG, false, true);
    verifyNoMoreInteractions(channel);
    verify(pauser).requestPause(AIRPORT);
  }

  @Test
  void correlationAndFlightIdsAreInMdcWhileLandingAndClearedAfter() throws Exception {
    Flight<Contact> f = flight("contact", 0);
    AtomicReference<String> correlation = new AtomicReference<>();
    AtomicReference<String> flightId = new AtomicReference<>();
    doAnswer(inv -> {
      correlation.set(MDC.get(FlightConsumer.MDC_CORRELATION_ID));
      flightId.set(MDC.get(FlightConsumer.MDC_FLIGHT_ID));
      return null;
    }).when(pilot).land(any());
    reporterReturns(FlightReport.landed());

    consumer.onMessage(message(f), channel);

    assertEquals("abc123", correlation.get());
    assertEquals(f.flightId().toString(), flightId.get());
    assertNull(MDC.get(FlightConsumer.MDC_CORRELATION_ID));
    assertNull(MDC.get(FlightConsumer.MDC_FLIGHT_ID));
  }
}
