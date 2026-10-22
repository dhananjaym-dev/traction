package dev.traction.demo.governed;

import dev.traction.core.api.AirportSpec;
import dev.traction.core.api.CharterConcurrency;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Acme's mail API as its own airport (its own quota and outage fate). Welcome emails go out as
 * charters, one run at a time per gate. Same vendor as the CRM, so it shares the CRM's reporter.
 */
@Configuration(proxyBeanMethods = false)
public class MailAirportConfig {

    public static final String AIRPORT = "mail:acme";
    public static final String WELCOME = "welcome";

    @Bean
    AirportSpec mailAcme(AcmeClient acme, CrmAirportConfig crm) {
        return AirportSpec.of(AIRPORT)
                .slots(300, Duration.ofMinutes(1))
                .gate(WELCOME)
                .charterConcurrency(CharterConcurrency.PER_KEY)
                .pilot(flight -> acme.upsert(AcmeClient.EMAILS, flight.payload()))
                .reporter(crm::report);
    }
}
