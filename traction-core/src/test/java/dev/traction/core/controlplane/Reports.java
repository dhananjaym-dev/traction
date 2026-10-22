package dev.traction.core.controlplane;

import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.report.FlightReportMessage.RecordType;
import java.util.UUID;

/** Tower-report fixtures shaped the way {@code FlightReportEmitter} emits them. */
final class Reports {

  static final String AIRPORT = "crm:acme";
  static final long EMITTED_AT = 1_700_000_000_000L;

  private Reports() { }

  static FlightReportMessage incident(RecordType type, String category, String gateId,
      String domainRef, String disposition) {
    return new FlightReportMessage(type, UUID.randomUUID(), AIRPORT, gateId, domainRef, null,
        "INCIDENT", category, type == RecordType.INTERIM ? "DEFERRED" : null, "401", null,
        "token expired", 1, 0, "abc123", UUID.randomUUID(), EMITTED_AT, disposition);
  }

  /** As the consumer emits AIRPORT_FAULT: an INTERIM row, deferred to the runway tail. */
  static FlightReportMessage airportFault() {
    return incident(RecordType.INTERIM, "AIRPORT_FAULT", "contact-sync", "c-1", "RETRYING");
  }

  /** As the consumer emits GATE_FAULT / FLIGHT_FAULT / INTERNAL-after-budget: a settled RECORD. */
  static FlightReportMessage recordIncident(String category, String gateId, String domainRef) {
    return incident(RecordType.RECORD, category, gateId, domainRef,
        "FLIGHT_FAULT".equals(category) ? "TERMINAL" : "PARKED");
  }

  static FlightReportMessage landed(String gateId) {
    return new FlightReportMessage(RecordType.RECORD, UUID.randomUUID(), AIRPORT, gateId, "c-9",
        null, "LANDED", null, null, null, null, null, 1, 0, "abc123", UUID.randomUUID(),
        EMITTED_AT, "TERMINAL");
  }
}
