package dev.traction.core.publish;

import java.time.Duration;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitOperations;

/**
 * Publishes that wait for the broker's confirm. Publish-then-ack is only loss-safe when the
 * publish is confirmed before the original delivery is acked, so framework re-entry paths (hold,
 * tower reports, announcements) go through here. It uses a dedicated confirm-enabled template so
 * the application's own connection factory keeps its no-confirms behavior. A throw means "do not
 * ack the original": callers fall back to nack-requeue and let the broker redeliver.
 *
 * <p>{@code Tower.dispatch} deliberately does not use this class: it is a plain best-effort send
 * from application code.
 */
public class ConfirmedPublisher {

  /** Broker-confirm wait. */
  static final long CONFIRM_TIMEOUT_MS = 5_000;

  private final RabbitOperations confirmedTemplate;

  public ConfirmedPublisher(RabbitOperations confirmedTemplate) {
    this.confirmedTemplate = confirmedTemplate;
  }

  /**
   * Publishes {@code payload} to {@code queue} via the default exchange and blocks for the broker
   * confirm. A non-null {@code ttl} becomes the message's per-message expiration.
   */
  public void publish(String queue, Object payload, Duration ttl) {
    Boolean confirmed;
    try {
      confirmed = confirmedTemplate.invoke(ops -> {
        if (ttl != null) {
          ops.convertAndSend("", queue, payload, m -> {
            m.getMessageProperties().setExpiration(String.valueOf(ttl.toMillis()));
            return m;
          });
        } else {
          ops.convertAndSend("", queue, payload);
        }
        return ops.waitForConfirms(CONFIRM_TIMEOUT_MS);
      });
    } catch (AmqpException e) {
      throw new PublishException("broker error publishing to " + queue, e);
    }
    if (!Boolean.TRUE.equals(confirmed)) {
      throw new PublishException("broker did not confirm publish to " + queue);
    }
  }

  public static class PublishException extends RuntimeException {
    public PublishException(String message) {
      super(message);
    }

    public PublishException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
