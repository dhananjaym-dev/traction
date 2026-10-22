package dev.traction.core.charter;

import static dev.traction.core.charter.CharterFixtures.AIRPORT;
import static dev.traction.core.charter.CharterFixtures.landed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.traction.core.api.CharterConcurrency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CharterQueryServiceTest {

  @Test
  void listsAnAirportsChartersNewestFirstAndPrunesExpiredOnes() {
    CharterFixtures world = new CharterFixtures(CharterConcurrency.NONE);
    CharterQueryService query = new CharterQueryService(world.redis.redis, world.board);
    String done = world.open("run-1", 1);
    String gone = world.open("run-2", 1);
    String open = world.open("run-3", 2);
    world.redis.zsets.get(CharterKeys.airportIndexKey(AIRPORT))
        .putAll(Map.of(done, 1.0, gone, 2.0, open, 3.0)); // opened in this order
    world.board.record(landed(done, UUID.randomUUID()));
    world.redis.expire(CharterKeys.key(gone)); // its retention ran out

    List<CharterBoard.CharterSnapshot> charters = query.list(AIRPORT);

    assertEquals(List.of(open, done),
        charters.stream().map(CharterBoard.CharterSnapshot::charterRef).toList());
    assertEquals(List.of("OPEN", "COMPLETED"),
        charters.stream().map(CharterBoard.CharterSnapshot::status).toList());
    assertFalse(world.redis.zsets.get(CharterKeys.airportIndexKey(AIRPORT)).containsKey(gone));
    assertEquals(List.of(), query.list("crm:acme"));
  }
}
