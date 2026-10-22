package dev.traction.core.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import dev.traction.core.api.Flight;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.topology.QueueNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Moves every parked flight of an airport back to its runway, after the underlying fault has
 * been fixed. Each flight is read unacked, republished to the runway with the broker's confirm,
 * and only then acked: a crash between the two duplicates the flight on the runway (at-least-once,
 * consumers are idempotent on {@code domainRef}) but never loses it. A replayed flight starts a
 * fresh life: attempt and hold counts reset to zero, otherwise a flight parked for exhausting its
 * hold budget would be parked again at its first new failure.
 *
 * <p>Parked messages that are not decodable flights cannot be reset, so they are requeued where
 * they were (reported as {@code skipped}). One operation per airport at a time ({@link ParkedOpLock}).
 */
@Slf4j
public class ParkedReplayService {

  private static final TypeReference<Flight<JsonNode>> WIRE_FLIGHT = new TypeReference<>() { };

  private final RabbitTemplate rabbitTemplate;
  private final ConfirmedPublisher confirmedPublisher;
  private final ObjectMapper objectMapper;
  private final ParkedOpLock lock;
  private final NotifyDispatcher notify;

  public ParkedReplayService(RabbitTemplate rabbitTemplate, ConfirmedPublisher confirmedPublisher,
      ObjectMapper objectMapper, ParkedOpLock lock, NotifyDispatcher notify) {
    this.rabbitTemplate = rabbitTemplate;
    this.confirmedPublisher = confirmedPublisher;
    this.objectMapper = objectMapper;
    this.lock = lock;
    this.notify = notify;
  }

  /**
   * Replays the airport's parked queue (as it stood when the replay began) to its runway.
   *
   * @throws ParkedOpInProgressException another replay or discard holds the airport
   * @throws IllegalStateException a publish was not confirmed; flights already moved stay moved,
   *     the failing one stays parked
   */
  public ReplayResult replayParked(String airportId) {
    String token = lock.acquire(airportId);
    if (token == null) {
      throw new ParkedOpInProgressException(airportId);
    }
    try {
      ReplayResult result = rabbitTemplate.execute(channel -> pump(channel, airportId));
      log.info("airport {}: replayed {} parked flights to the runway ({} undecodable left parked)",
          airportId, result.replayed(), result.skipped());
      notify.dispatch(new NotifyEvent(NotifyEvent.Type.PARKED_REPLAYED, airportId, null, null, null,
          Map.of("count", String.valueOf(result.replayed()), "skipped", String.valueOf(result.skipped()))));
      return result;
    } finally {
      lock.release(airportId, token);
    }
  }

  private ReplayResult pump(Channel channel, String airportId) throws Exception {
    QueueNames names = QueueNames.of(airportId);
    List<Long> undecodable = new ArrayList<>();
    int replayed = 0;
    try {
      int cap = Integer.MAX_VALUE; // bounded by the depth seen on the first pop, so new parkings don't extend it
      for (int pops = 0; pops < cap; pops++) {
        GetResponse response = channel.basicGet(names.parked(), false);
        if (response == null) {
          break;
        }
        if (pops == 0) {
          cap = response.getMessageCount() + 1;
        }
        long tag = response.getEnvelope().getDeliveryTag();
        Flight<JsonNode> flight = decode(response.getBody());
        if (flight == null) {
          log.warn("airport {}: undecodable parked message left in the parked queue", airportId);
          undecodable.add(tag);
          continue;
        }
        try {
          confirmedPublisher.publish(names.runway(), fresh(flight), null);
          channel.basicAck(tag, false); // only after the broker confirmed the publish
        } catch (Exception e) {
          channel.basicNack(tag, false, true);
          throw new IllegalStateException("replay of airport " + airportId + " stopped after "
              + replayed + " flights: " + e.getMessage(), e);
        }
        replayed++;
      }
    } finally {
      for (long tag : undecodable) {
        channel.basicNack(tag, false, true);
      }
    }
    return new ReplayResult(airportId, replayed, undecodable.size());
  }

  private static Flight<JsonNode> fresh(Flight<JsonNode> f) {
    return new Flight<>(f.flightId(), f.airportId(), f.gateId(), f.domainRef(), f.charterRef(), 0, 0,
        f.correlationId(), f.payloadType(), f.payload());
  }

  private Flight<JsonNode> decode(byte[] body) {
    try {
      return objectMapper.readValue(body, WIRE_FLIGHT);
    } catch (Exception e) {
      return null;
    }
  }
}
