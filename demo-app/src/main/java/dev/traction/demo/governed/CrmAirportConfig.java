package dev.traction.demo.governed;

import static dev.traction.core.api.FlightReport.incident;
import static dev.traction.core.api.FlightReport.landed;
import static dev.traction.core.api.IncidentCategory.AIRPORT_FAULT;
import static dev.traction.core.api.IncidentCategory.FLIGHT_FAULT;
import static dev.traction.core.api.IncidentCategory.GATE_FAULT;
import static dev.traction.core.api.IncidentCategory.INTERNAL;
import static dev.traction.core.api.IncidentCategory.RATE_LIMIT;
import static dev.traction.core.api.IncidentCategory.TRANSIENT;

import dev.traction.core.api.AirportSpec;
import dev.traction.core.api.Evidence;
import dev.traction.core.api.Flight;
import dev.traction.core.api.FlightReport;
import dev.traction.demo.governed.AcmeExceptions.AuthException;
import dev.traction.demo.governed.AcmeExceptions.BadPayloadException;
import dev.traction.demo.governed.AcmeExceptions.ContractException;
import dev.traction.demo.governed.AcmeExceptions.RateLimitedException;
import dev.traction.demo.governed.AcmeExceptions.VendorUnavailableException;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Acme CRM as one airport: one shared rate limit, one credential set, one outage fate. */
@Configuration(proxyBeanMethods = false)
public class CrmAirportConfig {

    public static final String AIRPORT = "crm:acme";
    public static final String CONTACT_SYNC = "contact-sync";
    public static final String COMPANY_SYNC = "company-sync";

    private static final Map<String, String> PATHS =
            Map.of(CONTACT_SYNC, AcmeClient.CONTACTS, COMPANY_SYNC, AcmeClient.COMPANIES);

    private final AcmeClient acme;

    public CrmAirportConfig(AcmeClient acme) {
        this.acme = acme;
    }

    @Bean
    AirportSpec crmAcme() {
        return AirportSpec.of(AIRPORT)
                .slots(20, Duration.ofSeconds(1))   // the shared rate limit
                .gate(CONTACT_SYNC)                 // a gate = one independently stoppable work type
                .gate(COMPANY_SYNC)
                .pilot(this::land)                  // your work
                .reporter(this::report);            // your failure classification
    }

    /** Lands one flight: one Acme call, routed by gate. Never touches the broker. */
    void land(Flight<?> flight) {
        acme.upsert(PATHS.get(flight.gateId()), flight.payload());
    }

    /** Classifies every outcome to the level it breaks. */
    FlightReport report(Flight<?> flight, Throwable t) {
        if (t == null)                               return landed();
        if (t instanceof AuthException)              return incident(AIRPORT_FAULT, Evidence.from(t)); // dead credentials → the whole airport ground-stops
        if (t instanceof ContractException)          return incident(GATE_FAULT, Evidence.from(t));    // this work type is rejected → a streak stops the gate
        if (t instanceof BadPayloadException)        return incident(FLIGHT_FAULT, Evidence.from(t));  // this record's data → rejected, re-drive from source
        if (t instanceof VendorUnavailableException) return incident(TRANSIENT, Evidence.from(t));     // 5xx or timeout → hold with backoff, park past budget
        if (t instanceof RateLimitedException)       return incident(RATE_LIMIT, Evidence.from(t));    // throttled → pace the airport down, never a failure
        return incident(INTERNAL, Evidence.from(t));                                                   // our own bug → hold briefly, then park
    }
}
