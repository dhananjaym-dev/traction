package dev.traction.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.api.AirportSpec;
import dev.traction.core.api.Tower;
import dev.traction.core.clearance.ClearanceService;
import dev.traction.core.consume.GroundDelayService;
import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.registry.AirportDeclarations;
import dev.traction.core.registry.AirportListenerFactory;
import dev.traction.core.registry.AirportRegistry;
import dev.traction.core.registry.AmqpAirportDeclarations;
import dev.traction.core.registry.ConsumerReconciler;
import dev.traction.core.registry.ContainerFactory;
import dev.traction.core.topology.Topology;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the Traction data plane. Registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>Wiring pattern for every module: one explicit {@code @Bean} method per bean here (no
 * component scanning, no stereotype annotations on the classes). Control-plane beans are added
 * the same way, grouped in a nested {@code @Configuration} gated by
 * {@code @ConditionalOnProperty("traction.control-plane.enabled")}.
 */
@AutoConfiguration(after = {JacksonAutoConfiguration.class, RabbitAutoConfiguration.class,
    RedisAutoConfiguration.class})
@EnableConfigurationProperties(TractionProperties.class)
public class TractionAutoConfiguration {

  @Bean
  public Topology tractionTopology(RabbitAdmin rabbitAdmin) {
    return new Topology(rabbitAdmin);
  }

  /**
   * A second connection factory over the same underlying client factory, with publisher confirms
   * on, so confirms stay a Traction-only concern. Not an autowire candidate, so the application's
   * own {@code ConnectionFactory} injection points are unaffected.
   */
  @Bean(destroyMethod = "destroy", defaultCandidate = false)
  public CachingConnectionFactory tractionConfirmedConnectionFactory(ConnectionFactory existing) {
    if (!(existing instanceof CachingConnectionFactory caching)) {
      throw new IllegalStateException("Traction's confirmed publisher requires a CachingConnectionFactory, but the "
          + "application's ConnectionFactory is " + existing.getClass().getName());
    }
    CachingConnectionFactory confirmed = new CachingConnectionFactory(caching.getRabbitConnectionFactory());
    confirmed.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
    return confirmed;
  }

  /**
   * Traction's own template for flight dispatch, with a private JSON converter. The converter is
   * deliberately not a bean: a {@code MessageConverter} bean would be adopted by the application's
   * own RabbitTemplate and listeners. Not an autowire candidate, so the application's
   * {@code RabbitTemplate} injection points are unaffected. The copy of the application's
   * ObjectMapper keeps its modules (so payload types serialize) without sharing mutable state.
   */
  @Bean(defaultCandidate = false)
  public RabbitTemplate tractionRabbitTemplate(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper.copy()));
    return template;
  }

  @Bean
  public ConfirmedPublisher tractionConfirmedPublisher(
      @Qualifier("tractionConfirmedConnectionFactory") CachingConnectionFactory confirmedConnectionFactory,
      @Qualifier("tractionRabbitTemplate") RabbitTemplate tractionTemplate) {
    RabbitTemplate template = new RabbitTemplate(confirmedConnectionFactory);
    template.setMessageConverter(tractionTemplate.getMessageConverter());
    return new ConfirmedPublisher(template);
  }

  @Bean
  public AirportDeclarations tractionAirportDeclarations(ConfirmedPublisher confirmedPublisher) {
    return new AmqpAirportDeclarations(confirmedPublisher);
  }

  @Bean
  public ContainerFactory tractionContainerFactory(ConnectionFactory connectionFactory) {
    return new ContainerFactory(connectionFactory);
  }

  /**
   * The {@link AirportListenerFactory} (provided by the flight consumer module) is optional here
   * so a producer-only application with no airports still starts; the registry fails with a clear
   * message as soon as an airport needs it.
   */
  @Bean
  public AirportRegistry tractionAirportRegistry(Topology topology, ContainerFactory containerFactory,
      AirportDeclarations declarations, ObjectProvider<AirportListenerFactory> listenerFactory,
      TractionProperties properties, Environment environment) {
    return new AirportRegistry(topology, containerFactory, declarations,
        listenerFactory.getIfAvailable(), properties,
        environment.getProperty("spring.application.name", "application"));
  }

  @Bean
  public ConsumerReconciler tractionConsumerReconciler(AirportRegistry registry,
      ObjectProvider<AirportSpec> specs) {
    return new ConsumerReconciler(registry, specs.orderedStream().toList());
  }

  @Bean
  public Tower tractionTower(AirportRegistry registry,
      @Qualifier("tractionRabbitTemplate") RabbitTemplate tractionTemplate) {
    return new Tower(registry, tractionTemplate);
  }

  @Bean
  public ClearanceService tractionClearanceService(StringRedisTemplate redis) {
    return new ClearanceService(redis);
  }

  @Bean
  public GroundDelayService tractionGroundDelayService(StringRedisTemplate redis) {
    return new GroundDelayService(redis);
  }
}
