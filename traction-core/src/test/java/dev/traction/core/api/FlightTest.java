package dev.traction.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FlightTest {

  @Test
  void holdIncrementPreservesEverythingElse() {
    Flight<String> f = new Flight<>(UUID.randomUUID(), "a", "g", "d", "c", 2, 1, "corr",
        "java.lang.String", "p");
    Flight<String> held = f.withIncrementedHold();
    assertEquals(2, held.holdCount());
    assertEquals(2, held.attemptCount());
    assertEquals(f.flightId(), held.flightId());
    assertEquals(f.payload(), held.payload());
    assertEquals(f.payloadType(), held.payloadType());
  }
}
