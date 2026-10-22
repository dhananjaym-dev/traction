package dev.traction.core.report;

import dev.traction.core.api.Evidence;
import dev.traction.core.api.Flight;
import dev.traction.core.api.FlightEventHook;
import dev.traction.core.api.FlightRecord;
import dev.traction.core.api.FlightRecord.Disposition;
import dev.traction.core.api.FlightRecord.Outcome;
import dev.traction.core.api.FlightReport;
import dev.traction.core.api.IncidentCategory;
import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.topology.QueueNames;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes each flight outcome: a {@link FlightReportMessage} to the tower queue (confirmed),
 * then the same row as a {@link FlightRecord} to every {@link FlightEventHook}.
 *
 * <p>A failed tower publish THROWS and no hook sees the row. The sole caller is the flight
 * consumer, whose catch-all treats a throw before the delivery is settled as a framework error
 * (park + ack, never a double settle). Hooks run only after the publish succeeded, so a hook
 * never sees a row the control plane did not; a throwing hook is logged and skipped.
 */
@Slf4j
public class FlightReportEmitter {

  private final ConfirmedPublisher confirmedPublisher;
  private final List<FlightEventHook> hooks;

  public FlightReportEmitter(ConfirmedPublisher confirmedPublisher, List<FlightEventHook> hooks) {
    this.confirmedPublisher = confirmedPublisher;
    this.hooks = List.copyOf(hooks);
  }

  /** A settled outcome. A SKIPPED report's reason rides along as the row's reason. */
  public void emitRecord(Flight<?> flight, FlightReport report, Disposition disposition) {
    String reason = report instanceof FlightReport.Skipped s ? s.reason() : null;
    emit(FlightRecord.RecordType.RECORD, flight, report, disposition, reason);
  }

  /** A step along the way: {@code HELD_<n>} (holding round) or {@code DEFERRED} (runway tail). */
  public void emitInterim(Flight<?> flight, FlightReport.Incident incident, String reason) {
    emit(FlightRecord.RecordType.INTERIM, flight, incident, Disposition.RETRYING, reason);
  }

  private void emit(FlightRecord.RecordType type, Flight<?> flight, FlightReport report,
      Disposition disposition, String reason) {
    Outcome outcome;
    IncidentCategory category = null;
    Evidence evidence = null;
    switch (report) {
      case FlightReport.Landed ignored -> outcome = Outcome.LANDED;
      case FlightReport.Skipped ignored -> outcome = Outcome.SKIPPED;
      case FlightReport.Incident i -> {
        outcome = Outcome.INCIDENT;
        category = i.category();
        evidence = i.evidence();
      }
    }
    long now = System.currentTimeMillis();
    FlightReportMessage message = new FlightReportMessage(
        FlightReportMessage.RecordType.valueOf(type.name()), flight.flightId(), flight.airportId(),
        flight.gateId(), flight.domainRef(), flight.charterRef(), outcome.name(),
        category == null ? null : category.name(), reason,
        evidence == null ? null : evidence.code(),
        evidence == null ? null : evidence.messageKey(),
        evidence == null ? null : evidence.excerpt(),
        flight.attemptCount(), flight.holdCount(), flight.correlationId(), UUID.randomUUID(), now,
        disposition.name()).withClampedEvidence();
    confirmedPublisher.publish(QueueNames.tower(), message, null);

    FlightRecord record = new FlightRecord(type, outcome, disposition, category, reason,
        flight.flightId(), flight.airportId(), flight.gateId(), flight.domainRef(),
        flight.charterRef(), flight.correlationId(), now);
    log.debug("flight record {}", record);
    for (FlightEventHook hook : hooks) {
      try {
        hook.onRecord(record);
      } catch (RuntimeException e) {
        log.warn("FlightEventHook {} threw; skipped for this record", hook.getClass().getName(), e);
      }
    }
  }
}
