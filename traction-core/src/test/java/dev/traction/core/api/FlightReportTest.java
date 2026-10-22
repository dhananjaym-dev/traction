package dev.traction.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlightReportTest {

  @Test
  void incidentExposesCategoryAndEvidence() {
    Evidence evidence = new Evidence("422", "bad-shape", "missing email");
    FlightReport report = FlightReport.incident(IncidentCategory.FLIGHT_FAULT, evidence);
    FlightReport.Incident incident = assertInstanceOf(FlightReport.Incident.class, report);
    assertEquals(IncidentCategory.FLIGHT_FAULT, incident.category());
    assertEquals(evidence, incident.evidence());
  }

  @Test
  void incidentRequiresCategory() {
    assertThrows(NullPointerException.class, () -> FlightReport.incident(null, null));
  }

  @Test
  void skippedRejectsBlankReason() {
    assertThrows(IllegalArgumentException.class, () -> FlightReport.skipped(" "));
  }

  @Test
  void evidenceFromThrowableBoundsTheExcerpt() {
    Evidence e = Evidence.from(new IllegalStateException("x".repeat(10_000)));
    assertEquals("IllegalStateException", e.code());
    assertTrue(e.excerpt().length() <= 300);
  }
}
