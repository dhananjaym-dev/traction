package dev.traction.core.api;

/** Receives every {@link FlightRecord} as it is written. Implementations must not throw. */
@FunctionalInterface
public interface FlightEventHook {
  void onRecord(FlightRecord r);
}
