package dev.traction.core.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.charter.CharterEventMessage;
import dev.traction.core.registry.AirportAnnouncement;
import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.topology.QueueNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

/**
 * The tower queue's type dispatch and poison/retry ladder. Runs under the container's AUTO ack:
 * returning acks, throwing requeues, so throwing is the retry mechanism, paced here against the
 * queue's {@code x-delivery-limit} of 20 before the broker dead-letters to {@code tr.dlq}.
 * <ul>
 *   <li>undeserializable or of an unexpected type &rarr; rejected without requeue (dead-lettered
 *       for a human, never a loop);</li>
 *   <li>a handler failure &rarr; sleep the backoff, rethrow (redelivered).</li>
 * </ul>
 * Dispatch is explicit {@code instanceof} on purpose: the failure semantics are the point.
 */
@Slf4j
public class TowerMessageListener implements MessageListener {

  static final long DEFAULT_RETRY_BACKOFF_MS = 1000;

  private final MessageConverter converter;
  private final TowerConsumer consumer;
  private final long retryBackoffMs;

  public TowerMessageListener(MessageConverter converter, TowerConsumer consumer) {
    this(converter, consumer, DEFAULT_RETRY_BACKOFF_MS);
  }

  TowerMessageListener(MessageConverter converter, TowerConsumer consumer, long retryBackoffMs) {
    this.converter = converter;
    this.consumer = consumer;
    this.retryBackoffMs = retryBackoffMs;
  }

  /**
   * The tower's reader: the Traction wire format (the publisher's mapper, type id in the
   * {@code __TypeId__} header) restricted to the packages of the three tower message types.
   */
  public static MessageConverter converter(ObjectMapper tractionObjectMapper) {
    return new Jackson2JsonMessageConverter(tractionObjectMapper,
        FlightReportMessage.class.getPackageName(), AirportAnnouncement.class.getPackageName(),
        CharterEventMessage.class.getPackageName());
  }

  @Override
  public void onMessage(Message message) {
    Object payload;
    try {
      payload = converter.fromMessage(message);
    } catch (RuntimeException e) {
      log.error("tower message undeserializable; dead-lettering to {}", QueueNames.deadLetter(), e);
      throw new AmqpRejectAndDontRequeueException("undeserializable", e);
    }
    try {
      if (payload instanceof FlightReportMessage report) {
        consumer.onReport(report);
      } else if (payload instanceof AirportAnnouncement announcement) {
        consumer.onAnnouncement(announcement);
      } else if (payload instanceof CharterEventMessage event) {
        consumer.onCharterEvent(event);
      } else {
        log.error("tower message of unexpected type {}; dead-lettering to {}",
            payload == null ? "null" : payload.getClass().getName(), QueueNames.deadLetter());
        throw new AmqpRejectAndDontRequeueException("unexpected type");
      }
    } catch (AmqpRejectAndDontRequeueException reject) {
      throw reject;
    } catch (RuntimeException e) {
      // Deliberate head-of-line stall: with one tower consumer this pauses all intake for the
      // backoff. A shared cause (Redis down) would only burn the other messages' delivery
      // budgets too, so blocking the stream is the backpressure.
      sleepQuietly(retryBackoffMs);
      throw e;
    }
  }

  private static void sleepQuietly(long ms) {
    if (ms <= 0) {
      return;
    }
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }
}
