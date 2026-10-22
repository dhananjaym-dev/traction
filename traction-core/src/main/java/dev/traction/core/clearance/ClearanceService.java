package dev.traction.core.clearance;

import dev.traction.core.api.SlotsConfig;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Clearance: the shared rate-limit token acquire.
 *
 * <p>One atomic Lua call against the airport's single bucket {@code tr:slots:<airportId>}, so
 * every pod draws from the same tokens. A flight draws {@code cost} slots (its gate's
 * {@code slotCost}). The script is deliberately plain Lua rather than a JVM library so non-JVM
 * clients can share the same bucket.
 *
 * <p>A single non-blocking attempt: the caller decides what to do with a denial (sleep, ground
 * delay, or defer the flight to the runway tail). Redis failures propagate instead of
 * granting, so an outage can never bypass the limit; the caller defers the flight.
 */
public class ClearanceService {

  static final String KEY_PREFIX = "tr:slots:";
  private static final long MILLI_PER_SLOT = 1000L;

  private final StringRedisTemplate redis;
  private final RedisScript<List> acquireScript;

  public ClearanceService(StringRedisTemplate redis) {
    this.redis = redis;
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setScriptSource(new ResourceScriptSource(new ClassPathResource("traction/clearance.lua")));
    script.setResultType(List.class);
    this.acquireScript = script;
  }

  /**
   * Draws {@code cost} slots from the airport's shared bucket.
   *
   * @throws IllegalArgumentException if cost is below 1 or above the bucket's burst (it could
   *     never be granted)
   * @throws IllegalStateException if the bucket cannot be configured
   * @throws RuntimeException on Redis failure (never treated as a grant)
   */
  public ClearanceResult acquire(String airportId, SlotsConfig slots, int cost) {
    if (cost < 1 || cost > slots.burst()) {
      throw new IllegalArgumentException(
          "cost must be between 1 and burst (" + slots.burst() + "): " + cost);
    }
    String key = KEY_PREFIX + airportId;
    String weight = String.valueOf(cost * MILLI_PER_SLOT);
    List<?> result = eval(key, weight);
    if (isUnconfigured(result)) {
      configure(key, slots);
      result = eval(key, weight);
      if (isUnconfigured(result)) {
        throw new IllegalStateException("clearance bucket for " + airportId
            + " is still unconfigured after configure");
      }
    }
    if (((Number) result.get(0)).longValue() == 1L) {
      return ClearanceResult.cleared();
    }
    return ClearanceResult.denied(((Number) result.get(1)).longValue());
  }

  private List<?> eval(String key, String weight) {
    List<?> result = redis.execute(acquireScript, List.of(key), weight);
    if (result == null || result.size() < 2) {
      throw new IllegalStateException("unexpected clearance script result: " + result);
    }
    return result;
  }

  private static boolean isUnconfigured(List<?> result) {
    return ((Number) result.get(0)).longValue() == -1L;
  }

  /**
   * Writes ONLY config fields, never tokens/last_refill, so configuring never resets shared
   * bucket state.
   */
  private void configure(String key, SlotsConfig slots) {
    long capacityMilli = slots.burst() * MILLI_PER_SLOT;
    long refillPerSecMilli = Math.max(1L,
        Math.round(slots.limit() * 1_000_000.0d / slots.window().toMillis()));
    redis.opsForHash().putAll(key, Map.of(
        "capacity_milli", String.valueOf(capacityMilli),
        "refill_per_sec_milli", String.valueOf(refillPerSecMilli)));
  }
}
