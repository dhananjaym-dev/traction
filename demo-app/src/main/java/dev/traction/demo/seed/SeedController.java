package dev.traction.demo.seed;

import dev.traction.core.api.CharterConcurrencyException;
import dev.traction.core.api.CharterDesk;
import dev.traction.core.api.DispatchOptions;
import dev.traction.core.api.Tower;
import dev.traction.demo.governed.AcmeClient.Company;
import dev.traction.demo.governed.AcmeClient.Contact;
import dev.traction.demo.governed.AcmeClient.WelcomeEmail;
import dev.traction.demo.governed.CrmAirportConfig;
import dev.traction.demo.governed.MailAirportConfig;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Demo traffic: dispatches synthetic flights through the {@link Tower}. Every seed returns its counts as JSON. */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private final Tower tower;
    private final CharterDesk charterDesk;
    private final AtomicLong sequence = new AtomicLong();

    public SeedController(Tower tower, CharterDesk charterDesk) {
        this.tower = tower;
        this.charterDesk = charterDesk;
    }

    /** {@code count} contact-sync flights; the first {@code bad} carry {@code bad=true}. */
    @PostMapping("/contacts")
    public Map<String, Object> contacts(@RequestParam int count, @RequestParam(defaultValue = "0") int bad) {
        requireCounts(count, bad);
        for (int i = 0; i < count; i++) {
            String id = "contact-" + sequence.incrementAndGet();
            tower.dispatch(CrmAirportConfig.AIRPORT, CrmAirportConfig.CONTACT_SYNC,
                    new Contact(id, id + "@example.com", i < bad), new DispatchOptions(id, null));
        }
        return json("dispatched", count, "bad", Math.min(bad, count));
    }

    /** {@code count} company-sync flights. */
    @PostMapping("/companies")
    public Map<String, Object> companies(@RequestParam int count) {
        requireCounts(count, 0);
        for (int i = 0; i < count; i++) {
            String id = "company-" + sequence.incrementAndGet();
            tower.dispatch(CrmAirportConfig.AIRPORT, CrmAirportConfig.COMPANY_SYNC,
                    new Company(id, id + ".example.com", false), new DispatchOptions(id, null));
        }
        return json("dispatched", count);
    }

    /** Opens a charter on mail:acme/welcome for {@code count} flights, then dispatches them on it. */
    @PostMapping("/mailCharter")
    public Map<String, Object> mailCharter(@RequestParam int count) {
        requireCounts(count, 0);
        String charterRef = charterDesk.open(MailAirportConfig.AIRPORT, MailAirportConfig.WELCOME,
                "run-" + Instant.now().getEpochSecond(), count);
        for (int i = 0; i < count; i++) {
            String id = "welcome-" + sequence.incrementAndGet();
            tower.dispatch(MailAirportConfig.AIRPORT, MailAirportConfig.WELCOME,
                    new WelcomeEmail(id, id + "@example.com", false), new DispatchOptions(id, charterRef));
        }
        return json("charterRef", charterRef, "dispatched", count);
    }

    /** A second run while one is open on the gate (PER_KEY admission). */
    @ExceptionHandler(CharterConcurrencyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> runAlreadyOpen(CharterConcurrencyException e) {
        return json("error", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        return json("error", e.getMessage());
    }

    private static void requireCounts(int count, int bad) {
        if (count < 0 || bad < 0) {
            throw new IllegalArgumentException("count and bad must not be negative");
        }
    }

    private static Map<String, Object> json(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }
}
