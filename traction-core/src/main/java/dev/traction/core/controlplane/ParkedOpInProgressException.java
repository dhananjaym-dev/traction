package dev.traction.core.controlplane;

/**
 * Another replay or discard already holds the airport's parked-op lock. The message contains
 * {@code already-running}; the admin API maps this to HTTP 409.
 */
public class ParkedOpInProgressException extends IllegalStateException {

  public ParkedOpInProgressException(String airportId) {
    super("parked-op already-running for airport " + airportId);
  }
}
