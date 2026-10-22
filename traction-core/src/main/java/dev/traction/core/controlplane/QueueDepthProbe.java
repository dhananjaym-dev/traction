package dev.traction.core.controlplane;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * The queue-depth read for the control plane. Empty means unknown: the queue is absent or the
 * broker is unreachable, and the two are indistinguishable here. Callers fail safe on empty and
 * never see a fabricated zero.
 */
@Slf4j
public class QueueDepthProbe {

  private static final long CACHE_TTL_MS = 10_000L;

  private record CachedDepth(Optional<Long> value, long atMillis) { }

  private final AmqpAdmin rabbitAdmin;
  private final Map<String, CachedDepth> cache = new ConcurrentHashMap<>();

  public QueueDepthProbe(AmqpAdmin rabbitAdmin) {
    this.rabbitAdmin = rabbitAdmin;
  }

  /**
   * Display-only depth with a ~10 s TTL, so a dashboard polling every few seconds costs one broker
   * round trip per queue per window. Decisions must use {@link #depth}.
   */
  public Optional<Long> cachedDepth(String queue) {
    long now = System.currentTimeMillis();
    CachedDepth hit = cache.get(queue);
    if (hit != null && now - hit.atMillis() < CACHE_TTL_MS) {
      return hit.value();
    }
    Optional<Long> fresh = depth(queue);
    cache.put(queue, new CachedDepth(fresh, now));
    return fresh;
  }

  /** The queue's message count right now; empty when unknown. Never throws. */
  public Optional<Long> depth(String queue) {
    try {
      Properties props = rabbitAdmin.getQueueProperties(queue);
      if (props == null) {
        return Optional.empty();
      }
      Object count = props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
      return count instanceof Integer i ? Optional.of((long) i) : Optional.empty();
    } catch (Exception e) {
      log.warn("queue depth probe failed for {}; depth unknown", queue, e);
      return Optional.empty();
    }
  }
}
