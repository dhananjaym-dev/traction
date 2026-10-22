package dev.traction.core.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Envelope around one unit of work bound for an airport. {@code gateId}, {@code domainRef} and
 * {@code charterRef} are nullable; {@code flightId} is minted per dispatch. {@code payloadType}
 * is the fully-qualified class name stamped by Tower at dispatch; the consumer converts the JSON
 * payload to it before invoking the pilot (only Tower publishes to tr.* queues).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Flight<T>(
    UUID flightId,
    String airportId,
    String gateId,
    String domainRef,
    String charterRef,
    int attemptCount,
    int holdCount,
    String correlationId,
    String payloadType,
    T payload) {

  public Flight<T> withIncrementedAttempt() {
    return new Flight<>(flightId, airportId, gateId, domainRef, charterRef,
        attemptCount + 1, holdCount, correlationId, payloadType, payload);
  }

  public Flight<T> withIncrementedHold() {
    return new Flight<>(flightId, airportId, gateId, domainRef, charterRef,
        attemptCount, holdCount + 1, correlationId, payloadType, payload);
  }
}
