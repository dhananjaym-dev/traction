package dev.traction.core.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire form of one flight outcome on the tower queue: the single report path from every data
 * plane to the control plane. Flat strings rather than the sealed {@code FlightReport} types, so
 * the JSON is a language-neutral contract.
 *
 * <p>RECORD is the settled outcome (counted); INTERIM is a step along the way (logged, never
 * counted): {@code reason} is {@code HELD_<n>} for a holding round or {@code DEFERRED} for a
 * runway-tail deferral. On RECORD rows {@code reason} is the SKIPPED reason. {@code reportId} is
 * unique per emitted message (the read-side dedup key under at-least-once delivery);
 * {@code emittedAtEpochMillis} is stamped by the producer. Evidence fields are bounded by
 * {@link #withClampedEvidence()} at the emitter, and should be re-clamped at tower ingress for
 * foreign producers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightReportMessage(
    RecordType recordType,
    UUID flightId,
    String airportId,
    String gateId,
    String domainRef,
    String charterRef,
    String outcome,            // LANDED | SKIPPED | INCIDENT
    String category,           // nullable; IncidentCategory name
    String reason,             // nullable; SKIPPED reason, or INTERIM HELD_<n> / DEFERRED
    String evidenceCode,       // nullable
    String evidenceMessageKey, // nullable
    String evidenceExcerpt,    // nullable
    int attemptCount,
    int holdCount,
    String correlationId,
    UUID reportId,
    Long emittedAtEpochMillis,
    String disposition) {      // RETRYING | PARKED | TERMINAL

  /** Framework-reserved SKIPPED reason: the flight's gate was ground-stopped, so it parked. */
  public static final String REASON_GATE_GROUNDED = "GATE_GROUNDED";

  public static final int EVIDENCE_MAX_CODE_LENGTH = 64;
  public static final int EVIDENCE_MAX_MESSAGE_KEY_LENGTH = 128;
  public static final int EVIDENCE_MAX_EXCERPT_LENGTH = 1000;

  public enum RecordType { RECORD, INTERIM }

  /** Evidence truncated to the wire bounds; identity when already compliant. */
  public FlightReportMessage withClampedEvidence() {
    String code = truncate(evidenceCode, EVIDENCE_MAX_CODE_LENGTH);
    String key = truncate(evidenceMessageKey, EVIDENCE_MAX_MESSAGE_KEY_LENGTH);
    String excerpt = truncate(evidenceExcerpt, EVIDENCE_MAX_EXCERPT_LENGTH);
    if (Objects.equals(code, evidenceCode) && Objects.equals(key, evidenceMessageKey)
        && Objects.equals(excerpt, evidenceExcerpt)) {
      return this;
    }
    return new FlightReportMessage(recordType, flightId, airportId, gateId, domainRef, charterRef,
        outcome, category, reason, code, key, excerpt, attemptCount, holdCount, correlationId,
        reportId, emittedAtEpochMillis, disposition);
  }

  private static String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
