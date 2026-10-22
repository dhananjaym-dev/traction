package dev.traction.core.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.config.TractionProperties;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.status.StatusReader;
import dev.traction.core.topology.QueueNames;
import dev.traction.core.topology.Topology;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The control plane: tower consumer, ground-stop rules, status writes, incident summaries and
 * the fleet projection and the status sweep. Active only with {@code traction.control-plane.enabled=true}, in exactly
 * the processes that should decide ground stops (one or several: transitions are atomic and
 * notify once). Imported by {@code TractionAutoConfiguration}; one explicit {@code @Bean} per
 * bean, like the rest of the wiring. The data plane's pause/resume fan-out
 * ({@code EventsListener}) and the notify hooks live in the main configuration, because every
 * process needs them.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "traction.control-plane", name = "enabled", havingValue = "true")
public class ControlPlaneConfig {

  /** One tower consumer: the rules see reports in order, and a retry stall is the backpressure. */
  static final int TOWER_CONCURRENCY = 1;
  static final int TOWER_PREFETCH = 10;

  @Bean
  public StatusStore tractionStatusStore(StringRedisTemplate redis) {
    return new StatusStore(redis);
  }

  @Bean
  public StreakTracker tractionStreakTracker(StringRedisTemplate redis) {
    return new StreakTracker(redis);
  }

  @Bean
  public TransitionService tractionTransitionService(StatusStore statusStore, StreakTracker streaks,
      NotifyDispatcher notifyDispatcher) {
    return new TransitionService(statusStore, streaks, notifyDispatcher);
  }

  @Bean
  public GroundStopRules tractionGroundStopRules(StreakTracker streaks, TransitionService transitions) {
    return new GroundStopRules(streaks, transitions);
  }

  @Bean
  public IncidentSummaryStore tractionIncidentSummaryStore(StringRedisTemplate redis,
      @Qualifier("tractionObjectMapper") ObjectMapper tractionObjectMapper) {
    return new IncidentSummaryStore(redis, tractionObjectMapper);
  }

  @Bean
  public AnnouncementProjector tractionAnnouncementProjector(StringRedisTemplate redis,
      @Qualifier("tractionObjectMapper") ObjectMapper tractionObjectMapper) {
    return new AnnouncementProjector(redis, tractionObjectMapper);
  }

  @Bean
  public StatusSweep tractionStatusSweep(StatusStore statusStore, AnnouncementProjector fleet,
      TractionProperties properties) {
    return new StatusSweep(statusStore, fleet, properties.getControlPlane().getStatusSweepInterval());
  }

  @Bean
  public TowerConsumer tractionTowerConsumer(GroundStopRules rules, IncidentSummaryStore incidents,
      StatusReader statusReader, NotifyDispatcher notifyDispatcher,
      AnnouncementProjector announcements) {
    return new TowerConsumer(rules, incidents, statusReader, notifyDispatcher, announcements);
  }

  /**
   * The tower queue's listener container (AUTO ack: return acks, throw requeues). The tower
   * queue is declared here first, so this process can consume even before any airport has
   * registered. Reads with the same mapper every Traction publisher writes with. Not an autowire
   * candidate, so the application's own container injection points are unaffected.
   */
  @Bean(defaultCandidate = false)
  public SimpleMessageListenerContainer tractionTowerContainer(ConnectionFactory connectionFactory,
      Topology topology, TowerConsumer towerConsumer,
      @Qualifier("tractionObjectMapper") ObjectMapper tractionObjectMapper) {
    topology.declareTower();
    SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
    container.setQueueNames(QueueNames.tower());
    container.setConcurrentConsumers(TOWER_CONCURRENCY);
    container.setPrefetchCount(TOWER_PREFETCH);
    container.setAcknowledgeMode(AcknowledgeMode.AUTO);
    container.setMessageListener(new TowerMessageListener(
        TowerMessageListener.converter(tractionObjectMapper), towerConsumer));
    return container;
  }
}
