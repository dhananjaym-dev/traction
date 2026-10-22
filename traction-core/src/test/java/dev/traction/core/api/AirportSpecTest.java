package dev.traction.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AirportSpecTest {

  private static final Pilot<Object> NOOP_PILOT = flight -> { };
  private static final FlightReporter NOOP_REPORTER = (flight, error) -> FlightReport.landed();

  private AirportSpec validSpec() {
    return AirportSpec.of("acme").slots(60, Duration.ofMinutes(1))
        .pilot(NOOP_PILOT).reporter(NOOP_REPORTER);
  }

  @Test
  void duplicateGateIdIsRejectedAtGateCall() {
    AirportSpec spec = AirportSpec.of("acme").gate("contacts");
    assertThrows(IllegalArgumentException.class, () -> spec.gate("contacts", 3));
  }

  @Test
  void validateRequiresPilotAndReporter() {
    assertThrows(IllegalStateException.class, () -> AirportSpec.of("acme")
        .slots(60, Duration.ofMinutes(1)).reporter(NOOP_REPORTER).validate());
    assertThrows(IllegalStateException.class, () -> AirportSpec.of("acme")
        .slots(60, Duration.ofMinutes(1)).pilot(NOOP_PILOT).validate());
    validSpec().validate();
  }

  @Test
  void gateSlotCostDefaultsToOne() {
    AirportSpec spec = validSpec().gate("contacts").gate("export", 5);
    assertEquals(1, spec.gates().get("contacts").slotCost());
    assertEquals(5, spec.gates().get("export").slotCost());
  }

  @Test
  void idsMustBeQueueSafe() {
    assertThrows(IllegalArgumentException.class, () -> AirportSpec.of("has spaces!"));
    assertThrows(IllegalArgumentException.class, () -> validSpec().gate("bad gate"));
  }
}
