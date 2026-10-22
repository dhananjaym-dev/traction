package dev.traction.core.charter;

import static dev.traction.core.charter.CharterFixtures.AIRPORT;
import static dev.traction.core.charter.CharterFixtures.GATE;
import static dev.traction.core.charter.CharterFixtures.OTHER_GATE;
import static dev.traction.core.charter.CharterFixtures.landed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import dev.traction.core.api.CharterConcurrency;
import dev.traction.core.api.CharterConcurrencyException;
import dev.traction.core.publish.ConfirmedPublisher.PublishException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Opening a charter through the desk: validation, the OPENED event, and admission policy. */
class CharterOpenerTest {

  @Test
  void perKeyAdmitsOneOpenCharterPerGateUntilItCompletes() {
    CharterFixtures world = new CharterFixtures(CharterConcurrency.PER_KEY);
    String first = world.open("run-1", 1);
    assertEquals(CharterEventMessage.Type.OPENED, world.published.get(0).type());
    assertEquals(first, world.published.get(0).charterRef());
    assertEquals(1L, world.published.get(0).expected());

    CharterConcurrencyException denied = assertThrows(CharterConcurrencyException.class,
        () -> world.desk.open(AIRPORT, GATE, "run-2", 5));
    assertEquals(first, denied.blockingCharterRef());
    assertEquals(AIRPORT + "/" + GATE, denied.scopeKey());
    world.open(OTHER_GATE, "run-2", 5); // the key is the gate: another gate is free

    world.board.record(landed(first, UUID.randomUUID())); // the first charter completes

    assertEquals("mail:acme/welcome/run-3", world.open("run-3", 5));
    // Re-opening a charter that is still open is idempotent: same ref, no second charter.
    assertEquals("mail:acme/welcome/run-3", world.desk.open(AIRPORT, GATE, "run-3", 5));
    assertEquals("OPEN", world.snapshot("mail:acme/welcome/run-3").status());
  }

  @Test
  void noneAdmitsConcurrentChartersButACompletedCharterIdCannotBeReused() {
    CharterFixtures world = new CharterFixtures(CharterConcurrency.NONE);
    String first = world.open("run-1", 1);
    world.open("run-2", 1);
    world.board.record(landed(first, UUID.randomUUID()));

    assertThrows(IllegalArgumentException.class, () -> world.desk.open(AIRPORT, GATE, "run-1", 1));
  }

  @Test
  void anUnknownAirportGateOrMalformedRequestIsRejectedBeforeAnythingIsWritten() {
    CharterFixtures world = new CharterFixtures(CharterConcurrency.PER_KEY);

    assertThrows(IllegalArgumentException.class, () -> world.desk.open("crm:nope", GATE, "run-1", 1));
    assertThrows(IllegalArgumentException.class, () -> world.desk.open(AIRPORT, "nope", "run-1", 1));
    assertThrows(IllegalArgumentException.class, () -> world.desk.open(AIRPORT, null, "run-1", 1));
    assertThrows(IllegalArgumentException.class, () -> world.desk.open(AIRPORT, GATE, "a/b", 1));
    assertThrows(IllegalArgumentException.class, () -> world.desk.open(AIRPORT, GATE, "a:seen", 1));
    assertThrows(IllegalArgumentException.class, () -> world.desk.open(AIRPORT, GATE, "run-1", -1));

    assertTrue(world.redis.hashes.isEmpty());
    assertTrue(world.redis.strings.isEmpty());
    assertTrue(world.published.isEmpty());
  }

  @Test
  void aFailedOpenedPublishPropagatesAndARetryOfTheSameCharterIdSucceeds() {
    CharterFixtures world = new CharterFixtures(CharterConcurrency.PER_KEY);
    doThrow(new PublishException("broker did not confirm")).when(world.events).publish(any());

    assertThrows(PublishException.class, () -> world.desk.open(AIRPORT, GATE, "run-1", 2));

    reset(world.events);
    assertEquals("mail:acme/welcome/run-1", world.desk.open(AIRPORT, GATE, "run-1", 2));
    verify(world.events).publish(any());
  }
}
