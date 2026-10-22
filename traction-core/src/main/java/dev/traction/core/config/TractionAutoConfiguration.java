package dev.traction.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.api.AirportSpec;
import dev.traction.core.api.CharterDesk;
import dev.traction.core.api.FlightEventHook;
import dev.traction.core.api.NotifyHook;
import dev.traction.core.api.Tower;
import dev.traction.core.charter.CharterBoard;
import dev.traction.core.charter.CharterEventPublisher;
import dev.traction.core.charter.CharterOpener;
import dev.traction.core.clearance.ClearanceService;
import dev.traction.core.consume.FlightConsumer;
import dev.traction.core.consume.GroundDelayService;
import dev.traction.core.consume.HoldingPatternService;
import dev.traction.core.controlplane.ControlPlaneConfig;
import dev.traction.core.hooks.LogNotifyHook;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.registry.AirportDeclarations;
import dev.traction.core.registry.AirportListenerFactory;
import dev.traction.core.registry.AirportRegistry;
import dev.traction.core.registry.AmqpAirportDeclarations;
import dev.traction.core.registry.ConsumerReconciler;
import dev.traction.core.registry.ContainerFactory;
import dev.traction.core.report.FlightReportEmitter;
import dev.traction.core.status.Events;
import dev.traction.core.status.EventsListener;
import dev.traction.core.status.StatusReader;
import dev.traction.core.topology.Topology;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Wires the Traction data plane. Registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>Wiring pattern for every module: one explicit {@code @Bean} method per bean here (no
 * component scanning, no stereotype annotations on the classes). Control-plane beans are added
 * the same way, grouped in {@link ControlPlaneConfig} (imported here) gated by
 * {@code traction.control-plane.enabled=true}.
 */
@AutoConfiguration(after = {JacksonAutoConfiguration.class, RabbitAutoConfiguration.class,
    RedisAutoConfiguration.class})
@EnableConfigurationProperties(TractionProperties.class)
@Import(ControlPlaneConfig.class)
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
   * The wire-format mapper: a copy of the application's ObjectMapper (keeps its modules, so
   * payload types serialize) that Traction owns. The template's converter wraps it, and the
   * flight consumer reads and converts payloads with it, so both ends share one format. Not an
   * autowire candidate, so the application's {@code ObjectMapper} injection points are unaffected.
   */
  @Bean(defaultCandidate = false)
  public ObjectMapper tractionObjectMapper(ObjectMapper objectMapper) {
    return objectMapper.copy();
  }

  /**
   * Traction's own template for flight dispatch, with a private JSON converter. The converter is
   * deliberately not a bean: a {@code MessageConverter} bean would be adopted by the application's
   * own RabbitTemplate and listeners. Not an autowire candidate, so the application's
   * {@code RabbitTemplate} injection points are unaffected.
   */
  @Bean(defaultCandidate = false)
  public RabbitTemplate tractionRabbitTemplate(ConnectionFactory connectionFactory,
      @Qualifier("tractionObjectMapper") ObjectMapper tractionObjectMapper) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(new Jackson2JsonMessageConverter(tractionObjectMapper));
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
  @Bean(destroyMethod = "shutdown")
  public AirportRegistry tractionAirportRegistry(Topology topology, ContainerFactory containerFactory,
      AirportDeclarations declarations, ObjectProvider<AirportListenerFactory> listenerFactory,
      StatusReader statusReader, TractionProperties properties, Environment environment) {
    return new AirportRegistry(topology, containerFactory, declarations,
        listenerFactory.getIfAvailable(), statusReader, properties,
        environment.getProperty("spring.application.name", "application"));
  }

  /**
   * One {@link FlightConsumer} per airport. The registry is the consumer's pauser but also builds
   * the consumers through this factory, so it is looked up lazily at pause time (no bean cycle).
   * Payload types resolve with the application's class loader.
   */
  @Bean
  public AirportListenerFactory tractionAirportListenerFactory(StatusReader statusReader,
      FlightReportEmitter reportEmitter,
      @Qualifier("tractionRabbitTemplate") RabbitTemplate tractionTemplate,
      ConfirmedPublisher confirmedPublisher, ObjectProvider<AirportRegistry> registry,
      @Qualifier("tractionObjectMapper") ObjectMapper tractionObjectMapper,
      ResourceLoader resourceLoader, ClearanceService clearance, GroundDelayService groundDelay,
      HoldingPatternService holding, CharterBoard charterBoard) {
    ClassLoader classLoader = resourceLoader.getClassLoader();
    return spec -> new FlightConsumer(spec, statusReader, reportEmitter, tractionTemplate,
        confirmedPublisher, airportId -> registry.getObject().requestPause(airportId),
        tractionObjectMapper, classLoader, clearance, groundDelay, holding, charterBoard);
  }

  @Bean
  public StatusReader tractionStatusReader(StringRedisTemplate redis) {
    return new StatusReader(redis);
  }

  @Bean
  public FlightReportEmitter tractionFlightReportEmitter(ConfirmedPublisher confirmedPublisher,
      ObjectProvider<FlightEventHook> hooks) {
    return new FlightReportEmitter(confirmedPublisher, hooks.orderedStream().toList());
  }

  @Bean
  public HoldingPatternService tractionHoldingPatternService(ConfirmedPublisher confirmedPublisher) {
    return new HoldingPatternService(confirmedPublisher);
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

  /**
   * The charter projection. Here, not in the control plane, because the data plane reads its
   * abort marker per flight; its counting, completion and abort are driven only by control-plane
   * beans (tower consumer, replay and discard through the tally seams, sweep, admin abort).
   */
  @Bean
  public CharterBoard tractionCharterBoard(StringRedisTemplate redis, NotifyDispatcher notifyDispatcher) {
    return new CharterBoard(redis, notifyDispatcher);
  }

  @Bean
  public CharterEventPublisher tractionCharterEventPublisher(ConfirmedPublisher confirmedPublisher) {
    return new CharterEventPublisher(confirmedPublisher);
  }

  @Bean
  public CharterOpener tractionCharterOpener(AirportRegistry registry, StringRedisTemplate redis,
      CharterEventPublisher charterEvents) {
    return new CharterOpener(registry, redis, charterEvents);
  }

  /** Opens charters on the airports registered in this process (any process, control plane or not). */
  @Bean
  public CharterDesk tractionCharterDesk(CharterOpener opener) {
    return new CharterDesk(opener);
  }

  /**
   * The default notify hook, registered beside any hooks the application adds (every hook gets
   * every event). Opt out with {@code traction.notify.log.enabled=false}.
   */
  @Bean
  @ConditionalOnProperty(prefix = "traction.notify.log", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  public LogNotifyHook tractionLogNotifyHook() {
    return new LogNotifyHook();
  }

  @Bean
  public NotifyDispatcher tractionNotifyDispatcher(ObjectProvider<NotifyHook> hooks) {
    return new NotifyDispatcher(hooks.orderedStream().toList());
  }

  @Bean
  public EventsListener tractionEventsListener(AirportRegistry registry) {
    return new EventsListener(registry);
  }

  /**
   * Subscribes this process to {@code tr:events}, so a ground stop decided by any control plane
   * pauses this process's consumers and a reopen resumes them. Fail-fast like registration: an
   * unreachable Redis at boot fails startup; a later connection loss is retried by the container.
   * Not an autowire candidate.
   */
  @Bean(defaultCandidate = false)
  public RedisMessageListenerContainer tractionEventsContainer(RedisConnectionFactory connectionFactory,
      EventsListener eventsListener) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(
        (message, pattern) -> eventsListener.onEvent(new String(message.getBody(), StandardCharsets.UTF_8)),
        new ChannelTopic(Events.CHANNEL));
    return container;
  }
}
