package dev.traction.demo.governed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import dev.traction.core.api.Flight;
import dev.traction.core.api.FlightReport;
import dev.traction.core.api.IncidentCategory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Acme's response status, through the client's exception, to the reported category. */
class CrmReporterTest {

    private final CrmAirportConfig crm = new CrmAirportConfig(mock(AcmeClient.class));
    private final Flight<?> flight = new Flight<>(UUID.randomUUID(), "crm:acme", "contact-sync",
            "contact-1", null, 1, 0, "abc123", null, null);

    @ParameterizedTest
    @CsvSource({
        "401, AIRPORT_FAULT",
        "422, GATE_FAULT",
        "400, FLIGHT_FAULT",
        "503, TRANSIENT",
        "429, RATE_LIMIT",
        "404, INTERNAL",
    })
    void classifiesEachStatusToTheLevelItBreaks(int status, IncidentCategory expected) {
        Throwable error = AcmeClient.failure(status, "{\"error\":\"x\"}");

        FlightReport.Incident incident = (FlightReport.Incident) crm.report(flight, error);

        assertEquals(expected, incident.category());
        assertEquals(error.getClass().getSimpleName(), incident.evidence().code());
    }

    @Test
    void noErrorIsLanded() {
        assertEquals(FlightReport.landed(), crm.report(flight, null));
    }
}
