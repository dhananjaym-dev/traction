package dev.traction.core.api;

public enum AirportStatus {
  OPEN, GROUND_STOP, CLOSED;

  /** Whether flights may be consumed at this status. */
  public boolean allowsConsumption() {
    return this == OPEN;
  }
}
