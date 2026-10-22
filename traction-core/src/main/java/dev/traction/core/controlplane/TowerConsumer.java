package dev.traction.core.controlplane;

import dev.traction.core.api.AirportStatus;
import dev.traction.core.api.Evidence;
import dev.traction.core.api.FlightRecord.Disposition;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.consume.FlightConsumer;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.registry.AirportAnnouncement;
import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.report.FlightReportMessage.RecordType;
import dev.traction.core.status.ScopeRef;
import dev.traction.core.status.StatusReader;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * The control plane's sink for {@code tr.tower.queue}. Per flight report:
 * <ol>
 *   <li>re-clamp the evidence (foreign producers may exceed the bounds);</li>
 *   <li>feed the {@link GroundStopRules} (which ground-stop and notify on a state change);</li>
 *   <li>INCIDENT reports go to the {@link IncidentSummaryStore} (recent incidents);</li>
 *   <li>a FLIGHT_INCIDENT notification, under the rule below.</li>
 * </ol>
 * Each step is best effort: a failure is logged and the report is still acked, never requeued
 * into a loop. Streaks are ephemeral and self-heal; the summaries are a convenience view.
 *
 * <p><b>FLIGHT_INCIDENT rule.</b> One notification per flight the framework gave up on: a RECORD
 * row with outcome INCIDENT and disposition PARKED or TERMINAL. Never for INTERIM rows (holds and
 * deferrals are still in flight), never for AIRPORT_FAULT (always the airport's GROUND_STOP
 * instead), and never while the flight's airport or gate is not OPEN, checked after the rules
 * ran: the ground stop already notified, so the report that trips a streak and every incident
 * still draining from a grounded scope stay quiet. What remains is capped at 25 per airport and
 * disposition per hour ({@link IncidentSummaryStore#allowNotify}).
 */
@Slf4j
public class TowerConsumer {

  private final GroundStopRules rules;
  private final IncidentSummaryStore incidents;
  private final StatusReader status;
  private final NotifyDispatcher notify;
  private final AnnouncementProjector announcements;

  public TowerConsumer(GroundStopRules rules, IncidentSummaryStore incidents, StatusReader status,
      NotifyDispatcher notify, AnnouncementProjector announcements) {
    this.rules = rules;
    this.incidents = incidents;
    this.status = status;
    this.notify = notify;
    this.announcements = announcements;
  }

  public void onReport(FlightReportMessage message) {
    FlightReportMessage m = message.withClampedEvidence();
    if (m.correlationId() != null) {
      MDC.put(FlightConsumer.MDC_CORRELATION_ID, m.correlationId());
    }
    try {
      log.debug("tower report {}", m);
      try {
        rules.evaluate(m);
      } catch (RuntimeException e) {
        log.warn("ground-stop rules failed for flight {}; skipped (streaks self-heal)", m.flightId(), e);
      }
      if (!"INCIDENT".equals(m.outcome())) {
        return;
      }
      try {
        incidents.record(m);
      } catch (RuntimeException e) {
        log.warn("incident summary not recorded for flight {}", m.flightId(), e);
      }
      try {
        if (notifiable(m)) {
          notify.dispatch(flightIncident(m));
        }
      } catch (RuntimeException e) {
        log.warn("incident notification failed for flight {}", m.flightId(), e);
      }
    } finally {
      MDC.remove(FlightConsumer.MDC_CORRELATION_ID);
    }
  }

  /** Projection failures propagate, so the listener requeues: the projection is the fleet view. */
  public void onAnnouncement(AirportAnnouncement announcement) {
    announcements.project(announcement);
  }

  private boolean notifiable(FlightReportMessage m) {
    if (m.recordType() != RecordType.RECORD || "AIRPORT_FAULT".equals(m.category())) {
      return false;
    }
    if (!Disposition.PARKED.name().equals(m.disposition())
        && !Disposition.TERMINAL.name().equals(m.disposition())) {
      return false;
    }
    if (status.get(ScopeRef.airport(m.airportId())) != AirportStatus.OPEN) {
      return false;
    }
    if (m.gateId() != null && status.get(ScopeRef.gate(m.airportId(), m.gateId())) != AirportStatus.OPEN) {
      return false;
    }
    return incidents.allowNotify(m.airportId(), m.disposition());
  }

  private static NotifyEvent flightIncident(FlightReportMessage m) {
    Map<String, String> detail = new LinkedHashMap<>();
    detail.put("disposition", m.disposition());
    putIfPresent(detail, "domainRef", m.domainRef());
    putIfPresent(detail, "flightId", m.flightId() == null ? null : m.flightId().toString());
    putIfPresent(detail, "flight", m.correlationId());
    putIfPresent(detail, "charterRef", m.charterRef());
    Evidence evidence = m.evidenceCode() == null && m.evidenceMessageKey() == null
        && m.evidenceExcerpt() == null
        ? null : new Evidence(m.evidenceCode(), m.evidenceMessageKey(), m.evidenceExcerpt());
    return new NotifyEvent(NotifyEvent.Type.FLIGHT_INCIDENT, m.airportId(), m.gateId(),
        m.category(), evidence, detail);
  }

  private static void putIfPresent(Map<String, String> detail, String key, String value) {
    if (value != null) {
      detail.put(key, value);
    }
  }
}
