package dev.traction.core.api;

import dev.traction.core.registry.AirportRegistry;
import dev.traction.core.topology.QueueNames;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Producer facade: validates the destination, builds the {@link Flight} envelope and publishes it
 * to the airport's runway. Only airports registered in this process are known, and only after
 * startup registration has run. An unknown airport or gate fails immediately with an
 * {@link IllegalArgumentException} and nothing is queued.
 *
 * <p>Publishing uses Traction's own RabbitTemplate (private JSON converter), never the application's.
 * It is a plain best-effort send on the default exchange (routing key = queue name),
 * so it is safe to call from inside the caller's own transaction boundaries.
 */
@Slf4j
public class Tower {

  private final AirportRegistry registry;
  private final RabbitTemplate rabbitTemplate;

  public Tower(AirportRegistry registry, RabbitTemplate rabbitTemplate) {
    this.registry = registry;
    this.rabbitTemplate = rabbitTemplate;
  }

  public Flight<Object> dispatch(String airportId, String gateId, Object payload) {
    return dispatch(airportId, gateId, payload, DispatchOptions.none());
  }

  public Flight<Object> dispatch(String airportId, String gateId, Object payload, DispatchOptions options) {
    if (airportId == null) {
      throw new IllegalArgumentException("airportId is required");
    }
    Map<String, AirportSpec.Gate> gates = registry.gatesOf(airportId)
        .orElseThrow(() -> new IllegalArgumentException("unknown airport: " + airportId));
    if (gateId == null || !gates.containsKey(gateId)) {
      throw new IllegalArgumentException("unknown gate '" + gateId + "' on airport " + airportId);
    }
    if (payload == null) {
      throw new IllegalArgumentException("payload is required (its type is stamped on the flight)");
    }
    DispatchOptions opts = options == null ? DispatchOptions.none() : options;
    Flight<Object> flight = new Flight<>(UUID.randomUUID(), airportId, gateId, opts.domainRef(),
        opts.charterRef(), 0, 0, shortCorrelationId(), payload.getClass().getName(), payload);
    rabbitTemplate.convertAndSend("", QueueNames.runway(airportId), flight);
    log.debug("dispatched flight {} to {}/{}", flight.flightId(), airportId, gateId);
    return flight;
  }

  private static String shortCorrelationId() {
    return UUID.randomUUID().toString().substring(0, 6);
  }
}
