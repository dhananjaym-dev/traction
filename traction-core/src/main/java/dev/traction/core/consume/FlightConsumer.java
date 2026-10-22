package dev.traction.core.consume;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dev.traction.core.api.AirportSpec;
import dev.traction.core.api.Evidence;
import dev.traction.core.api.Flight;
import dev.traction.core.api.FlightRecord.Disposition;
import dev.traction.core.api.FlightRecord.RecordType;
import dev.traction.core.api.FlightReport;
import dev.traction.core.api.IncidentCategory;
import dev.traction.core.api.Pilot;
import dev.traction.core.clearance.ClearanceResult;
import dev.traction.core.clearance.ClearanceService;
import dev.traction.core.consume.HoldingPatternService.HoldOutcome;
import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.report.FlightReportEmitter;
import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.status.ScopeRef;
import dev.traction.core.status.StatusReader;
import dev.traction.core.topology.QueueNames;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.util.ClassUtils;

/**
 * The per-flight loop for one airport (MANUAL ack, one listener thread):
 * airport status → ground delay → gate status → payload → clearance → land → report → settle.
 *
 * <p>Invariant: every delivery settles exactly once, by ack (after any confirmed republish /
 * hold / park) or by nack-requeue. Deliberate deferrals republish a fresh message to the runway
 * tail and ack, so the runway's delivery limit only counts genuine crashes. A step that throws
 * before the delivery settled lands in the catch-all: record INTERNAL, park, ack. Streaks and
 * ground stops are the control plane's job; this loop only reports, and pauses its own airport
 * on AIRPORT_FAULT so the runway is not burned during the round trip.
 *
 * <p>Clearance fails closed: if the shared bucket cannot be read (Redis down) the flight is
 * deferred, after a short backoff, and never flown unpaced. The settle matrix is
 * {@link SettleDecision}. Every log line carries the flight's correlation and flight ids in MDC.
 */
@Slf4j
public class FlightConsumer implements ChannelAwareMessageListener {

  public static final String MDC_CORRELATION_ID = "correlationId";
  public static final String MDC_FLIGHT_ID = "flightId";

  /** Backoff before a deliberate re-queue while the airport is stopped or clearance is down. */
  static final long DEFER_BACKOFF_MS = 500;
  /** A clearance wait above this escalates to a ground delay instead of sleeping the listener. */
  static final long MAX_CLEARANCE_SLEEP_MS = 10_000;
  /** Total clearance sleeping one delivery may do before escalating to a ground delay. */
  static final long MAX_CLEARANCE_WAIT_MS = 15_000;
  /** One ground-delay sleep slice; caps the latency of reacting to a container stop. */
  static final long MAX_DELAY_SLEEP_MS = 10 * 60 * 1000L;
  /** Total unacked ground-delay sleep, safely under the broker's 30 min consumer timeout. */
  static final long MAX_TOTAL_DELAY_MS = 25 * 60 * 1000L;

  private static final TypeReference<Flight<JsonNode>> WIRE_FLIGHT = new TypeReference<>() { };

  private final AirportSpec spec;
  private final String airportId;
  private final QueueNames names;
  private final Pilot<Object> pilot;
  private final StatusReader statusReader;
  private final FlightReportEmitter reportEmitter;
  private final RabbitTemplate rabbitTemplate;
  private final ConfirmedPublisher confirmedPublisher;
  private final AirportPauser pauser;
  private final ObjectMapper objectMapper;
  private final ClassLoader classLoader;
  private final ClearanceService clearance;
  private final GroundDelayService groundDelay;
  private final HoldingPatternService holding;

  /**
   * @param rabbitTemplate Traction's plain template, for the last-resort parks only
   * @param objectMapper the mapper behind Traction's message converter, so the wire format matches
   * @param classLoader resolves each flight's {@code payloadType}
   */
  @SuppressWarnings("unchecked")
  public FlightConsumer(AirportSpec spec, StatusReader statusReader, FlightReportEmitter reportEmitter,
      RabbitTemplate rabbitTemplate, ConfirmedPublisher confirmedPublisher, AirportPauser pauser,
      ObjectMapper objectMapper, ClassLoader classLoader, ClearanceService clearance,
      GroundDelayService groundDelay, HoldingPatternService holding) {
    this.spec = spec;
    this.airportId = spec.airportId();
    this.names = QueueNames.of(airportId);
    this.pilot = (Pilot<Object>) spec.pilot();
    this.statusReader = statusReader;
    this.reportEmitter = reportEmitter;
    this.rabbitTemplate = rabbitTemplate;
    this.confirmedPublisher = confirmedPublisher;
    this.pauser = pauser;
    this.objectMapper = objectMapper;
    this.classLoader = classLoader;
    this.clearance = clearance;
    this.groundDelay = groundDelay;
    this.holding = holding;
  }

  @Override
  public void onMessage(Message message, Channel channel) throws Exception {
    long tag = message.getMessageProperties().getDeliveryTag();
    Flight<JsonNode> flight;
    try {
      flight = objectMapper.readValue(message.getBody(), WIRE_FLIGHT);
    } catch (Exception e) {
      log.error("airport {}: undeserializable message; parking it raw", airportId, e);
      // Plain template: this is the raw Message, and no safer disposition exists if it fails.
      rabbitTemplate.send("", names.parked(), message);
      channel.basicAck(tag, false);
      return;
    }

    MDC.put(MDC_CORRELATION_ID, flight.correlationId());
    MDC.put(MDC_FLIGHT_ID, String.valueOf(flight.flightId()));
    // Per-delivery state lives on the stack. A one-element carrier, so a step that settles and
    // THEN throws still tells the catch-all not to settle again (a double settle closes the channel).
    boolean[] settled = {false};
    try {
      fly(flight, channel, tag, settled);
    } catch (Throwable frameworkError) {
      if (settled[0]) {
        log.error("airport {}: framework error after flight {} was settled; left as is",
            airportId, flight.flightId(), frameworkError);
      } else {
        parkAfterFrameworkError(flight, channel, tag, frameworkError);
      }
    } finally {
      MDC.remove(MDC_CORRELATION_ID);
      MDC.remove(MDC_FLIGHT_ID);
    }
  }

  private void fly(Flight<JsonNode> flight, Channel channel, long tag, boolean[] settled) throws Exception {
    // 1. Airport status: authoritative per flight (covers a lagging pause).
    if (!statusReader.get(ScopeRef.airport(airportId)).allowsConsumption()) {
      sleep(DEFER_BACKOFF_MS); // bounds the republish spin until the pause lands
      deferToRunwayTail(flight, channel, tag, settled);
      pauser.requestPause(airportId);
      return;
    }

    // 2. Ground delay: the sleeping listener thread is the resume timer.
    if (!sleepThroughGroundDelay(flight, channel, tag, settled)) {
      return;
    }

    // 3. Gate status: a grounded gate parks its flights while the rest of the airport flows.
    if (flight.gateId() != null
        && !statusReader.get(ScopeRef.gate(airportId, flight.gateId())).allowsConsumption()) {
      reportEmitter.emitRecord(flight,
          FlightReport.skipped(FlightReportMessage.REASON_GATE_GROUNDED), Disposition.PARKED);
      confirmedPublisher.publish(names.parked(), flight, null);
      ack(channel, tag, settled);
      return;
    }

    // 4. Payload: a payload that cannot become its stamped type is this flight's fault. Checked
    // before clearance so a rejected flight never burns a slot.
    Object payload;
    try {
      payload = toPayload(flight);
    } catch (Exception | LinkageError e) {
      Evidence cause = Evidence.from(e);
      FlightReport rejected = FlightReport.incident(IncidentCategory.FLIGHT_FAULT,
          new Evidence(cause.code(), "payload-unreadable", cause.excerpt()));
      logFailure(Disposition.TERMINAL, (FlightReport.Incident) rejected, flight, e);
      reportEmitter.emitRecord(flight, rejected, Disposition.TERMINAL);
      ack(channel, tag, settled);
      return;
    }

    // 5. Clearance: the shared slot bucket, before any work.
    if (!clear(flight, channel, tag, settled)) {
      return;
    }

    // 6. Land
    Flight<JsonNode> attempted = flight.withIncrementedAttempt();
    Flight<Object> typed = new Flight<>(attempted.flightId(), attempted.airportId(),
        attempted.gateId(), attempted.domainRef(), attempted.charterRef(), attempted.attemptCount(),
        attempted.holdCount(), attempted.correlationId(), attempted.payloadType(), payload);
    Throwable error = null;
    try {
      pilot.land(typed);
    } catch (Throwable t) {
      error = t;
    }

    // 7. Report (a broken reporter must not lose the flight), 8. settle
    settle(classify(typed, error), attempted, channel, tag, settled, error);
  }

  private FlightReport classify(Flight<Object> flight, Throwable error) {
    try {
      FlightReport report = spec.reporter().report(flight, error);
      if (report != null) {
        return report;
      }
      log.error("airport {}: reporter returned null; classifying INTERNAL", airportId);
      return FlightReport.incident(IncidentCategory.INTERNAL,
          new Evidence(null, "reporter-returned-null", null));
    } catch (Throwable reporterError) {
      log.error("airport {}: reporter threw; classifying INTERNAL", airportId, reporterError);
      return FlightReport.incident(IncidentCategory.INTERNAL, Evidence.from(reporterError));
    }
  }

  /** Carries out the {@link SettleDecision}. Sets {@code settled[0]} the moment the delivery settles. */
  private void settle(FlightReport report, Flight<JsonNode> flight, Channel channel, long tag,
      boolean[] settled, Throwable pilotError) throws Exception {
    SettleDecision decision = SettleDecision.of(report);
    switch (decision.route()) {
      case ACK -> {
        write(decision, report, flight, pilotError);
        ack(channel, tag, settled);
      }
      case PARK -> {
        write(decision, report, flight, pilotError);
        confirmedPublisher.publish(names.parked(), flight, null);
        ack(channel, tag, settled);
      }
      case HOLD_OR_PARK -> {
        FlightReport.Incident incident = (FlightReport.Incident) report;
        HoldOutcome outcome = holding.holdOrPark(flight, spec.holdPolicyFor(incident.category()));
        Flight<JsonNode> after = outcome == HoldOutcome.HELD ? flight.withIncrementedHold() : flight;
        write(SettleDecision.afterHolding(outcome, after.holdCount()), report, after, pilotError);
        ack(channel, tag, settled);
      }
      case DEFER_AND_PAUSE -> {
        // The control plane ground-stops the airport on this report; pausing now keeps this
        // consumer from burning the runway during the round trip. The flight re-flies on reopen.
        write(decision, report, flight, pilotError);
        deferToRunwayTail(flight, channel, tag, settled);
        pauser.requestPause(airportId);
      }
      case DELAY_AND_DEFER -> {
        // Not the flight's problem: pace the airport down. No pause; later deliveries sleep
        // through the delay at step 2.
        write(decision, report, flight, pilotError);
        groundDelay.delay(airportId, spec.slots().window());
        deferToRunwayTail(flight, channel, tag, settled);
      }
    }
  }

  /** Emits the decision's row (and logs the failure line for incidents). */
  private void write(SettleDecision decision, FlightReport report, Flight<JsonNode> flight,
      Throwable pilotError) {
    if (report instanceof FlightReport.Incident incident) {
      logFailure(decision.disposition(), incident, flight, pilotError);
      if (decision.recordType() == RecordType.INTERIM) {
        reportEmitter.emitInterim(flight, incident, decision.reason());
        return;
      }
    }
    reportEmitter.emitRecord(flight, report, decision.disposition());
  }

  /**
   * One non-blocking acquire per attempt; short waits sleep and retry, long waits (or too much
   * total sleeping) ground-delay the airport and defer. Redis failure defers after a backoff.
   * Returns true when cleared; otherwise the delivery has been settled.
   */
  private boolean clear(Flight<JsonNode> flight, Channel channel, long tag, boolean[] settled) throws Exception {
    int cost = slotCost(flight.gateId());
    long slept = 0L;
    while (true) {
      ClearanceResult result;
      try {
        result = clearance.acquire(airportId, spec.slots(), cost);
      } catch (RuntimeException unavailable) {
        log.warn("airport {}: clearance unavailable; deferring flight {} to the runway tail "
            + "(never flown unpaced)", airportId, flight.flightId(), unavailable);
        if (!sleep(DEFER_BACKOFF_MS)) {
          nackRequeue(channel, tag, settled);
          return false;
        }
        deferToRunwayTail(flight, channel, tag, settled);
        return false;
      }
      if (result.granted()) {
        return true;
      }
      long wait = Math.max(result.waitMillis(), 1L);
      if (wait > MAX_CLEARANCE_SLEEP_MS || slept >= MAX_CLEARANCE_WAIT_MS) {
        groundDelay.delay(airportId,
            wait > MAX_CLEARANCE_SLEEP_MS ? Duration.ofMillis(wait) : spec.slots().window());
        deferToRunwayTail(flight, channel, tag, settled); // no pause: deliveries sleep at step 2
        return false;
      }
      if (!sleep(wait)) {
        nackRequeue(channel, tag, settled); // container stopping: put it back and leave
        return false;
      }
      slept += wait;
    }
  }

  /**
   * Sleeps while the airport's ground-delay marker is live, in bounded slices. Past the total
   * cap the delivery is released to the runway tail (the marker persists, so it sleeps again
   * there). Returns false when the delivery has been settled.
   */
  private boolean sleepThroughGroundDelay(Flight<JsonNode> flight, Channel channel, long tag,
      boolean[] settled) throws Exception {
    long slept = 0L;
    Optional<Duration> remaining;
    while ((remaining = groundDelay.remaining(airportId)).isPresent()) {
      if (slept >= MAX_TOTAL_DELAY_MS) {
        deferToRunwayTail(flight, channel, tag, settled);
        return false;
      }
      long slice = delaySliceMs(remaining.get().toMillis(), slept);
      if (!sleep(slice)) {
        nackRequeue(channel, tag, settled);
        return false;
      }
      slept += slice;
    }
    return true;
  }

  /** Next ground-delay slice: bounded by the marker's remaining time, the slice cap and the unspent budget. */
  static long delaySliceMs(long remainMs, long sleptMs) {
    return Math.min(Math.min(Math.max(remainMs, 1L), MAX_DELAY_SLEEP_MS), MAX_TOTAL_DELAY_MS - sleptMs);
  }

  /**
   * Deliberate deferral: confirmed republish to the runway tail, then ack. A publish failure
   * falls back to nack-requeue (broker-owned redelivery: degraded accounting, never loss).
   */
  private void deferToRunwayTail(Flight<JsonNode> flight, Channel channel, long tag,
      boolean[] settled) throws Exception {
    try {
      confirmedPublisher.publish(names.runway(), flight, null);
      channel.basicAck(tag, false);
    } catch (Exception publishFailure) {
      log.warn("airport {}: deferral republish failed for flight {}; falling back to nack",
          airportId, flight.flightId(), publishFailure);
      channel.basicNack(tag, false, true);
    }
    settled[0] = true;
  }

  /** Never drop, never spin: best-effort INTERNAL record, then park and ack. */
  private void parkAfterFrameworkError(Flight<JsonNode> flight, Channel channel, long tag,
      Throwable frameworkError) throws Exception {
    log.error("flight-failure disposition=PARKED airport={} framework error; parking flight {}",
        airportId, flight.flightId(), frameworkError);
    try {
      reportEmitter.emitRecord(flight,
          FlightReport.incident(IncidentCategory.INTERNAL, Evidence.from(frameworkError)),
          Disposition.PARKED);
    } catch (Exception emitFailure) {
      log.warn("airport {}: park record emit failed for flight {}", airportId, flight.flightId(), emitFailure);
    }
    // Plain template: the final safety net, where a confirm failure would leave no safe option.
    rabbitTemplate.convertAndSend("", names.parked(), flight);
    channel.basicAck(tag, false);
  }

  private Object toPayload(Flight<JsonNode> flight) throws Exception {
    String type = flight.payloadType();
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("flight carries no payloadType");
    }
    Class<?> payloadClass = ClassUtils.forName(type, classLoader);
    JsonNode node = flight.payload();
    return node == null ? null : objectMapper.treeToValue(node, payloadClass);
  }

  /** The gate's slot cost; 1 for a flight whose gate is not (or no longer) declared. */
  private int slotCost(String gateId) {
    AirportSpec.Gate gate = gateId == null ? null : spec.gates().get(gateId);
    return gate == null ? 1 : gate.slotCost();
  }

  /**
   * The structured failure line: always WARN (a disposed flight is handled; ERROR is for
   * framework defects), never for LANDED/SKIPPED. Correlation and flight ids ride MDC.
   */
  private void logFailure(Disposition disposition, FlightReport.Incident incident, Flight<?> flight,
      Throwable error) {
    String evidence = incident.evidence() == null ? null : incident.evidence().display();
    log.warn("flight-failure disposition={} airport={} gate={} category={} attempt={} holds={} "
            + "domainRef={} charterRef={} evidence=\"{}\"",
        disposition, airportId, flight.gateId(), incident.category(), flight.attemptCount(),
        flight.holdCount(), flight.domainRef(), flight.charterRef(),
        evidence == null ? "" : evidence, error);
  }

  private static void ack(Channel channel, long tag, boolean[] settled) throws Exception {
    channel.basicAck(tag, false);
    settled[0] = true;
  }

  private static void nackRequeue(Channel channel, long tag, boolean[] settled) throws Exception {
    channel.basicNack(tag, false, true);
    settled[0] = true;
  }

  /** False when interrupted (container stopping); the interrupt flag is restored. */
  private static boolean sleep(long millis) {
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
