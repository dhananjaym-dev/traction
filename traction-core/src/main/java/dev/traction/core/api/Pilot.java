package dev.traction.core.api;

/**
 * Domain handler: lands each flight (performs the external call). Knows nothing about queues,
 * pacing or status. Delivery is at-least-once, so {@code land} must be idempotent; key it on
 * {@link Flight#domainRef()}, since {@code flightId} is minted per dispatch. Throwing signals a
 * failure for the paired {@link FlightReporter} to classify.
 */
@FunctionalInterface
public interface Pilot<T> {
  void land(Flight<T> flight) throws Exception;
}
