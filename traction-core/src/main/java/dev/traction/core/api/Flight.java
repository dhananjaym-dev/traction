package dev.traction.core.api;

import java.util.UUID;

/**
 * Envelope around one unit of work bound for an airport. {@code gateId}, {@code domainRef} and
 * {@code charterRef} are nullable; {@code flightId} is minted per dispatch.
 */
public record Flight<T>(
    UUID flightId,
    String airportId,
    String gateId,
    String domainRef,
    String charterRef,
    String correlationId,
    T payload) { }
