package dev.traction.core.api;

import java.util.Map;

/** An operational event delivered to {@link NotifyHook}. Unused fields are null. */
public record NotifyEvent(
    Type type,
    String airportId,
    String gateId,
    String reason,
    Evidence evidence,
    Map<String, String> detail) {

  public enum Type {
    GROUND_STOP, REOPEN, GROUND_DELAY, FLIGHT_INCIDENT,
    CHARTER_OPENED, CHARTER_COMPLETED, CHARTER_ABORTED,
    PARKED_REPLAYED, PARKED_DISCARDED
  }
}
