package dev.traction.core.registry;

/**
 * Submits an airport's self-description to the control plane by publishing an
 * {@link AirportAnnouncement} to the tower queue. Idempotent; safe to repeat on every boot.
 */
public interface AirportDeclarations {

  /**
   * Announces one airport (the airport and its gates in one message). Throws on a publish or
   * confirm failure; boot treats that as fatal ("booted => announced").
   */
  void announce(AirportAnnouncement announcement);
}
