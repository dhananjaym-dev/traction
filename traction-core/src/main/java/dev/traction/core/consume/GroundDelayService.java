package dev.traction.core.consume;

import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Ground delay: a timed, self-expiring pause for an airport (e.g. after a rate-limit response).
 *
 * <p>This service owns only the Redis TTL marker {@code tr:grounddelay:<airportId>}. Pausing
 * the container belongs to the caller. Consumers sleep through the marker in slices and
 * re-check between slices, so the sleeping listener thread is the resume timer and no leader
 * election is needed. The marker value is the intended resume epoch-millis (authoritative for
 * {@link #remaining}); the key's TTL is the backstop that removes it.
 */
@Slf4j
public class GroundDelayService {

  static final String KEY_PREFIX = "tr:grounddelay:";

  private final StringRedisTemplate redis;

  public GroundDelayService(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** Writes the cooldown marker only; the caller pauses its own consumer. */
  public void delay(String airportId, Duration cooldown) {
    try {
      redis.opsForValue().set(KEY_PREFIX + airportId,
          String.valueOf(System.currentTimeMillis() + cooldown.toMillis()), cooldown);
      log.info("airport {} ground-delayed for {}", airportId, cooldown);
    } catch (Exception e) {
      log.warn("ground-delay marker write failed for {}; the caller still pauses and re-checks "
          + "the marker each slice", airportId, e);
    }
  }

  /**
   * Remaining cooldown from the stored resume epoch. Empty when there is no marker, it is
   * unparseable, or Redis errors: callers treat empty as "not delayed" (a wrongly cleared delay
   * just re-triggers on the next rate-limit response).
   */
  public Optional<Duration> remaining(String airportId) {
    try {
      String raw = redis.opsForValue().get(KEY_PREFIX + airportId);
      if (raw == null) {
        return Optional.empty();
      }
      long millis = Long.parseLong(raw) - System.currentTimeMillis();
      return millis <= 0 ? Optional.empty() : Optional.of(Duration.ofMillis(millis));
    } catch (Exception e) {
      log.warn("ground-delay remaining read failed for {}", airportId, e);
      return Optional.empty();
    }
  }
}
