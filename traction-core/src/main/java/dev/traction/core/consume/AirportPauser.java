package dev.traction.core.consume;

/**
 * Requests an asynchronous stop of an airport's listener container. Implemented by the
 * {@code AirportRegistry} (which also offers {@code resume}). Asynchronous because a container
 * must never be stopped from its own listener thread. Must not throw once the caller has settled
 * the delivery; the consumer tolerates it if it does.
 */
@FunctionalInterface
public interface AirportPauser {
  void requestPause(String airportId);
}
