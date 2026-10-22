package dev.traction.core.controlplane;

/** Outcome of a parked discard: how many messages were dropped from the parked queue. */
public record DiscardResult(String airportId, int dropped) { }
