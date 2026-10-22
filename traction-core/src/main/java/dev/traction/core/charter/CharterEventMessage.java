package dev.traction.core.charter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Wire form of a charter lifecycle event on the tower queue, beside {@code FlightReportMessage}
 * and {@code AirportAnnouncement}. The opening process has already written the charter (see
 * {@link CharterOpener}); OPENED tells the control plane, which notifies {@code CHARTER_OPENED}
 * exactly once per charter (redeliveries included) and completes a charter whose expected count
 * is 0. Every later transition (counts, completion, abort) happens on the control plane itself,
 * so it needs no wire form.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CharterEventMessage(
    UUID eventId,
    Type type,
    String charterRef,
    String airportId,
    String gateId,
    Long expected,
    long epochMillis) {

  public enum Type { OPENED }

  public static CharterEventMessage opened(String charterRef, String airportId, String gateId,
      long expected) {
    return new CharterEventMessage(UUID.randomUUID(), Type.OPENED, charterRef, airportId, gateId,
        expected, System.currentTimeMillis());
  }
}
