package dev.traction.core.controlplane;

import static dev.traction.core.controlplane.ParkedOps.AIRPORT;
import static dev.traction.core.controlplane.ParkedOps.PARKED;
import static dev.traction.core.controlplane.ParkedOps.RUNWAY;
import static dev.traction.core.controlplane.ParkedOps.parked;
import static dev.traction.core.controlplane.ParkedOps.raw;
import static dev.traction.core.controlplane.ParkedOps.templateOver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import dev.traction.core.api.Flight;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.charter.ReplayTally;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.publish.ConfirmedPublisher.PublishException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

/** Replay moves parked flights to the runway, publish-confirmed before each ack. */
class ParkedReplayServiceTest {

  private final Channel channel = mock(Channel.class);
  private final RabbitTemplate template = templateOver(channel);
  private final ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
  private final ParkedOpLock lock = mock(ParkedOpLock.class);
  private final List<NotifyEvent> notified = new ArrayList<>();
  private final ReplayTally tally = mock(ReplayTally.class);
  private final ParkedReplayService service = new ParkedReplayService(template, publisher,
      new ObjectMapper(), lock, new NotifyDispatcher(List.of(notified::add)), provider(tally));

  @SuppressWarnings("unchecked")
  private static ObjectProvider<ReplayTally> provider(ReplayTally tally) {
    ObjectProvider<ReplayTally> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(tally);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private Flight<JsonNode> published(int index) {
    ArgumentCaptor<Object> flight = ArgumentCaptor.forClass(Object.class);
    verify(publisher, atLeastOnce()).publish(eq(RUNWAY), flight.capture(), isNull());
    return (Flight<JsonNode>) flight.getAllValues().get(index);
  }

  @Test
  void replaysEveryParkedFlightToTheRunwayAndEmitsParkedReplayed() throws Exception {
    when(lock.acquire(AIRPORT)).thenReturn("tok");
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(channel.basicGet(PARKED, false))
        .thenReturn(parked(1, a, "c-1", 2, 3, 1), parked(2, b, null, 0, 3, 0), (GetResponse) null);

    ReplayResult result = service.replayParked(AIRPORT);

    assertEquals(new ReplayResult(AIRPORT, 2, 0), result);
    assertEquals(a, published(0).flightId());
    assertEquals(b, published(1).flightId());
    verify(channel).basicAck(1L, false);
    verify(channel).basicAck(2L, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    verify(lock).release(AIRPORT, "tok");
    assertEquals(1, notified.size());
    assertEquals(NotifyEvent.Type.PARKED_REPLAYED, notified.get(0).type());
    assertEquals(AIRPORT, notified.get(0).airportId());
    assertEquals("2", notified.get(0).detail().get("count"));
  }

  @Test
  void replayedFlightGetsAFreshAttemptAndHoldBudgetWithEverythingElseUnchanged() throws Exception {
    when(lock.acquire(AIRPORT)).thenReturn("tok");
    UUID id = UUID.randomUUID();
    when(channel.basicGet(PARKED, false)).thenReturn(parked(1, id, "c-1", 2, 3, 0), (GetResponse) null);

    service.replayParked(AIRPORT);

    Flight<JsonNode> flight = published(0);
    assertEquals(0, flight.attemptCount());
    assertEquals(0, flight.holdCount());
    assertEquals(id, flight.flightId());
    assertEquals("c-1", flight.charterRef());
    assertEquals("co-1", flight.domainRef());
    assertEquals("company-sync", flight.gateId());
    assertEquals("corr-1", flight.correlationId());
    assertEquals("p-1", flight.payload().asText());
  }

  /** Review Focus 5: the charter un-counts the parked flight before it re-enters the runway. */
  @Test
  void eachCharterFlightIsUncountedBeforeItsRunwayPublishAndAFailedUncountKeepsItParked()
      throws Exception {
    when(lock.acquire(AIRPORT)).thenReturn("tok");
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(channel.basicGet(PARKED, false)).thenReturn(parked(1, a, "mail:acme/welcome/run-1", 1, 0, 1),
        parked(2, b, null, 1, 0, 0), (GetResponse) null);

    service.replayParked(AIRPORT);

    var order = inOrder(tally, publisher, channel);
    order.verify(tally).replayed("mail:acme/welcome/run-1", a);
    order.verify(publisher).publish(eq(RUNWAY), any(), isNull());
    order.verify(channel).basicAck(1L, false);
    verify(tally, times(1)).replayed(anyString(), any()); // no charter, no tally

    doThrow(new IllegalStateException("redis down")).when(tally).replayed(anyString(), any());
    when(channel.basicGet(PARKED, false)).thenReturn(parked(3, a, "mail:acme/welcome/run-1", 1, 0, 0));
    assertThrows(IllegalStateException.class, () -> service.replayParked(AIRPORT));
    verify(channel).basicNack(3L, false, true);
    verify(publisher, times(2)).publish(eq(RUNWAY), any(), isNull()); // nothing new published
  }

  @Test
  void ackHappensOnlyAfterTheConfirmedPublish() throws Exception {
    when(lock.acquire(AIRPORT)).thenReturn("tok");
    when(channel.basicGet(PARKED, false))
        .thenReturn(parked(1, UUID.randomUUID(), null, 0, 0, 0), (GetResponse) null);

    service.replayParked(AIRPORT);

    var order = inOrder(publisher, channel);
    order.verify(publisher).publish(eq(RUNWAY), any(), isNull());
    order.verify(channel).basicAck(1L, false);
  }

  @Test
  void publishFailureRequeuesTheFlightStopsAndReleasesTheLock() throws Exception {
    when(lock.acquire(AIRPORT)).thenReturn("tok");
    when(channel.basicGet(PARKED, false))
        .thenReturn(parked(1, UUID.randomUUID(), null, 0, 0, 1), parked(2, UUID.randomUUID(), null, 0, 0, 0));
    doThrow(new PublishException("broker did not confirm publish to " + RUNWAY))
        .when(publisher).publish(eq(RUNWAY), any(), isNull());

    IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.replayParked(AIRPORT));

    assertTrue(e.getMessage().contains("stopped after 0 flights"), e.getMessage()); // unwrapped
    verify(channel).basicNack(1L, false, true);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    verify(channel, times(1)).basicGet(PARKED, false);
    verify(lock).release(AIRPORT, "tok");
    assertTrue(notified.isEmpty());
  }

  @Test
  void undecodableParkedMessageIsLeftInParkedNotReplayedAndNotCounted() throws Exception {
    when(lock.acquire(AIRPORT)).thenReturn("tok");
    when(channel.basicGet(PARKED, false)).thenReturn(raw(1, "not json", 1),
        parked(2, UUID.randomUUID(), null, 0, 0, 0), (GetResponse) null);

    ReplayResult result = service.replayParked(AIRPORT);

    assertEquals(new ReplayResult(AIRPORT, 1, 1), result);
    verify(channel).basicNack(1L, false, true);
    verify(channel).basicAck(2L, false);
    verify(publisher, times(1)).publish(eq(RUNWAY), any(), isNull());
  }

  @Test
  void aConcurrentOpIsRefusedAsAlreadyRunningAndTouchesNothing() {
    when(lock.acquire(AIRPORT)).thenReturn(null);

    ParkedOpInProgressException e =
        assertThrows(ParkedOpInProgressException.class, () -> service.replayParked(AIRPORT));

    assertTrue(e.getMessage().contains("already-running"));
    verifyNoInteractions(template, publisher);
    verify(lock, never()).release(anyString(), anyString());
  }
}
