package dev.traction.core.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.report.FlightReportMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The recent incidents per airport, for the admin API and dashboard: every INCIDENT report
 * (RECORD and INTERIM) the tower sees, newest first, bounded.
 * <pre>
 *   tr:incidents:&lt;airportId&gt;   list, LPUSH + LTRIM to the newest 100
 *     element = the (clamped) FlightReportMessage as JSON, in the Traction wire format
 * </pre>
 *
 * <p>Also holds the per-flight incident notification cap, so a flood of incidents cannot become
 * a flood of notifications:
 * <pre>
 *   tr:incident-notify-cap:&lt;airportId&gt;:&lt;disposition&gt;   counter, INCR, TTL 1 h
 * </pre>
 */
@Slf4j
public class IncidentSummaryStore {

  static final String KEY_PREFIX = "tr:incidents:";
  static final int CAP = 100;
  static final String NOTIFY_CAP_PREFIX = "tr:incident-notify-cap:";
  static final long NOTIFY_CAP_PER_WINDOW = 25;
  static final Duration NOTIFY_CAP_WINDOW = Duration.ofHours(1);

  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public IncidentSummaryStore(StringRedisTemplate redis, ObjectMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
  }

  public void record(FlightReportMessage report) {
    String json;
    try {
      json = mapper.writeValueAsString(report);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("incident summary not serializable", e);
    }
    String key = KEY_PREFIX + report.airportId();
    redis.opsForList().leftPush(key, json);
    redis.opsForList().trim(key, 0, CAP - 1);
  }

  /** Up to the newest 100 incidents, newest first; an unreadable entry is skipped. */
  public List<FlightReportMessage> recent(String airportId) {
    List<String> raw = redis.opsForList().range(KEY_PREFIX + airportId, 0, CAP - 1);
    List<FlightReportMessage> out = new ArrayList<>();
    for (String json : raw == null ? List.<String>of() : raw) {
      try {
        out.add(mapper.readValue(json, FlightReportMessage.class));
      } catch (JsonProcessingException e) {
        log.debug("unreadable incident summary skipped for {}", airportId);
      }
    }
    return out;
  }

  /**
   * Whether one more per-flight incident notification fits this hour's cap for the airport and
   * disposition (25). Fails open: with Redis down, notifying beats silence.
   */
  public boolean allowNotify(String airportId, String disposition) {
    String key = NOTIFY_CAP_PREFIX + airportId + ":" + disposition;
    try {
      Long count = redis.opsForValue().increment(key);
      if (count == null) {
        return true;
      }
      // Arm the TTL whenever it is missing, not only on the first INCR: a crash between the two
      // would otherwise cap this airport forever.
      Long ttl = redis.getExpire(key);
      if (ttl == null || ttl < 0) {
        redis.expire(key, NOTIFY_CAP_WINDOW);
      }
      if (count == NOTIFY_CAP_PER_WINDOW + 1) {
        log.warn("{}: FLIGHT_INCIDENT notifications capped at {}/h ({}); suppressing the rest of "
            + "this window", airportId, NOTIFY_CAP_PER_WINDOW, disposition);
      }
      return count <= NOTIFY_CAP_PER_WINDOW;
    } catch (RuntimeException e) {
      log.warn("incident notify cap unavailable for {}; notifying anyway", key, e);
      return true;
    }
  }
}
