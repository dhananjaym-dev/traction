package dev.traction.core.api;

import java.util.Objects;

/** The closed outcome vocabulary the framework acts on, produced by a {@link FlightReporter}. */
public sealed interface FlightReport {

  record Landed() implements FlightReport { }

  record Skipped(String reason) implements FlightReport {
    public Skipped {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("Skipped requires a non-blank reason");
      }
    }
  }

  record Incident(IncidentCategory category, Evidence evidence) implements FlightReport {
    public Incident {
      Objects.requireNonNull(category, "category");
    }
  }

  static FlightReport landed() {
    return new Landed();
  }

  static FlightReport skipped(String reason) {
    return new Skipped(reason);
  }

  static FlightReport incident(IncidentCategory category, Evidence evidence) {
    return new Incident(category, evidence);
  }
}
