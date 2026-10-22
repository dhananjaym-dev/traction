package dev.traction.core.status;

import dev.traction.core.api.AirportStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Data-plane status reads: whether an airport or gate may consume. No control plane needed.
 *
 * <p><b>Key shape (shared contract with the control plane's StatusStore, which writes it):</b>
 * one Redis hash per scope, no TTL:
 * <pre>
 *   tr:status:&lt;airportId&gt;           airport scope, e.g. tr:status:crm:acme
 *   tr:status:&lt;airportId&gt;/&lt;gateId&gt;  gate scope,    e.g. tr:status:crm:acme/company
 *     field "status" = an {@link AirportStatus} name (OPEN | GROUND_STOP | CLOSED)
 * </pre>
 * Other fields in the hash (reason, since, ...) belong to the writer and are ignored here.
 *
 * <p>An absent key or field is OPEN: a scope that never changed status has no key. A Redis
 * failure or unknown value also reads OPEN. With Redis down the flight path is already stopped
 * by clearance (which fails closed), so failing closed here would only change which error the
 * operator sees.
 */
@Slf4j
public class StatusReader {

  public static final String KEY_PREFIX = "tr:status:";
  public static final String STATUS_FIELD = "status";

  private final StringRedisTemplate redis;

  public StatusReader(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** The hash key holding {@code scope}'s status. */
  public static String key(ScopeRef scope) {
    return KEY_PREFIX + scope.value();
  }

  public AirportStatus get(ScopeRef scope) {
    try {
      Object value = redis.opsForHash().get(key(scope), STATUS_FIELD);
      return value == null ? AirportStatus.OPEN : AirportStatus.valueOf(value.toString());
    } catch (Exception e) {
      log.warn("status read failed for {}; treating it as OPEN", scope.value(), e);
      return AirportStatus.OPEN;
    }
  }
}
