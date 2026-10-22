package dev.traction.core.registry;

import dev.traction.core.api.AirportSpec;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

/**
 * The seam between registration and consumption: builds the listener that drains one airport's
 * runway. The registry creates one listener container per airport around whatever this returns
 * (MANUAL ack, prefetch 1), so the listener owns acknowledgement. The flight consumer module
 * supplies the implementation bean; the registry refuses to register an airport without one.
 */
@FunctionalInterface
public interface AirportListenerFactory {

  ChannelAwareMessageListener forAirport(AirportSpec spec);
}
