package dev.traction.core.status;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.traction.core.registry.AirportRegistry;
import org.junit.jupiter.api.Test;

/** {@code tr:events} fan-out: every process pauses on a ground stop and resumes on reopen. */
class EventsListenerTest {

  private final AirportRegistry registry = mock(AirportRegistry.class);
  private final EventsListener listener = new EventsListener(registry);

  @Test
  void aGroundStoppedAirportIsPausedHere() {
    when(registry.isRegistered("crm:acme")).thenReturn(true);

    listener.onEvent("STATUS_CHANGED|crm:acme|GROUND_STOP|AIRPORT_FAULT");

    verify(registry).requestPause("crm:acme");
    verify(registry, never()).resume(anyString());
  }

  @Test
  void aReopenedAirportIsResumedHere() {
    when(registry.isRegistered("crm:acme")).thenReturn(true);

    listener.onEvent("STATUS_CHANGED|crm:acme|OPEN|");

    verify(registry).resume("crm:acme");
  }

  @Test
  void gateScopeForeignAirportsAndMalformedPayloadsAreIgnored() {
    when(registry.isRegistered("crm:acme")).thenReturn(true);

    listener.onEvent("STATUS_CHANGED|crm:acme/company-sync|GROUND_STOP|GATE_FAULT");
    listener.onEvent("STATUS_CHANGED|mail:acme|GROUND_STOP|AIRPORT_FAULT"); // not hosted here
    listener.onEvent("garbage");
    listener.onEvent("STATUS_CHANGED|crm:acme|SIDEWAYS|");

    verify(registry, never()).requestPause(anyString());
    verify(registry, never()).resume(anyString());
  }
}
