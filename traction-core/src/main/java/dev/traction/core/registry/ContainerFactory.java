package dev.traction.core.registry;

import dev.traction.core.api.AirportSpec;
import dev.traction.core.topology.QueueNames;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

/**
 * Builds one listener container per airport: MANUAL ack, prefetch 1, one consumer thread, runway
 * queue only. A single consumer per airport keeps pacing against the shared slot bucket and
 * ground delay coherent. Extracted so the registry is testable without a broker.
 */
public class ContainerFactory {

  private final ConnectionFactory connectionFactory;

  public ContainerFactory(ConnectionFactory connectionFactory) {
    this.connectionFactory = connectionFactory;
  }

  public SimpleMessageListenerContainer create(AirportSpec spec, ChannelAwareMessageListener listener) {
    SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
    container.setQueueNames(QueueNames.runway(spec.airportId()));
    container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    container.setPrefetchCount(1);
    container.setConcurrentConsumers(1);
    container.setMessageListener(listener);
    container.setAutoStartup(false); // the registry decides when to start
    container.setBeanName("tr-container-" + spec.airportId());
    return container;
  }
}
