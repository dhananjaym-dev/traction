package dev.traction.core.charter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * An in-memory Redis behind a mocked {@link StringRedisTemplate}, so the charter tests run the
 * real Java accounting end to end. The two charter scripts are modelled here line for line
 * ({@code traction/charter_open.lua}, {@code traction/charter_tally.lua}); the scripts
 * themselves were exercised against a real Redis by hand. Expiry is not simulated: TTLs are
 * recorded, and {@link #expire} drops a key as if its TTL had run out.
 */
final class CharterRedisModel {

  final Map<String, Map<String, String>> hashes = new HashMap<>();
  final Map<String, Set<String>> sets = new HashMap<>();
  final Map<String, String> strings = new HashMap<>();
  final Map<String, Map<String, Double>> zsets = new HashMap<>();
  final Map<String, Duration> ttls = new HashMap<>();
  /** Every operation throws while true, like an unreachable Redis. */
  boolean down;

  final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  private final String openSha = sha("traction/charter_open.lua");
  private final String tallySha = sha("traction/charter_tally.lua");

  @SuppressWarnings({"unchecked", "rawtypes"})
  CharterRedisModel() {
    HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn((HashOperations) hashOps);
    when(hashOps.entries(anyString())).thenAnswer(inv -> {
      up();
      return new HashMap<Object, Object>(hashes.getOrDefault(inv.<String>getArgument(0), Map.of()));
    });

    SetOperations<String, String> setOps = mock(SetOperations.class);
    when(redis.opsForSet()).thenReturn(setOps);
    when(setOps.members(anyString())).thenAnswer(inv -> {
      up();
      return new HashSet<>(sets.getOrDefault(inv.<String>getArgument(0), Set.of()));
    });

    ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    doAnswer(inv -> {
      up();
      strings.put(inv.getArgument(0), inv.getArgument(1));
      ttls.put(inv.getArgument(0), inv.getArgument(2));
      return null;
    }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

    when(redis.hasKey(anyString())).thenAnswer(inv -> {
      up();
      String key = inv.getArgument(0);
      return strings.containsKey(key) || hashes.containsKey(key) || sets.containsKey(key);
    });

    ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);
    when(redis.opsForZSet()).thenReturn(zsetOps);
    when(zsetOps.reverseRange(anyString(), anyLong(), anyLong())).thenAnswer(inv -> {
      up();
      List<Map.Entry<String, Double>> entries =
          new ArrayList<>(zsets.getOrDefault(inv.<String>getArgument(0), Map.of()).entrySet());
      entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());
      Set<String> members = new LinkedHashSet<>();
      entries.forEach(e -> members.add(e.getKey()));
      return members;
    });
    when(zsetOps.remove(anyString(), any(Object[].class))).thenAnswer(inv -> {
      up();
      Map<String, Double> zset = zsets.getOrDefault(inv.<String>getArgument(0), new HashMap<>());
      long removed = 0;
      for (Object member : (Object[]) inv.getRawArguments()[1]) {
        removed += zset.remove(member) == null ? 0 : 1;
      }
      return removed;
    });

    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(inv -> {
      up();
      RedisScript<?> script = inv.getArgument(0);
      List<String> keys = inv.getArgument(1);
      Object[] args = (Object[]) inv.getRawArguments()[2];
      String[] argv = new String[args.length];
      for (int i = 0; i < args.length; i++) {
        argv[i] = String.valueOf(args[i]);
      }
      if (script.getSha1().equals(openSha)) {
        return open(keys, argv);
      }
      if (script.getSha1().equals(tallySha)) {
        return tally(keys, argv);
      }
      throw new IllegalStateException("unmodelled script");
    });
  }

  /** Drops {@code key} as if its TTL had run out. */
  void expire(String key) {
    hashes.remove(key);
    sets.remove(key);
    strings.remove(key);
    zsets.remove(key);
  }

  Map<String, String> hash(String key) {
    return hashes.getOrDefault(key, Map.of());
  }

  // ---- traction/charter_open.lua ----

  private String open(List<String> k, String[] a) {
    Map<String, String> h = hashes.get(k.get(0));
    String status = h == null ? null : h.get("status");
    if (status != null) {
      return "OPEN".equals(status) ? "EXISTS" : "CLOSED";
    }
    if (strings.containsKey(k.get(4))) {
      return "CLOSED";
    }
    if ("1".equals(a[6])) {
      String holder = strings.get(k.get(2));
      if (holder != null && !holder.equals(a[0])) {
        return "DENIED:" + holder;
      }
      strings.put(k.get(2), a[0]);
    }
    Map<String, String> created = hashes.computeIfAbsent(k.get(0), x -> new HashMap<>());
    created.putAll(Map.of("airportId", a[2], "gateId", a[3], "charterId", a[4], "expected", a[5],
        "status", "OPEN", "openedAt", a[1], "lastProgressAt", a[1]));
    for (String bucket : BUCKETS) {
      created.put(bucket, "0");
    }
    sets.computeIfAbsent(k.get(1), x -> new HashSet<>()).add(a[0]);
    zsets.computeIfAbsent(k.get(3), x -> new HashMap<>()).put(a[0], Double.parseDouble(a[1]));
    return "OPENED";
  }

  // ---- traction/charter_tally.lua ----

  private static final List<String> BUCKETS =
      List.of("landed", "rejected", "skipped", "parked", "discarded");

  private long tally(List<String> k, String[] a) {
    String hKey = k.get(0);
    String seenKey = k.get(1);
    String openSet = k.get(2);
    String admission = k.get(3);
    String op = a[0];
    String ref = a[1];
    String now = a[2];
    Duration ttl = Duration.ofSeconds(Long.parseLong(a[3]));
    Map<String, String> h = hashes.get(hKey);
    if (h == null) {
      if ("abandon".equals(op)) {
        sets.getOrDefault(openSet, new HashSet<>()).remove(ref);
        if (ref.equals(strings.get(admission))) {
          strings.remove(admission);
        }
        return 1;
      }
      return -1;
    }
    switch (op) {
      case "opened" -> {
        long changed = h.putIfAbsent("openNotifiedAt", now) == null ? 1 : 0;
        return changed + maybeComplete(h, hKey, seenKey, openSet, admission, ref, now, ttl);
      }
      case "move" -> {
        String flight = a[4];
        String from = a[5];
        String to = a[6];
        Map<String, String> seen = hashes.computeIfAbsent(seenKey, x -> new HashMap<>());
        if (!seen.getOrDefault(flight, "").equals(from)) {
          if (seen.isEmpty()) {
            hashes.remove(seenKey);
          }
          return 0;
        }
        if (to.isEmpty()) {
          seen.remove(flight);
        } else {
          seen.put(flight, to);
        }
        if (!from.isEmpty()) {
          h.merge(from, "-1", (x, y) -> String.valueOf(Long.parseLong(x) + Long.parseLong(y)));
        }
        if (!to.isEmpty()) {
          h.merge(to, "1", (x, y) -> String.valueOf(Long.parseLong(x) + Long.parseLong(y)));
        }
        h.put("lastProgressAt", now);
        if (ttls.containsKey(hKey)) {
          ttls.put(seenKey, ttls.get(hKey));
        }
        return 1 + maybeComplete(h, hKey, seenKey, openSet, admission, ref, now, ttl);
      }
      case "abort" -> {
        long changed = 0;
        if (h.putIfAbsent("abortedAt", now) == null) {
          h.put("abortedBy", a[4]);
          changed = 1;
        }
        return changed + maybeComplete(h, hKey, seenKey, openSet, admission, ref, now, ttl);
      }
      case "complete" -> {
        if (!"OPEN".equals(h.get("status"))) {
          return 0;
        }
        return 1 + complete(h, hKey, seenKey, openSet, admission, ref, now, ttl, a[4]);
      }
      case "abandon" -> {
        return 0;
      }
      default -> throw new IllegalArgumentException(op);
    }
  }

  private long maybeComplete(Map<String, String> h, String hKey, String seenKey, String openSet,
      String admission, String ref, String now, Duration ttl) {
    if (!"OPEN".equals(h.get("status")) || h.get("expected") == null) {
      return 0;
    }
    long accounted = 0;
    for (String bucket : BUCKETS) {
      accounted += Long.parseLong(h.getOrDefault(bucket, "0"));
    }
    return accounted >= Long.parseLong(h.get("expected"))
        ? complete(h, hKey, seenKey, openSet, admission, ref, now, ttl,
            h.containsKey("abortedAt") ? "ABORT" : "LATCH") : 0;
  }

  private long complete(Map<String, String> h, String hKey, String seenKey, String openSet,
      String admission, String ref, String now, Duration ttl, String source) {
    h.put("status", h.containsKey("abortedAt") ? "ABORTED" : "COMPLETED");
    h.put("completedAt", now);
    h.put("source", source);
    sets.getOrDefault(openSet, new HashSet<>()).remove(ref);
    if (ref.equals(strings.get(admission))) {
      strings.remove(admission);
    }
    ttls.put(hKey, ttl);
    if (hashes.containsKey(seenKey)) {
      ttls.put(seenKey, ttl);
    }
    return 2;
  }

  private void up() {
    if (down) {
      throw new RedisConnectionFailureException("redis down");
    }
  }

  private static String sha(String resource) {
    DefaultRedisScript<Object> script = new DefaultRedisScript<>();
    script.setScriptSource(new ResourceScriptSource(new ClassPathResource(resource)));
    return script.getSha1();
  }
}
