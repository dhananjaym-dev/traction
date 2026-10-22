package dev.traction.acme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class VendorControllerTest {

    private final List<Long> sleeps = new ArrayList<>();
    private boolean flakyFails;
    private VendorController controller;

    @BeforeEach
    void setUp() {
        BooleanSupplier coin = () -> flakyFails;
        controller = new VendorController(new ModeStore(), coin, sleeps::add);
    }

    private ResponseEntity<Map<String, Object>> setMode(String endpoint, String mode) {
        return controller.setMode(Map.of("endpoint", endpoint, "mode", mode));
    }

    @Test
    void modeMapsToStatus() {
        // mode, payload, expected status
        Object[][] table = {
            {"healthy", Map.of(), 200},
            {"token_expired", Map.of(), 401},
            {"contract_break", Map.of(), 422},
            {"rate_limited", Map.of(), 429},
            {"bad_payloads", Map.of("bad", true), 400},
            {"bad_payloads", Map.of("bad", false), 200},
        };
        for (Object[] row : table) {
            setMode("contacts", (String) row[0]);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> res = controller.contacts((Map<String, Object>) row[1]);
            assertEquals(row[2], res.getStatusCode().value(), row[0] + " " + row[1]);
        }
    }

    @Test
    void modeSpecificResponseDetails() {
        ResponseEntity<Map<String, Object>> healthy = controller.contacts(Map.of());
        assertNotNull(healthy.getBody().get("id"));
        assertEquals(List.of(20L), sleeps);

        setMode("contacts", "rate_limited");
        assertEquals("5", controller.contacts(Map.of()).getHeaders().getFirst("Retry-After"));

        setMode("contacts", "contract_break");
        assertEquals(Map.of("error", "unknown field"), controller.contacts(Map.of()).getBody());
    }

    @Test
    void flakyFollowsInjectedCoin() {
        setMode("emails", "flaky");
        flakyFails = true;
        assertEquals(500, controller.emails(Map.of()).getStatusCode().value());
        flakyFails = false;
        assertEquals(200, controller.emails(Map.of()).getStatusCode().value());
    }

    @Test
    void endpointsAreIsolated() {
        setMode("contacts", "token_expired");

        assertEquals(401, controller.contacts(Map.of()).getStatusCode().value());
        assertEquals(200, controller.companies(Map.of()).getStatusCode().value());
        assertEquals(200, controller.emails(Map.of()).getStatusCode().value());
        assertEquals(
                Map.of("contacts", "token_expired", "companies", "healthy", "emails", "healthy"),
                controller.getMode());
    }

    @Test
    void modeTransitionsAndAll() {
        setMode("contacts", "rate_limited");
        assertEquals(429, controller.contacts(Map.of()).getStatusCode().value());

        setMode("contacts", "healthy");
        assertEquals(200, controller.contacts(Map.of()).getStatusCode().value());

        setMode("all", "contract_break");
        assertEquals(
                Map.of("contacts", "contract_break", "companies", "contract_break", "emails", "contract_break"),
                controller.getMode());
        assertEquals(422, controller.companies(Map.of()).getStatusCode().value());
    }

    @Test
    void unknownEndpointOrModeIsRejectedAndChangesNothing() {
        assertEquals(400, setMode("leads", "healthy").getStatusCode().value());
        assertEquals(400, setMode("contacts", "on_fire").getStatusCode().value());
        assertEquals(400, controller.setMode(Map.of("mode", "healthy")).getStatusCode().value());
        assertTrue(controller.getMode().values().stream().allMatch("healthy"::equals));
    }

    @Test
    void statsCountPerEndpointAndReset() {
        controller.contacts(Map.of());
        setMode("contacts", "token_expired");
        controller.contacts(Map.of());
        controller.contacts(Map.of());
        controller.emails(Map.of());

        Map<String, Map<String, Long>> stats = controller.stats();
        assertEquals(Map.of("ok", 1L, "failed", 2L), stats.get("contacts"));
        assertEquals(Map.of("ok", 0L, "failed", 0L), stats.get("companies"));
        assertEquals(Map.of("ok", 1L, "failed", 0L), stats.get("emails"));

        controller.resetStats();
        assertEquals(Map.of("ok", 0L, "failed", 0L), controller.stats().get("contacts"));
        assertEquals("token_expired", controller.getMode().get("contacts"));
    }
}
