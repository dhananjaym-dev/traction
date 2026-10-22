package dev.traction.core.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.registry.AirportAnnouncement;
import dev.traction.core.registry.AirportAnnouncement.GateAnnouncement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Projects airport announcements (from the tower queue) into Redis, the fleet the dashboard and
 * admin API list. Announcements are the only writer, so the control plane never needs an
 * airport's code to describe it.
 * <pre>
 *   tr:airports              set of announced airport ids
 *   tr:airport:&lt;airportId&gt;   hash
 *     owningProcess        the announcing application (spring.application.name)
 *     displayName          absent when the spec has none
 *     slotsLimit, slotsWindowSeconds, slotsBurst   decimal strings, absent when not announced
 *     gates                JSON array [{"gateId":"contact-sync","slotCost":1}, ...] in spec order
 *     announcedAt          epoch ms of the announcement
 * </pre>
 *
 * <p>Idempotent upsert: re-announcing overwrites the fields (a gate dropped from the spec drops
 * from {@code gates}). Ownership guard: the first announcing process claims the airport
 * (HSETNX {@code owningProcess}); an announcement from a different process is rejected with a
 * WARN, which catches two deployments accidentally sharing an airport id (an accident guard,
 * not a security boundary: the broker is one trust domain).
 */
@Slf4j
public class AnnouncementProjector {

  static final String AIRPORTS_KEY = "tr:airports";
  static final String AIRPORT_PREFIX = "tr:airport:";

  private static final TypeReference<List<GateAnnouncement>> GATES = new TypeReference<>() { };

  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public AnnouncementProjector(StringRedisTemplate redis, ObjectMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
  }

  public void project(AirportAnnouncement a) {
    if (a.airportId() == null || a.airportId().isBlank() || a.owningProcess() == null) {
      log.warn("announcement without airportId/owningProcess dropped: {}", a);
      return;
    }
    String key = AIRPORT_PREFIX + a.airportId();
    if (!Boolean.TRUE.equals(redis.opsForHash().putIfAbsent(key, "owningProcess", a.owningProcess()))) {
      Object owner = redis.opsForHash().get(key, "owningProcess");
      if (!a.owningProcess().equals(owner)) {
        log.warn("announcement for {} from {} rejected: the airport is owned by {}",
            a.airportId(), a.owningProcess(), owner);
        return;
      }
    }
    Map<String, String> fields = new LinkedHashMap<>();
    List<String> absent = new ArrayList<>();
    put(fields, absent, "displayName", a.displayName());
    put(fields, absent, "slotsLimit", a.slotsLimit());
    put(fields, absent, "slotsWindowSeconds", a.slotsWindowSeconds());
    put(fields, absent, "slotsBurst", a.slotsBurst());
    fields.put("gates", json(a.gates().stream().filter(g -> g.gateId() != null).toList()));
    fields.put("announcedAt", String.valueOf(a.epochMillis()));
    redis.opsForHash().putAll(key, fields);
    if (!absent.isEmpty()) {
      redis.opsForHash().delete(key, absent.toArray());
    }
    redis.opsForSet().add(AIRPORTS_KEY, a.airportId());
    log.info("airport {} declared by {} ({} gates)", a.airportId(), a.owningProcess(), a.gates().size());
  }

  /** Every announced airport id, sorted. */
  public Set<String> airportIds() {
    Set<String> ids = redis.opsForSet().members(AIRPORTS_KEY);
    return ids == null ? Set.of() : new TreeSet<>(ids);
  }

  /**
   * The projected declaration as an announcement ({@code announcementId} is not kept, so it is
   * null); empty when the airport was never announced.
   */
  public Optional<AirportAnnouncement> declaration(String airportId) {
    Map<Object, Object> hash = redis.opsForHash().entries(AIRPORT_PREFIX + airportId);
    if (hash == null || hash.isEmpty()) {
      return Optional.empty();
    }
    List<GateAnnouncement> gates;
    try {
      Object raw = hash.get("gates");
      gates = raw == null ? List.of() : mapper.readValue(raw.toString(), GATES);
    } catch (JsonProcessingException e) {
      log.warn("unreadable gates for airport {}; shown without gates", airportId);
      gates = List.of();
    }
    Long announcedAt = toLong(hash.get("announcedAt"));
    return Optional.of(new AirportAnnouncement(null, airportId, str(hash.get("owningProcess")),
        str(hash.get("displayName")), toInt(hash.get("slotsLimit")),
        toLong(hash.get("slotsWindowSeconds")), toInt(hash.get("slotsBurst")), gates,
        announcedAt == null ? 0L : announcedAt));
  }

  private static void put(Map<String, String> fields, List<String> absent, String name, Object v) {
    if (v == null) {
      absent.add(name);
    } else {
      fields.put(name, v.toString());
    }
  }

  private String json(List<GateAnnouncement> gates) {
    try {
      return mapper.writeValueAsString(gates);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("gates not serializable", e);
    }
  }

  private static String str(Object v) {
    return v == null ? null : v.toString();
  }

  private static Integer toInt(Object v) {
    Long l = toLong(v);
    return l == null ? null : l.intValue();
  }

  private static Long toLong(Object v) {
    try {
      return v == null ? null : Long.parseLong(v.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
