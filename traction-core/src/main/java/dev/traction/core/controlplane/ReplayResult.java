package dev.traction.core.controlplane;

/**
 * Outcome of a parked replay. {@code skipped} counts parked messages that could not be decoded
 * as flights and were left in the parked queue.
 */
public record ReplayResult(String airportId, int replayed, int skipped) { }
