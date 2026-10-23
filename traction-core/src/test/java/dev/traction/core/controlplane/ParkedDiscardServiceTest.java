package dev.traction.core.controlplane;

import static dev.traction.core.controlplane.ParkedOps.AIRPORT;
import static dev.traction.core.controlplane.ParkedOps.PARKED;
import static dev.traction.core.controlplane.ParkedOps.parked;
import static dev.traction.core.controlplane.ParkedOps.raw;
import static dev.traction.core.controlplane.ParkedOps.templateOver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.charter.DiscardTally;
import dev.traction.core.hooks.NotifyDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

/** Discard is the one destructive action: confirm-token gated, tallied per charter. */
class ParkedDiscardServiceTest {

  private final Channel channel = mock(Channel.class);
  private final RabbitTemplate template = templateOver(channel);
  private final ParkedOpLock lock = mock(ParkedOpLock.class);
  private final List<NotifyEvent> notified = new ArrayList<>();
  private final List<String> tallied = new ArrayList<>();
  @SuppressWarnings("unchecked")
  private final ObjectProvider<DiscardTally> tally = mock(ObjectProvider.class);
  private final ParkedDiscardService service = new ParkedDiscardService(template, new ObjectMapper(),
      lock, new NotifyDispatcher(List.of(notified::add)), tally);

  @Test
  void aWrongOrMissingConfirmTokenThrowsBeforeTouchingTheQueueOrTheLock() {
    assertThrows(IllegalArgumentException.class, () -> service.discardParked(AIRPORT, null));
    assertThrows(IllegalArgumentException.class, () -> service.discardParked(AIRPORT, "yes"));
    assertThrows(IllegalArgumentException.class, () -> service.discardParked(AIRPORT, "DISCARD-ALL"));

    verifyNoInteractions(template, channel, lock);
    assertTrue(notified.isEmpty());
  }

  @Test
  void dropsEveryParkedMessageTalliesEachFlightOnItsCharterAndEmitsParkedDiscarded() throws Exception {
    when(tally.getIfAvailable()).thenReturn((ref, id) -> tallied.add(ref + "/" + id));
    when(lock.acquire(AIRPORT)).thenReturn("tok");
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    UUID d = UUID.randomUUID();
    when(channel.basicGet(PARKED, false)).thenReturn(parked(1, a, "c-1", 0, 3, 3),
        parked(2, b, "c-1", 0, 3, 2), parked(3, c, "c-2", 0, 3, 1), parked(4, d, null, 0, 3, 0),
        (GetResponse) null);

    DiscardResult result = service.discardParked(AIRPORT, "discard-all");

    assertEquals(new DiscardResult(AIRPORT, 4), result);
    assertEquals(List.of("c-1/" + a, "c-1/" + b, "c-2/" + c), tallied); // charterless flight: no tally
    for (long tag = 1; tag <= 4; tag++) {
      verify(channel).basicAck(tag, false);
    }
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    verify(lock).release(AIRPORT, "tok");
    assertEquals(1, notified.size());
    assertEquals(NotifyEvent.Type.PARKED_DISCARDED, notified.get(0).type());
    assertEquals(AIRPORT, notified.get(0).airportId());
    assertEquals("4", notified.get(0).detail().get("count"));
  }

  @Test
  void undecodableMessagesAreDroppedAndCountedButHaveNoCharterToTally() throws Exception {
    when(tally.getIfAvailable()).thenReturn((ref, id) -> tallied.add(ref));
    when(lock.acquire(AIRPORT)).thenReturn("tok");
    when(channel.basicGet(PARKED, false)).thenReturn(raw(1, "not json", 0), (GetResponse) null);

    DiscardResult result = service.discardParked(AIRPORT, "discard-all");

    assertEquals(1, result.dropped());
    assertTrue(tallied.isEmpty());
    verify(channel).basicAck(1L, false);
  }

  @Test
  void worksWithoutAnyDiscardTallyBean() throws Exception {
    when(tally.getIfAvailable()).thenReturn(null);
    when(lock.acquire(AIRPORT)).thenReturn("tok");
    when(channel.basicGet(PARKED, false))
        .thenReturn(parked(1, UUID.randomUUID(), "c-1", 0, 0, 0), (GetResponse) null);

    assertEquals(1, service.discardParked(AIRPORT, "discard-all").dropped());
  }

  @Test
  void aFailedTallyRequeuesThatMessageStopsAndReleasesTheLock() throws Exception {
    DiscardTally broken = (ref, id) -> {
      throw new IllegalStateException("redis down");
    };
    when(tally.getIfAvailable()).thenReturn(broken);
    when(lock.acquire(AIRPORT)).thenReturn("tok");
    when(channel.basicGet(PARKED, false)).thenReturn(parked(1, UUID.randomUUID(), null, 0, 0, 1),
        parked(2, UUID.randomUUID(), "c-1", 0, 0, 0), (GetResponse) null);

    // The service's own stop, not the template's UncategorizedAmqpException wrapper, so the
    // admin API maps it to its documented 500 body with this message.
    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> service.discardParked(AIRPORT, "discard-all"));

    assertTrue(e.getMessage().contains("stopped after dropping 1 flights"), e.getMessage());
    verify(channel).basicAck(1L, false); // dropped before the failure: stays dropped
    verify(channel).basicNack(2L, false, true);
    verify(channel, never()).basicAck(2L, false);
    verify(lock).release(AIRPORT, "tok");
    assertTrue(notified.isEmpty());
  }

  @Test
  void aConcurrentOpIsRefusedAsAlreadyRunningAndDropsNothing() {
    when(lock.acquire(AIRPORT)).thenReturn(null);

    ParkedOpInProgressException e = assertThrows(ParkedOpInProgressException.class,
        () -> service.discardParked(AIRPORT, "discard-all"));

    assertTrue(e.getMessage().contains("already-running"));
    verifyNoInteractions(template, channel);
    verify(lock, never()).release(anyString(), anyString());
  }
}
