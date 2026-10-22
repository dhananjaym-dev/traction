package dev.traction.demo.naive;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The plumbing a typical hand-rolled consumer ends up with: one work queue with a delivery
 * limit, and a dead-letter queue behind it. This is what "we added retries and a DLQ" looks
 * like; nothing here knows about Traction.
 */
@Configuration(proxyBeanMethods = false)
public class NaiveTopology {

    public static final String QUEUE = "naive.contact-sync";
    public static final String DLQ = "naive.contact-sync.dlq";
    static final String DLX = "naive.contact-sync.dlx";
    static final String LISTENER_FACTORY = "naiveListenerFactory";

    @Bean
    Declarables naiveContactSyncTopology() {
        Queue work = QueueBuilder.durable(QUEUE).quorum()
                .withArgument("x-delivery-limit", 3)            // the broker dead-letters on the 4th redelivery
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ)
                .build();
        Queue dead = QueueBuilder.durable(DLQ).quorum().build();
        DirectExchange dlx = new DirectExchange(DLX);
        Binding binding = BindingBuilder.bind(dead).to(dlx).with(DLQ);
        return new Declarables(work, dead, dlx, binding);
    }

    /** A typical tuned listener: a few threads, a prefetch, JSON in. Used by the naive listener only. */
    @Bean(LISTENER_FACTORY)
    SimpleRabbitListenerContainerFactory naiveListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConcurrentConsumers(4);
        factory.setPrefetchCount(10);
        factory.setMessageConverter(new Jackson2JsonMessageConverter());
        return factory;
    }
}
