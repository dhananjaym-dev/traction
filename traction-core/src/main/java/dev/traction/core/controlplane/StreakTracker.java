package dev.traction.core.controlplane;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Sliding-window failure streaks in Redis, shared by every control-plane instance (so a streak
 * split across several tower consumers still trips).
 *
 * <p>One sorted set per scope and streak category:
 * <pre>
 *   tr:streak:&lt;scopeRef&gt;:&lt;category&gt;   e.g. tr:streak:crm:acme/company-sync:GATE_FAULT
 *     member = the flight's domainRef (flightId when absent), score = epoch ms of the report
 *     TTL    = 2 x the window, refreshed on every increment
 * </pre>
 * Counting members rather than events makes a streak "N distinct records": retries of one bad
 * record never fake a systemic fault.
 *
 * <p>Ephemeral by design: a Redis failure counts 0 (detection pauses, never a false ground stop)
 * and a real streak re-accumulates within minutes.
 */
@Slf4j
public class StreakTracker {

  static final String KEY_PREFIX = "tr:streak:";
  /** Every streak category kept per gate scope; a landing clears them all. */
  static final String[] CATEGORIES = {"GATE_FAULT", "STRUCTURAL_FAULT"};

  private final StringRedisTemplate redis;
  private final LongSupplier nowMillis;

  public StreakTracker(StringRedisTemplate redis) {
    this(redis, System::currentTimeMillis);
  }

  StreakTracker(StringRedisTemplate redis, LongSupplier nowMillis) {
    this.redis = redis;
    this.nowMillis = nowMillis;
  }

  /** Adds {@code member}, drops entries older than {@code window}, returns the distinct count. */
  public long increment(String scopeRef, String category, String member, Duration window) {
    String key = key(scopeRef, category);
    try {
      long now = nowMillis.getAsLong();
      redis.opsForZSet().add(key, member, now);
      redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now - window.toMillis());
      redis.expire(key, window.multipliedBy(2));
      Long count = redis.opsForZSet().zCard(key);
      return count == null ? 0 : count;
    } catch (RuntimeException e) {
      log.warn("streak tracking failed for {}; detection paused (self-heals)", key, e);
      return 0;
    }
  }

  /** Forgets every streak on the scope. */
  public void clear(String scopeRef) {
    try {
      redis.delete(Arrays.stream(CATEGORIES).map(c -> key(scopeRef, c)).toList());
    } catch (RuntimeException e) {
      log.warn("streak clear failed for {} (the keys expire on their own)", scopeRef, e);
    }
  }

  private static String key(String scopeRef, String category) {
    return KEY_PREFIX + scopeRef + ":" + category;
  }
}
