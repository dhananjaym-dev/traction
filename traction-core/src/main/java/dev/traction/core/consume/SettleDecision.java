package dev.traction.core.consume;

import dev.traction.core.api.FlightRecord.Disposition;
import dev.traction.core.api.FlightRecord.RecordType;
import dev.traction.core.api.FlightReport;
import dev.traction.core.consume.HoldingPatternService.HoldOutcome;

/**
 * The disposition matrix: how a classified flight settles, and the row it writes. Pure (no I/O);
 * the {@link FlightConsumer} carries it out.
 *
 * <pre>
 *   LANDED / SKIPPED     ACK             RECORD  TERMINAL
 *   FLIGHT_FAULT         ACK             RECORD  TERMINAL   (rejected, no retry)
 *   GATE_FAULT           PARK            RECORD  PARKED
 *   AIRPORT_FAULT        DEFER_AND_PAUSE INTERIM RETRYING DEFERRED
 *   RATE_LIMIT           DELAY_AND_DEFER INTERIM RETRYING DEFERRED
 *   TRANSIENT, INTERNAL  HOLD_OR_PARK    holding pattern decides:
 *                          held   -> INTERIM RETRYING HELD_&lt;n&gt;
 *                          parked -> RECORD  PARKED
 * </pre>
 *
 * For {@code HOLD_OR_PARK} the row is unknown until the hold budget is checked, so
 * {@link #afterHolding} supplies it; the other fields are null until then.
 */
public record SettleDecision(Route route, RecordType recordType, Disposition disposition, String reason) {

  public static final String REASON_DEFERRED = "DEFERRED";
  public static final String REASON_HELD_PREFIX = "HELD_";

  public enum Route {
    /** Record, then ack. */
    ACK,
    /** Record, confirmed publish to parked, ack. */
    PARK,
    /** Holding pattern: a holding round with backoff, or park once the hold budget is spent. */
    HOLD_OR_PARK,
    /** Interim, republish to the runway tail + ack, then pause the airport's consumer. */
    DEFER_AND_PAUSE,
    /** Interim, ground-delay the airport for one slots window, republish to the runway tail + ack. */
    DELAY_AND_DEFER
  }

  public static SettleDecision of(FlightReport report) {
    return switch (report) {
      case FlightReport.Landed ignored -> finalRecord();
      case FlightReport.Skipped ignored -> finalRecord();
      case FlightReport.Incident incident -> switch (incident.category()) {
        case FLIGHT_FAULT -> finalRecord();
        case GATE_FAULT -> parked();
        case AIRPORT_FAULT -> deferred(Route.DEFER_AND_PAUSE);
        case RATE_LIMIT -> deferred(Route.DELAY_AND_DEFER);
        case TRANSIENT, INTERNAL -> new SettleDecision(Route.HOLD_OR_PARK, null, null, null);
      };
    };
  }

  /** The row for a holding-pattern outcome; {@code heldCount} is the flight's hold count after the hold. */
  public static SettleDecision afterHolding(HoldOutcome outcome, int heldCount) {
    return outcome == HoldOutcome.PARKED
        ? parked()
        : new SettleDecision(Route.HOLD_OR_PARK, RecordType.INTERIM, Disposition.RETRYING,
            REASON_HELD_PREFIX + heldCount);
  }

  private static SettleDecision finalRecord() {
    return new SettleDecision(Route.ACK, RecordType.RECORD, Disposition.TERMINAL, null);
  }

  private static SettleDecision parked() {
    return new SettleDecision(Route.PARK, RecordType.RECORD, Disposition.PARKED, null);
  }

  private static SettleDecision deferred(Route route) {
    return new SettleDecision(route, RecordType.INTERIM, Disposition.RETRYING, REASON_DEFERRED);
  }
}
