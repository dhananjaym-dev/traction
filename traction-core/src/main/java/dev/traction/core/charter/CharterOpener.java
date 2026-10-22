package dev.traction.core.charter;

import dev.traction.core.api.AirportSpec;
import dev.traction.core.api.CharterConcurrency;
import dev.traction.core.api.CharterConcurrencyException;
import dev.traction.core.api.CharterDesk;
import dev.traction.core.registry.AirportRegistry;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * The work behind {@link CharterDesk#open}, in the opening (data-plane) process:
 * <ol>
 *   <li>validate the airport and gate against the registry, the charterId and the expected count;</li>
 *   <li>one atomic script ({@code traction/charter_open.lua}): under
 *       {@link CharterConcurrency#PER_KEY} take the gate's admission key or be denied, then write
 *       the charter hash, the open set and the airport index;</li>
 *   <li>publish OPENED to the tower queue (confirmed), so the control plane notifies
 *       {@code CHARTER_OPENED}.</li>
 * </ol>
 * The charter exists in Redis before the caller dispatches a single flight, so no report can
 * reach the control plane ahead of its charter. Admission is exact: the key is held for exactly
 * as long as the charter is open, and the completion (organic, sweep or abort) releases it.
 *
 * <p>Redis failures propagate: a charter cannot exist without Redis. A failed OPENED publish
 * propagates too, but the charter stays written, so the caller's retry with the same charterId
 * is an idempotent re-open that publishes OPENED again. A run that is never retried or dispatched
 * stays open until the sweep (or an abort) closes it.
 */
@Slf4j
public class CharterOpener {

  private final AirportRegistry registry;
  private final StringRedisTemplate redis;
  private final CharterEventPublisher events;
  private final RedisScript<String> openScript;

  public CharterOpener(AirportRegistry registry, StringRedisTemplate redis,
      CharterEventPublisher events) {
    this.registry = registry;
    this.redis = redis;
    this.events = events;
    DefaultRedisScript<String> script = new DefaultRedisScript<>();
    script.setScriptSource(new ResourceScriptSource(new ClassPathResource("traction/charter_open.lua")));
    script.setResultType(String.class);
    this.openScript = script;
  }

  /** See {@link CharterDesk#open}. */
  public String open(String airportId, String gateId, String charterId, long expected) {
    Map<String, AirportSpec.Gate> gates = registry.gatesOf(airportId)
        .orElseThrow(() -> new IllegalArgumentException("unknown airport: " + airportId));
    if (gateId == null || !gates.containsKey(gateId)) {
      throw new IllegalArgumentException("unknown gate '" + gateId + "' on airport " + airportId);
    }
    if (expected < 0) {
      throw new IllegalArgumentException("expected must be >= 0 but was " + expected);
    }
    String ref = CharterKeys.composeRef(airportId, gateId, charterId);
    boolean perKey = registry.charterConcurrencyOf(airportId).orElse(CharterConcurrency.NONE)
        == CharterConcurrency.PER_KEY;
    String scopeKey = CharterKeys.scopeOf(ref);

    String result = redis.execute(openScript,
        List.of(CharterKeys.key(ref), CharterKeys.OPEN_SET, CharterKeys.admissionKey(scopeKey),
            CharterKeys.airportIndexKey(airportId), CharterKeys.abortedKey(ref)),
        ref, String.valueOf(System.currentTimeMillis()), airportId, gateId, charterId,
        String.valueOf(expected), perKey ? "1" : "0");
    if (result != null && result.startsWith("DENIED:")) {
      throw new CharterConcurrencyException(result.substring("DENIED:".length()), scopeKey);
    }
    if ("CLOSED".equals(result)) {
      throw new IllegalArgumentException("charter " + ref + " already ran; open a new charterId");
    }
    if (!"OPENED".equals(result) && !"EXISTS".equals(result)) {
      throw new IllegalStateException("unexpected charter open result for " + ref + ": " + result);
    }
    events.publish(CharterEventMessage.opened(ref, airportId, gateId, expected));
    log.info("charter {} {} (expected {})", ref, "OPENED".equals(result) ? "opened" : "re-opened",
        expected);
    return ref;
  }
}
