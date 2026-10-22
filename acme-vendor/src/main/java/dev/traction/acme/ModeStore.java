package dev.traction.acme;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Per-endpoint failure mode and ok/failed call counters. */
@Component
public class ModeStore {

    public enum Mode {
        HEALTHY, TOKEN_EXPIRED, CONTRACT_BREAK, FLAKY, RATE_LIMITED, BAD_PAYLOADS;

        static Mode parse(String s) {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return null;
            }
        }

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static final List<String> ENDPOINTS = List.of("contacts", "companies", "emails");

    private final Map<String, Mode> modes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> ok = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failed = new ConcurrentHashMap<>();

    public ModeStore() {
        for (String e : ENDPOINTS) {
            modes.put(e, Mode.HEALTHY);
            ok.put(e, new AtomicLong());
            failed.put(e, new AtomicLong());
        }
    }

    Mode mode(String endpoint) {
        return modes.get(endpoint);
    }

    /** Sets one endpoint, or all when endpoint is "all". Returns false if endpoint is unknown. */
    boolean set(String endpoint, Mode mode) {
        if ("all".equals(endpoint)) {
            ENDPOINTS.forEach(e -> modes.put(e, mode));
            return true;
        }
        if (!modes.containsKey(endpoint)) {
            return false;
        }
        modes.put(endpoint, mode);
        return true;
    }

    Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        ENDPOINTS.forEach(e -> out.put(e, modes.get(e).wire()));
        return out;
    }

    void record(String endpoint, int status) {
        (status < 400 ? ok : failed).get(endpoint).incrementAndGet();
    }

    Map<String, Map<String, Long>> stats() {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        ENDPOINTS.forEach(e -> out.put(e, Map.of("ok", ok.get(e).get(), "failed", failed.get(e).get())));
        return out;
    }

    void resetStats() {
        ok.values().forEach(c -> c.set(0));
        failed.values().forEach(c -> c.set(0));
    }
}
