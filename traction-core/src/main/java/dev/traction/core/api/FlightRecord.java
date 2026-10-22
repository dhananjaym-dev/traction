package dev.traction.core.api;

import java.util.UUID;

/**
 * One row of the flight log. INTERIM rows describe a non-final step (reasons such as
 * {@code HELD_1} or {@code DEFERRED}); RECORD rows are the settled outcome. Nullable: category,
 * disposition (LANDED rows), gateId, domainRef, charterRef, correlationId, reason.
 */
public record FlightRecord(
    RecordType recordType,
    Outcome outcome,
    Disposition disposition,
    IncidentCategory category,
    String reason,
    UUID flightId,
    String airportId,
    String gateId,
    String domainRef,
    String charterRef,
    String correlationId,
    long epochMillis) {

  public enum RecordType { RECORD, INTERIM }

  public enum Outcome { LANDED, SKIPPED, INCIDENT }

  public enum Disposition { RETRYING, PARKED, TERMINAL }
}
