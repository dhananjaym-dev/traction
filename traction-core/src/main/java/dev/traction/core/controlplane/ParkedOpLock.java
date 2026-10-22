package dev.traction.core.controlplane;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Per-airport single-flight guard for the parked-queue operations. Replay and discard share this
 * one lock ({@code SET NX} with a TTL on {@code tr:parked-op:<airportId>}), so they never run
 * concurrently on the same airport, across processes. The value is a random owner token and
 * release deletes the key only if the token still matches, so a slow operation whose lock has
 * expired can never free a newer operation's lock. The TTL is only the crash backstop.
 *
 * <p>Unlike the status reads, this lock fails closed: if Redis cannot be reached the exception
 * propagates and the operation does not run, because discard is destructive.
 */
@Slf4j
public class ParkedOpLock {

  static final String KEY_PREFIX = "tr:parked-op:";
  static final Duration TTL = Duration.ofMinutes(15);

  private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
      "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
      Long.class);

  private final StringRedisTemplate redis;

  public ParkedOpLock(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** The owner token when this caller now holds the airport's lock, or null when another op does. */
  public String acquire(String airportId) {
    String token = UUID.randomUUID().toString();
    return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(airportId), token, TTL)) ? token : null;
  }

  /** Frees the lock if {@code token} still owns it; a Redis failure is logged and left to the TTL. */
  public void release(String airportId, String token) {
    try {
      redis.execute(RELEASE, List.of(key(airportId)), token);
    } catch (RuntimeException e) {
      log.warn("parked-op lock release failed for {}; the TTL will clear it", airportId, e);
    }
  }

  private static String key(String airportId) {
    return KEY_PREFIX + airportId;
  }
}
