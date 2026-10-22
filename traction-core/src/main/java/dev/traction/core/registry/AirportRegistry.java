package dev.traction.core.registry;

import dev.traction.core.api.AirportSpec;
import dev.traction.core.config.TractionProperties;
import dev.traction.core.topology.Topology;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

/**
 * Holds the registered airports and owns their container lifecycle. Registration validates the
 * spec, declares its queues, announces it on the tower queue and starts one container. Airports
 * are static: each id registers once. Deregistration stops the container and keeps the queues.
 *
 * <p>Validation rejects what would otherwise crash a consumer later: an incomplete spec, a
 * duplicate airport id, and any gate whose slot cost exceeds the airport's burst (clearance could
 * never grant it). The gates are copied at registration because {@link AirportSpec#gates()} is a
 * live view of a mutable builder.
 */
@Slf4j
public class AirportRegistry {

  private record Registered(Map<String, AirportSpec.Gate> gates,
      SimpleMessageListenerContainer container) { }

  private final Topology topology;
  private final ContainerFactory containerFactory;
  private final AirportDeclarations declarations;
  private final AirportListenerFactory listenerFactory; // null when no consumer module is present
  private final TractionProperties properties;
  private final String owningProcess;
  private final Map<String, Registered> airports = new ConcurrentHashMap<>();

  public AirportRegistry(Topology topology, ContainerFactory containerFactory,
      AirportDeclarations declarations, AirportListenerFactory listenerFactory,
      TractionProperties properties, String owningProcess) {
    this.topology = topology;
    this.containerFactory = containerFactory;
    this.declarations = declarations;
    this.listenerFactory = listenerFactory;
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
    if (properties.isEnabled()) {
      container.start();
      log.info("airport {} registered and consuming", id);
    } else {
      log.info("airport {} registered dormant (traction.enabled=false)", id);
    }
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
