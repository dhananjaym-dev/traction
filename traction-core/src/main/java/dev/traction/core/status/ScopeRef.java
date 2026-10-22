package dev.traction.core.status;

import dev.traction.core.api.AirportSpec;

/**
 * Identifies a status scope: an airport ({@code airportId}) or a gate under it
 * ({@code airportId/gateId}). Ids never contain {@code /}, so the compound form is unambiguous.
 */
public record ScopeRef(String value) {

  public static ScopeRef airport(String airportId) {
    return new ScopeRef(airportId);
  }

  public static ScopeRef gate(String airportId, String gateId) {
    // The same pattern as AirportSpec ids (critically: no '/'), so the compound separator can
    // never appear inside a gate id.
    if (gateId == null || !AirportSpec.ID_PATTERN.matcher(gateId).matches()) {
      throw new IllegalArgumentException(
          "gateId must match " + AirportSpec.ID_PATTERN.pattern() + " but was: " + gateId);
    }
    return new ScopeRef(airportId + "/" + gateId);
  }

  /** An already-composed value read back from the store (airport or gate). Never user input. */
  public static ScopeRef raw(String value) {
    return new ScopeRef(value);
  }
}
