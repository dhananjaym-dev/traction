package dev.traction.core.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.charter.CharterBoard;
import dev.traction.core.charter.CharterQueryService;
import dev.traction.core.charter.CharterSweep;
import dev.traction.core.charter.DiscardTally;
import dev.traction.core.charter.ReplayTally;
import dev.traction.core.admin.AdminController;
import dev.traction.core.admin.DashboardController;
import dev.traction.core.config.TractionProperties;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.status.StatusReader;
import dev.traction.core.topology.QueueNames;
import dev.traction.core.topology.Topology;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The control plane: tower consumer, ground-stop rules, status writes, incident summaries and
 * the fleet projection, the status sweep, the parked replay / discard operations, and the
 * charter side (counting through the tower consumer, the charter sweep and charter queries; the
 * {@link CharterBoard} itself is in the main configuration because the data plane reads its
 * abort marker). Active only with {@code traction.control-plane.enabled=true}, in exactly
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
      AnnouncementProjector announcements, CharterBoard charterBoard) {
    return new TowerConsumer(rules, incidents, statusReader, notifyDispatcher, announcements,
        charterBoard);
  }

  @Bean
  public CharterQueryService tractionCharterQueryService(StringRedisTemplate redis,
      CharterBoard charterBoard) {
    return new CharterQueryService(redis, charterBoard);
  }

  @Bean
  public CharterSweep tractionCharterSweep(CharterBoard charterBoard, StatusReader statusReader,
      RabbitAdmin rabbitAdmin, TractionProperties properties) {
    return new CharterSweep(charterBoard, statusReader, rabbitAdmin,
        properties.getControlPlane().getCharterSweepInterval(),
        properties.getControlPlane().getCharterStaleAfter());
  }

  @Bean
  public ParkedOpLock tractionParkedOpLock(StringRedisTemplate redis) {
    return new ParkedOpLock(redis);
  }

  /**
   * Moves parked flights back to the runway through Traction's private template and mapper. The
   * {@link ReplayTally} (the charter board) is optional.
   */
  @Bean
  public ParkedReplayService tractionParkedReplayService(
      @Qualifier("tractionRabbitTemplate") RabbitTemplate tractionRabbitTemplate,
      ConfirmedPublisher confirmedPublisher,
      @Qualifier("tractionObjectMapper") ObjectMapper tractionObjectMapper, ParkedOpLock lock,
      NotifyDispatcher notifyDispatcher, ObjectProvider<ReplayTally> replayTally) {
    return new ParkedReplayService(tractionRabbitTemplate, confirmedPublisher, tractionObjectMapper, lock,
        notifyDispatcher, replayTally);
  }

  /** The {@link DiscardTally} (the charter board, when present) is optional. */
  @Bean
  public ParkedDiscardService tractionParkedDiscardService(
      @Qualifier("tractionRabbitTemplate") RabbitTemplate tractionRabbitTemplate,
      @Qualifier("tractionObjectMapper") ObjectMapper tractionObjectMapper, ParkedOpLock lock,
      NotifyDispatcher notifyDispatcher, ObjectProvider<DiscardTally> discardTally) {
    return new ParkedDiscardService(tractionRabbitTemplate, tractionObjectMapper, lock, notifyDispatcher,
        discardTally);
  }

  @Bean
  public QueueDepthProbe tractionQueueDepthProbe(RabbitAdmin rabbitAdmin) {
    return new QueueDepthProbe(rabbitAdmin);
  }

  /** The REST admin API under {@code /traction/api}; the fleet comes from the Redis projection. */
  @Bean
  public AdminController tractionAdminController(AnnouncementProjector announcements,
      StatusStore statusStore, TransitionService transitions, IncidentSummaryStore incidents,
      CharterQueryService charterQueries, CharterBoard charterBoard, ParkedReplayService replay,
      ParkedDiscardService discard, QueueDepthProbe depthProbe) {
    return new AdminController(announcements, statusStore, transitions, incidents, charterQueries,
        charterBoard, replay, discard, depthProbe);
  }

  /** Maps the admin controller's exceptions to HTTP statuses (scoped to that controller). */
  @Bean
  public AdminController.Errors tractionAdminErrors() {
    return new AdminController.Errors();
  }

  /** The read-only dashboard page at {@code GET /traction}. */
  @Bean
  public DashboardController tractionDashboardController() {
    return new DashboardController();
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
