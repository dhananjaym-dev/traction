package dev.traction.core.registry;

import dev.traction.core.api.AirportSpec;
import dev.traction.core.config.TractionProperties;
import dev.traction.core.consume.AirportPauser;
import dev.traction.core.status.ScopeRef;
import dev.traction.core.status.StatusReader;
import dev.traction.core.topology.Topology;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;

/**
 * Holds the registered airports and owns their container lifecycle. Registration validates the
 * spec, declares its queues, announces it on the tower queue and builds one container. Airports
 * are static: each id registers once. Deregistration stops the container and keeps the queues.
 *
 * <p>Containers are started and stopped through {@link SmartLifecycle}, so they start only once
 * the whole context has refreshed (a failed refresh never leaves a consumer running) and stop
 * first on shutdown, while the connection factory is still open and in-flight deliveries can be
 * settled. The phase is the latest possible for start and so the earliest for stop.
 *
 * <p>Validation rejects what would otherwise crash a consumer later: an incomplete spec, a
 * duplicate airport id, and any gate whose slot cost exceeds the airport's burst (clearance could
 * never grant it). The gates are copied at registration because {@link AirportSpec#gates()} is a
 * live view of a mutable builder.
 *
 * <p>Pause and resume ({@link AirportPauser}): the flight consumer pauses its own airport on
 * AIRPORT_FAULT or a non-consuming status, and the control plane's status events resume it on
 * reopen. A pause is asynchronous on a dedicated thread because a container cannot stop itself
 * from its own listener thread; {@link #shutdown()} (the bean's destroy method) ends that thread.
 */
@Slf4j
public class AirportRegistry implements SmartLifecycle, AirportPauser {

  private record Registered(Map<String, AirportSpec.Gate> gates,
      SimpleMessageListenerContainer container) { }

  private final Topology topology;
  private final ContainerFactory containerFactory;
  private final AirportDeclarations declarations;
  private final AirportListenerFactory listenerFactory; // null when no consumer module is present
  private final StatusReader statusReader;
  private final TractionProperties properties;
  private final String owningProcess;
  private final Map<String, Registered> airports = new ConcurrentHashMap<>();
  private final ExecutorService pauseExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "traction-pause");
    t.setDaemon(true);
    return t;
  });
  private volatile boolean running;

  public AirportRegistry(Topology topology, ContainerFactory containerFactory,
      AirportDeclarations declarations, AirportListenerFactory listenerFactory,
      StatusReader statusReader, TractionProperties properties, String owningProcess) {
    this.topology = topology;
    this.containerFactory = containerFactory;
    this.declarations = declarations;
    this.listenerFactory = listenerFactory;
    this.statusReader = statusReader;
    this.properties = properties;
    this.owningProcess = owningProcess;
  }

  /**
   * Validates every spec first, then registers them, so a bad spec or a duplicate id fails the
   * whole batch before any queue is declared or any airport announced.
   */
  public synchronized void registerAll(List<AirportSpec> specs) {
    Set<String> ids = new HashSet<>();
    for (AirportSpec spec : specs) {
      checkRegistrable(spec);
      if (!ids.add(spec.airportId())) {
        throw new IllegalArgumentException("duplicate airport id: " + spec.airportId());
      }
    }
    specs.forEach(this::register);
  }

  /** Registers one airport. Throws on an invalid spec or an id that is already registered. */
  public synchronized void register(AirportSpec spec) {
    Map<String, AirportSpec.Gate> gates = checkRegistrable(spec);
    String id = spec.airportId();
    if (airports.containsKey(id)) {
      throw new IllegalArgumentException("duplicate airport id: " + id);
    }
    if (listenerFactory == null) {
      throw new IllegalStateException("No AirportListenerFactory bean is available to consume airport '"
          + id + "'; add the module that provides it (the flight consumer) or remove the AirportSpec bean");
    }
    topology.declare(id);
    topology.declareTower();
    declarations.announce(AirportAnnouncement.from(spec, owningProcess));
    SimpleMessageListenerContainer container =
        containerFactory.create(spec, listenerFactory.forAirport(spec));
    airports.put(id, new Registered(gates, container));
    log.info("airport {} registered", id);
    if (running) {
      startContainer(id, container); // registered after the lifecycle already started
    }
  }

  /** Starts every container (unless {@code traction.enabled=false}); called once the context is ready. */
  @Override
  public synchronized void start() {
    running = true;
    airports.forEach((id, registered) -> startContainer(id, registered.container()));
  }

  /** Stops every container; one failing stop never prevents the rest from stopping. */
  @Override
  public synchronized void stop() {
    running = false;
    airports.forEach((id, registered) -> {
      try {
        registered.container().stop();
      } catch (RuntimeException e) {
        log.warn("airport {} container failed to stop cleanly", id, e);
      }
    });
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  private void startContainer(String id, SimpleMessageListenerContainer container) {
    if (properties.isEnabled()) {
      container.start();
      log.info("airport {} consuming", id);
    } else {
      log.info("airport {} dormant (traction.enabled=false)", id);
    }
  }

  /**
   * Stops the airport's container asynchronously. A no-op when the airport is unknown or already
   * stopped. Never throws: callers settle the delivery before asking for a pause.
   */
  @Override
  public void requestPause(String airportId) {
    Registered registered = airports.get(airportId);
    if (registered == null || !registered.container().isRunning()) {
      return;
    }
    try {
      pauseExecutor.submit(() -> {
        try {
          if (registered.container().isRunning()) {
            registered.container().stop();
            log.info("airport {} paused (container stopped)", airportId);
          }
        } catch (RuntimeException e) {
          log.error("airport {} pause failed", airportId, e);
        }
      });
    } catch (RejectedExecutionException shuttingDown) {
      log.warn("airport {} pause skipped: the registry is shutting down", airportId);
    }
  }

  /**
   * Restarts a paused airport's container. Only while the registry is running, consumption is
   * enabled, and the airport's status allows consumption (events can arrive stale or out of
   * order, so the stored status is re-checked). A no-op when unknown or already running.
   */
  public synchronized void resume(String airportId) {
    Registered registered = airports.get(airportId);
    if (registered == null || registered.container().isRunning()) {
      return;
    }
    if (running && properties.isEnabled()
        && statusReader.get(ScopeRef.airport(airportId)).allowsConsumption()) {
      registered.container().start();
      log.info("airport {} resumed", airportId);
    } else {
      log.info("airport {} resume skipped (registry stopped, disabled, or status not consuming)", airportId);
    }
  }

  /** Ends the pause thread (the bean's destroy method). */
  public void shutdown() {
    pauseExecutor.shutdownNow();
  }

  public synchronized void deregister(String airportId) {
    Registered removed = airports.remove(airportId);
    if (removed != null) {
      removed.container().stop();
      log.info("airport {} deregistered (queues retained)", airportId);
    }
  }

  public boolean isRegistered(String airportId) {
    return airports.containsKey(airportId);
  }

  public Set<String> airportIds() {
    return Set.copyOf(airports.keySet());
  }

  public boolean isRunning(String airportId) {
    Registered registered = airports.get(airportId);
    return registered != null && registered.container().isRunning();
  }

  /** The gates the airport had when it registered; empty when the airport is not registered. */
  public Optional<Map<String, AirportSpec.Gate>> gatesOf(String airportId) {
    return Optional.ofNullable(airports.get(airportId)).map(Registered::gates);
  }

  private static Map<String, AirportSpec.Gate> checkRegistrable(AirportSpec spec) {
    spec.validate();
    int burst = spec.slots().burst();
    Map<String, AirportSpec.Gate> gates = new LinkedHashMap<>(spec.gates());
    for (AirportSpec.Gate gate : gates.values()) {
      if (gate.slotCost() > burst) {
        throw new IllegalArgumentException("airport '" + spec.airportId() + "' gate '" + gate.gateId()
            + "' has slotCost " + gate.slotCost() + " above the slot burst " + burst
            + "; it could never be cleared");
      }
    }
    return Collections.unmodifiableMap(gates);
  }
}
