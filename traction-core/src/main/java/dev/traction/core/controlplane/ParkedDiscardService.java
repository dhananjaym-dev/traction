package dev.traction.core.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import dev.traction.core.api.Flight;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.charter.DiscardTally;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.topology.QueueNames;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Drops every parked flight of an airport. The only destructive action in the framework, so it
 * demands the literal confirm token {@value #CONFIRM_TOKEN}; anything else is rejected before
 * the queue or the lock is touched. Each flight that belongs to a charter is reported to the
 * {@link DiscardTally} (if one is registered) so the charter's accounting stays whole, then the
 * message is acked. A tally failure requeues that message and stops the discard. Messages that
 * are not decodable flights are dropped too, with nothing to tally.
 *
 * <p>One operation per airport at a time ({@link ParkedOpLock}).
 */
@Slf4j
public class ParkedDiscardService {

  public static final String CONFIRM_TOKEN = "discard-all";

  private static final TypeReference<Flight<JsonNode>> WIRE_FLIGHT = new TypeReference<>() { };

  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final ParkedOpLock lock;
  private final NotifyDispatcher notify;
  private final ObjectProvider<DiscardTally> tally;

  public ParkedDiscardService(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper,
      ParkedOpLock lock, NotifyDispatcher notify, ObjectProvider<DiscardTally> tally) {
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
    this.lock = lock;
    this.notify = notify;
    this.tally = tally;
  }

  /**
   * Discards the airport's parked queue (as it stood when the discard began).
   *
   * @throws IllegalArgumentException {@code confirmToken} is not {@value #CONFIRM_TOKEN}; nothing was touched
   * @throws ParkedOpInProgressException another replay or discard holds the airport
   * @throws IllegalStateException a tally or ack failed; flights already dropped stay dropped
   *     (the message says how many), the failing one stays parked
   */
  public DiscardResult discardParked(String airportId, String confirmToken) {
    if (!CONFIRM_TOKEN.equals(confirmToken)) {
      throw new IllegalArgumentException("discard requires confirmToken=\"" + CONFIRM_TOKEN + "\"");
    }
    String token = lock.acquire(airportId);
    if (token == null) {
      throw new ParkedOpInProgressException(airportId);
    }
    try {
      DiscardResult result = ParkedReplayService.onChannel(rabbitTemplate, channel -> pump(channel, airportId));
      log.warn("airport {}: discarded {} parked flights", airportId, result.dropped());
      notify.dispatch(new NotifyEvent(NotifyEvent.Type.PARKED_DISCARDED, airportId, null, null, null,
          Map.of("count", String.valueOf(result.dropped()))));
      return result;
    } finally {
      lock.release(airportId, token);
    }
  }

  private DiscardResult pump(Channel channel, String airportId) throws Exception {
    String parked = QueueNames.parked(airportId);
    DiscardTally charters = tally.getIfAvailable();
    int dropped = 0;
    int cap = Integer.MAX_VALUE; // bounded by the depth seen on the first pop
    for (int pops = 0; pops < cap; pops++) {
      GetResponse response = channel.basicGet(parked, false);
      if (response == null) {
        break;
      }
      if (pops == 0) {
        cap = response.getMessageCount() + 1;
      }
      long tag = response.getEnvelope().getDeliveryTag();
      try {
        Flight<JsonNode> flight = decode(response.getBody());
        if (flight == null) {
          log.warn("airport {}: dropping undecodable parked message", airportId);
        } else if (charters != null && flight.charterRef() != null && flight.flightId() != null) {
          charters.discarded(flight.charterRef(), flight.flightId());
        }
        channel.basicAck(tag, false); // the drop; after the tally, so a failed tally keeps the message
      } catch (Exception e) {
        channel.basicNack(tag, false, true);
        // The audit line for a partial drop: what is gone stays gone, the rest stays parked.
        log.warn("airport {}: discard stopped after dropping {} parked flights; the rest stay parked",
            airportId, dropped, e);
        throw new IllegalStateException("discard of airport " + airportId + " stopped after dropping "
            + dropped + " flights: " + e.getMessage(), e);
      }
      dropped++;
    }
    return new DiscardResult(airportId, dropped);
  }

  private Flight<JsonNode> decode(byte[] body) {
    try {
      return objectMapper.readValue(body, WIRE_FLIGHT);
    } catch (Exception e) {
      return null;
    }
  }
}
