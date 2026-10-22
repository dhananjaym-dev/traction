package dev.traction.core.controlplane;

import dev.traction.core.api.Evidence;
import dev.traction.core.api.StopReason;
import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.status.ScopeRef;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rules that ground-stop an airport or gate, evaluated per tower report. Dispositions (ack,
 * hold, park, defer, ground delay) are decided by the data plane, never here.
 * <ul>
 *   <li>AIRPORT_FAULT: ground-stop the airport on the first report, no streak.</li>
 *   <li>GATE_FAULT: 3 distinct records (domainRef) in 5 min on a gate &rarr; ground-stop the gate.</li>
 *   <li>FLIGHT_FAULT / INTERNAL: feed one shared STRUCTURAL_FAULT streak; 5 distinct records in
 *       5 min on a gate &rarr; ground-stop the gate ({@link StopReason#INCIDENT}). A broken mapping
 *       and one bad record look identical to the vendor, so the ambiguity is resolved here,
 *       statistically, rather than in the reporter.</li>
 *   <li>TRANSIENT / RATE_LIMIT: no rule. Recoverable, handled by the data plane, and kept out of
 *       the structural streak so an end-of-bulk burst of retries can never ground a gate.</li>
 *   <li>LANDED: clears the landing flight's gate streaks (partial faults keep flowing; a total
 *       fault re-accumulates within a few flights).</li>
 * </ul>
 * Thresholds are fixed constants on purpose: no per-airport tuning.
 */
public class GroundStopRules {

  static final int GATE_FAULT_THRESHOLD = 3;
  static final Duration GATE_FAULT_WINDOW = Duration.ofMinutes(5);
  static final int STRUCTURAL_FAULT_THRESHOLD = 5;
  static final Duration STRUCTURAL_FAULT_WINDOW = Duration.ofMinutes(5);

  private final StreakTracker streaks;
  private final TransitionService transitions;

  public GroundStopRules(StreakTracker streaks, TransitionService transitions) {
    this.streaks = streaks;
    this.transitions = transitions;
  }

  public void evaluate(FlightReportMessage m) {
    if ("LANDED".equals(m.outcome())) {
      if (m.gateId() != null) {
        streaks.clear(gateScope(m));
      }
      return;
    }
    if (!"INCIDENT".equals(m.outcome())) {
      return; // SKIPPED carries no rule
    }
    switch (m.category() == null ? "" : m.category()) {
      case "AIRPORT_FAULT" -> {
        Evidence evidence = evidence(m);
        String display = evidence == null ? null : evidence.display();
        transitions.groundStop(ScopeRef.airport(m.airportId()), StopReason.AIRPORT_FAULT,
            (display == null ? "airport fault" : display) + flightTag(m), evidence,
            detail(m, null), reportedAt(m));
      }
      case "GATE_FAULT" -> streak(m, "GATE_FAULT", GATE_FAULT_THRESHOLD, GATE_FAULT_WINDOW,
          StopReason.GATE_FAULT);
      case "FLIGHT_FAULT", "INTERNAL" -> streak(m, "STRUCTURAL_FAULT", STRUCTURAL_FAULT_THRESHOLD,
          STRUCTURAL_FAULT_WINDOW, StopReason.INCIDENT);
      default -> { } // TRANSIENT, RATE_LIMIT: data plane only
    }
  }

  private void streak(FlightReportMessage m, String streak, int threshold, Duration window,
      StopReason reason) {
    if (m.gateId() == null) {
      return; // nothing to ground below the airport
    }
    String member = m.domainRef() != null ? m.domainRef() : String.valueOf(m.flightId());
    long distinct = streaks.increment(gateScope(m), streak, member, window);
    if (distinct < threshold) {
      return;
    }
    Map<String, String> detail = detail(m, distinct);
    detail.put("window", window.toMinutes() + "m");
    // The real category (FLIGHT_FAULT / INTERNAL), not the streak name, for root-causing.
    transitions.groundStop(ScopeRef.gate(m.airportId(), m.gateId()), reason,
        distinct + " distinct " + m.category() + " failures in " + window.toMinutes() + "m"
            + flightTag(m), evidence(m), detail, reportedAt(m));
  }

  private static String gateScope(FlightReportMessage m) {
    return ScopeRef.gate(m.airportId(), m.gateId()).value();
  }

  private static Evidence evidence(FlightReportMessage m) {
    if (m.evidenceCode() == null && m.evidenceMessageKey() == null && m.evidenceExcerpt() == null) {
      return null;
    }
    return new Evidence(m.evidenceCode(), m.evidenceMessageKey(), m.evidenceExcerpt());
  }

  /** Category, streak size and the tripping flight's correlation id (to pivot into its logs). */
  private static Map<String, String> detail(FlightReportMessage m, Long streak) {
    Map<String, String> detail = new LinkedHashMap<>();
    detail.put("category", m.category());
    if (streak != null) {
      detail.put("streak", String.valueOf(streak));
    }
    if (m.correlationId() != null) {
      detail.put("flight", m.correlationId());
    }
    return detail;
  }

  private static long reportedAt(FlightReportMessage m) {
    return m.emittedAtEpochMillis() != null ? m.emittedAtEpochMillis() : System.currentTimeMillis();
  }

  private static String flightTag(FlightReportMessage m) {
    return m.correlationId() == null ? "" : " flight=" + m.correlationId();
  }
}
