package dev.traction.core.clearance;

import dev.traction.core.api.SlotsConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Each acquire first reconciles the bucket's config with the caller's {@link SlotsConfig}.
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
  private final Map<String, SlotsConfig> applied = new ConcurrentHashMap<>();

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
   * @throws IllegalStateException if the bucket is unconfigured when the script runs
   * @throws RuntimeException on Redis failure (never treated as a grant)
   */
  public ClearanceResult acquire(String airportId, SlotsConfig slots, int cost) {
    if (cost < 1 || cost > slots.burst()) {
      throw new IllegalArgumentException(
          "cost must be between 1 and burst (" + slots.burst() + "): " + cost);
    }
    String key = KEY_PREFIX + airportId;
    reconcile(airportId, key, slots);
    List<?> result = eval(key, String.valueOf(cost * MILLI_PER_SLOT));
    if (((Number) result.get(0)).longValue() == -1L) {
      // The hash vanished between reconcile and eval (flush/eviction). Fail closed and make the
      // next acquire write the config again.
      applied.remove(airportId);
      throw new IllegalStateException("clearance bucket for " + airportId + " is unconfigured");
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

  /**
   * Makes the shared bucket's config match {@code slots}: on the first acquire for an airport in
   * this JVM, and whenever the config differs from the last one applied. The hash outlives
   * redeploys, so without this a stale capacity (e.g. burst 2, gate cost 5) would deny forever.
   * Writes ONLY config fields, never tokens/last_refill, so it never resets shared bucket state.
   * A Redis failure propagates and leaves the config unapplied, so the next acquire retries.
   */
  private void reconcile(String airportId, String key, SlotsConfig slots) {
    if (slots.equals(applied.get(airportId))) {
      return;
    }
    long capacityMilli = slots.burst() * MILLI_PER_SLOT;
    long refillPerSecMilli = Math.max(1L,
        Math.round(slots.limit() * 1_000_000.0d / slots.window().toMillis()));
    redis.opsForHash().putAll(key, Map.of(
        "capacity_milli", String.valueOf(capacityMilli),
        "refill_per_sec_milli", String.valueOf(refillPerSecMilli)));
    applied.put(airportId, slots);
  }
}
