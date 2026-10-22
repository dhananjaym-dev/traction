package dev.traction.core.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.traction.core.api.Flight;
import dev.traction.core.api.HoldPolicy;
import dev.traction.core.consume.HoldingPatternService.HoldOutcome;
import dev.traction.core.publish.ConfirmedPublisher;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HoldingPatternServiceTest {

  private final ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
  private final HoldingPatternService service = new HoldingPatternService(publisher);

  private static Flight<String> flight(int holdCount) {
    return new Flight<>(UUID.randomUUID(), "crm:acme", "contact", "d", null, 1, holdCount, "c",
        String.class.getName(), "payload");
  }

  @Test
  @SuppressWarnings("unchecked")
  void transientScheduleHolds30s2m10mThenParks() {
    List<Duration> expected = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2),
        Duration.ofMinutes(10));
    for (int round = 0; round < expected.size(); round++) {
      assertEquals(HoldOutcome.HELD, service.holdOrPark(flight(round), HoldPolicy.TRANSIENT_DEFAULT));
      ArgumentCaptor<Flight<?>> held = ArgumentCaptor.forClass(Flight.class);
      verify(publisher).publish(eq("tr.crm:acme.holding"), held.capture(), eq(expected.get(round)));
      assertEquals(round + 1, held.getValue().holdCount());
    }

    assertEquals(HoldOutcome.PARKED, service.holdOrPark(flight(3), HoldPolicy.TRANSIENT_DEFAULT));
    verify(publisher).publish(eq("tr.crm:acme.parked"), any(Flight.class), isNull());
    verify(publisher, times(3)).publish(eq("tr.crm:acme.holding"), any(), any()); // budget: 3 rounds
  }

  @Test
  void publishFailurePropagatesForTheConsumerFallback() {
    doThrow(new ConfirmedPublisher.PublishException("nack")).when(publisher).publish(any(), any(), any());

    assertThrows(ConfirmedPublisher.PublishException.class,
        () -> service.holdOrPark(flight(0), HoldPolicy.INTERNAL_DEFAULT));
  }
}
