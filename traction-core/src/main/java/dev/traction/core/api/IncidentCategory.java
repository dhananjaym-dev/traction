package dev.traction.core.api;

/**
 * The level a failure breaks: airport, gate or flight. A reporter classifies to the level it
 * breaks; if unsure between airport and gate, choose the narrower one (streaks converge upward).
 */
public enum IncidentCategory {
  /** Whole-connection failure (dead credentials, suspended account): ground-stop the airport. */
  AIRPORT_FAULT,
  /** One gate's requests are rejected (contract or scope problem): park, and stop the gate on a streak. */
  GATE_FAULT,
  /** This flight's data was rejected: record and ack, no retry as-is. */
  FLIGHT_FAULT,
  /** Retryable blip (timeout, 5xx): hold per {@link HoldPolicy}, then park. */
  TRANSIENT,
  /** The vendor throttled: pace the airport down and defer, do not fail. */
  RATE_LIMIT,
  /** Framework or pilot-side fault, not the vendor's: hold briefly, then park. */
  INTERNAL
}
