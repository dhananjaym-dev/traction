package dev.traction.core.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.traction.core.api.AirportSpec;
import dev.traction.core.api.AirportStatus;
import dev.traction.core.api.FlightReport;
import dev.traction.core.config.TractionProperties;
import dev.traction.core.status.ScopeRef;
import dev.traction.core.status.StatusReader;
import dev.traction.core.topology.Topology;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

class AirportRegistryTest {

  private final Topology topology = mock(Topology.class);
  private final ContainerFactory containerFactory = mock(ContainerFactory.class);
  private final AirportDeclarations declarations = mock(AirportDeclarations.class);
  private final AirportListenerFactory listeners = mock(AirportListenerFactory.class);
  private final ChannelAwareMessageListener listener = mock(ChannelAwareMessageListener.class);
  private final SimpleMessageListenerContainer containerA = mock(SimpleMessageListenerContainer.class);
  private final SimpleMessageListenerContainer containerB = mock(SimpleMessageListenerContainer.class);
  private final StatusReader statusReader = mock(StatusReader.class);
  private final TractionProperties properties = new TractionProperties();
  private AirportRegistry registry;

  private static AirportSpec spec(String id) {
    return AirportSpec.of(id).displayName(id + " airport")
        .slots(60, Duration.ofMinutes(1))
        .pilot(flight -> { })
        .reporter((flight, error) -> FlightReport.landed());
  }

  @BeforeEach
  void setUp() {
    when(listeners.forAirport(any())).thenReturn(listener);
    when(containerFactory.create(any(), any())).thenReturn(containerA, containerB);
    registry = new AirportRegistry(topology, containerFactory, declarations, listeners, statusReader, properties, "demo-app");
  }

  @Test
  void twoSpecsGetTwoContainersAndOneAnnouncementEach() {
    registry.registerAll(List.of(spec("acme").gate("contacts").gate("export", 5), spec("globex")));

    verify(topology).declare("acme");
    verify(topology).declare("globex");
    verify(containerFactory).create(argThat(s -> s.airportId().equals("acme")), any());
    verify(containerFactory).create(argThat(s -> s.airportId().equals("globex")), any());
    // containers are built at registration but only start with the lifecycle
    verify(containerA, never()).start();
    verify(containerB, never()).start();
    registry.start();
    verify(containerA).start();
    verify(containerB).start();

    ArgumentCaptor<AirportAnnouncement> captor = ArgumentCaptor.forClass(AirportAnnouncement.class);
    verify(declarations, times(2)).announce(captor.capture());
    AirportAnnouncement acme = captor.getAllValues().get(0);
    assertEquals("acme", acme.airportId());
    assertEquals("acme airport", acme.displayName());
    assertEquals("demo-app", acme.owningProcess());
    assertEquals(60, acme.slotsLimit());
    assertEquals(60L, acme.slotsWindowSeconds());
    assertEquals(60, acme.slotsBurst());
    assertEquals(List.of(new AirportAnnouncement.GateAnnouncement("contacts", 1),
        new AirportAnnouncement.GateAnnouncement("export", 5)), acme.gates());
    assertEquals("globex", captor.getAllValues().get(1).airportId());
    assertEquals(Set.of("acme", "globex"), registry.airportIds());
  }

  @Test
  void queuesAreDeclaredBeforeAnnouncingSoTheAnnouncementIsRoutable() {
    registry.register(spec("acme"));
    InOrder order = inOrder(topology, declarations);
    order.verify(topology).declare("acme");
    order.verify(topology).declareTower();
    order.verify(declarations).announce(any());
  }

  @Test
  void disabledFrameworkDeclaresAndAnnouncesButNeverStartsConsumers() {
    properties.setEnabled(false);

    registry.register(spec("acme"));
    registry.start();

    verify(topology).declare("acme");
    verify(declarations).announce(any());
    verify(containerA, never()).start();
    assertTrue(registry.isRegistered("acme"));
  }

  @Test
  void duplicateAirportIdsAreRejectedBeforeAnythingIsDeclared() {
    assertThrows(IllegalArgumentException.class,
        () -> registry.registerAll(List.of(spec("acme"), spec("acme"))));
    verifyNoInteractions(topology, declarations, containerFactory);

    registry.register(spec("acme"));
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> registry.register(spec("acme")));
    assertTrue(e.getMessage().contains("acme"));
  }

  @Test
  void invalidSpecFailsTheWholeBatchBeforeAnythingIsDeclared() {
    AirportSpec noPilot = AirportSpec.of("broken").slots(60, Duration.ofMinutes(1))
        .reporter((flight, error) -> FlightReport.landed());

    assertThrows(IllegalStateException.class, () -> registry.registerAll(List.of(spec("acme"), noPilot)));
    verifyNoInteractions(topology, declarations, containerFactory);
  }

  @Test
  void gateCostAboveTheBurstIsRejectedNamingAirportAndGate() {
    AirportSpec spec = spec("acme").gate("export", 61);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> registry.registerAll(List.of(spec)));

    assertTrue(e.getMessage().contains("acme") && e.getMessage().contains("export"), e.getMessage());
    verifyNoInteractions(topology, declarations, containerFactory);
  }

  @Test
  void gatesAreSnapshottedAtRegistration() {
    AirportSpec spec = spec("acme").gate("contacts");
    registry.register(spec);

    spec.gate("added-later");

    assertEquals(Set.of("contacts"), registry.gatesOf("acme").orElseThrow().keySet());
    assertTrue(registry.gatesOf("unknown").isEmpty());
  }

  @Test
  void missingListenerFactoryFailsWithAClearMessage() {
    AirportRegistry bare = new AirportRegistry(topology, containerFactory, declarations, null, statusReader, properties, "demo-app");

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> bare.register(spec("acme")));

    assertTrue(e.getMessage().contains("AirportListenerFactory"), e.getMessage());
    verifyNoInteractions(topology, declarations);
  }

  @Test
  void noSpecsRegistersNothingAndNeedsNoListenerFactory() {
    AirportRegistry bare = new AirportRegistry(topology, containerFactory, declarations, null, statusReader, properties, "demo-app");
    bare.registerAll(List.of());
    verifyNoInteractions(topology, declarations, containerFactory);
  }

  @Test
  void stopStopsEveryContainerEvenWhenOneFailsToStop() {
    registry.registerAll(List.of(spec("acme"), spec("globex")));
    registry.start();
    assertTrue(registry.isRunning());
    doThrow(new IllegalStateException("boom")).when(containerA).stop();

    registry.stop();

    verify(containerA).stop();
    verify(containerB).stop();
    assertFalse(registry.isRunning());
  }

  @Test
  void airportRegisteredAfterStartIsStartedImmediately() {
    registry.start();
    registry.register(spec("acme"));
    verify(containerA).start();
  }

  @Test
  void registryStartsLastAndStopsFirst() {
    assertEquals(Integer.MAX_VALUE, registry.getPhase());
  }

  @Test
  void deregisterStopsTheContainerAndForgetsTheAirport() {
    registry.register(spec("acme"));

    registry.deregister("acme");

    verify(containerA).stop();
    assertFalse(registry.isRegistered("acme"));
  }

  @Test
  void requestPauseStopsARunningContainerOffTheCallingThread() {
    registry.register(spec("acme"));
    registry.start();
    when(containerA.isRunning()).thenReturn(true);

    registry.requestPause("acme");
    registry.requestPause("unknown"); // no-op

    verify(containerA, timeout(2_000)).stop();
  }

  @Test
  void resumeRestartsAPausedContainerOnlyWhenRunningAndStatusAllowsConsumption() {
    registry.register(spec("acme"));
    when(statusReader.get(ScopeRef.airport("acme"))).thenReturn(AirportStatus.OPEN);
    registry.resume("acme"); // lifecycle not started yet
    verify(containerA, never()).start();

    registry.start(); // first start
    when(statusReader.get(ScopeRef.airport("acme"))).thenReturn(AirportStatus.GROUND_STOP);
    registry.resume("acme"); // still grounded
    verify(containerA, times(1)).start();

    when(statusReader.get(ScopeRef.airport("acme"))).thenReturn(AirportStatus.OPEN);
    registry.resume("acme"); // reopened
    verify(containerA, times(2)).start();
  }
}
