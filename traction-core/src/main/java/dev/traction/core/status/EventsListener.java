package dev.traction.core.status;

import dev.traction.core.registry.AirportRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Every process's reaction to {@code tr:events}: a ground stop decided by the control plane
 * pauses this process's consumer for that airport, and a reopen resumes it. Runs in every
 * process that hosts airports, control plane or not.
 *
 * <ul>
 *   <li>non-consuming airport status (GROUND_STOP, CLOSED) &rarr; {@link AirportRegistry#requestPause}</li>
 *   <li>OPEN &rarr; {@link AirportRegistry#resume}, which re-reads the stored status, so a stale
 *       or out-of-order event can never restart a grounded airport</li>
 *   <li>gate scope &rarr; ignored: the consumer checks the gate status per flight</li>
 * </ul>
 * Both clauses are idempotent, so a repeated broadcast is harmless. Airports this process does
 * not host are ignored. Never throws: a bad payload is logged and dropped.
 */
@Slf4j
public class EventsListener {

  private final AirportRegistry registry;

  public EventsListener(AirportRegistry registry) {
    this.registry = registry;
  }

  public void onEvent(String payload) {
    try {
      Events.StatusChanged event = Events.parse(payload).orElse(null);
      if (event == null) {
        log.debug("tr:events payload ignored: {}", payload);
        return;
      }
      String airportId = event.scope().value();
      if (event.scope().gateId() != null || !registry.isRegistered(airportId)) {
        return;
      }
      if (event.status().allowsConsumption()) {
        registry.resume(airportId);
      } else {
        registry.requestPause(airportId);
      }
    } catch (RuntimeException e) {
      log.warn("tr:events payload not applied: {}", payload, e);
    }
  }
}
