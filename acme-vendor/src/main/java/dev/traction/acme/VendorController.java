package dev.traction.acme;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VendorController {

    private static final long HEALTHY_LATENCY_MS = 20;

    private final ModeStore store;
    private final BooleanSupplier flakyFails;
    private final LongConsumer sleeper;

    @Autowired
    public VendorController(ModeStore store) {
        this(store, () -> ThreadLocalRandom.current().nextBoolean(), VendorController::sleep);
    }

    VendorController(ModeStore store, BooleanSupplier flakyFails, LongConsumer sleeper) {
        this.store = store;
        this.flakyFails = flakyFails;
        this.sleeper = sleeper;
    }

    @PostMapping("/contacts")
    public ResponseEntity<Map<String, Object>> contacts(@RequestBody(required = false) Map<String, Object> body) {
        return call("contacts", body);
    }

    @PostMapping("/companies")
    public ResponseEntity<Map<String, Object>> companies(@RequestBody(required = false) Map<String, Object> body) {
        return call("companies", body);
    }

    @PostMapping("/emails")
    public ResponseEntity<Map<String, Object>> emails(@RequestBody(required = false) Map<String, Object> body) {
        return call("emails", body);
    }

    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> setMode(@RequestBody Map<String, String> body) {
        String endpoint = body.get("endpoint");
        String modeName = body.get("mode");
        ModeStore.Mode mode = modeName == null ? null : ModeStore.Mode.parse(modeName);
        if (endpoint == null || mode == null || !store.set(endpoint, mode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown endpoint or mode"));
        }
        return ResponseEntity.ok(new LinkedHashMap<>(store.snapshot()));
    }

    @GetMapping("/mode")
    public Map<String, String> getMode() {
        return store.snapshot();
    }

    @GetMapping("/stats")
    public Map<String, Map<String, Long>> stats() {
        return store.stats();
    }

    @PostMapping("/stats/reset")
    public void resetStats() {
        store.resetStats();
    }

    private ResponseEntity<Map<String, Object>> call(String endpoint, Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> res = respond(store.mode(endpoint), body);
        store.record(endpoint, res.getStatusCode().value());
        return res;
    }

    private ResponseEntity<Map<String, Object>> respond(ModeStore.Mode mode, Map<String, Object> body) {
        return switch (mode) {
            case HEALTHY -> ok();
            case TOKEN_EXPIRED -> error(HttpStatus.UNAUTHORIZED, "token expired");
            case CONTRACT_BREAK -> error(HttpStatus.UNPROCESSABLE_ENTITY, "unknown field");
            case FLAKY -> flakyFails.getAsBoolean() ? error(HttpStatus.INTERNAL_SERVER_ERROR, "flaky failure") : ok();
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "5").body(Map.of("error", "rate limited"));
            case BAD_PAYLOADS -> body != null && Boolean.TRUE.equals(body.get("bad"))
                    ? error(HttpStatus.BAD_REQUEST, "bad payload") : ok();
        };
    }

    private ResponseEntity<Map<String, Object>> ok() {
        sleeper.accept(HEALTHY_LATENCY_MS);
        return ResponseEntity.ok(Map.of("id", UUID.randomUUID().toString()));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
