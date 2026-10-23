package dev.traction.core.charter;

import static dev.traction.core.charter.CharterFixtures.AIRPORT;
import static dev.traction.core.charter.CharterFixtures.GATE;
import static dev.traction.core.charter.CharterFixtures.landed;
import static dev.traction.core.charter.CharterFixtures.parked;
import static dev.traction.core.charter.CharterFixtures.report;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.traction.core.api.CharterConcurrency;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.api.NotifyEvent.Type;
import dev.traction.core.report.FlightReportMessage.RecordType;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Charter accounting: settled outcomes only (RECORD rows), once per flight, bucketed by
 * disposition first; completion fires once when the accounted outcomes reach {@code expected}; abort opens the
 * skip window and the skipped rows complete the charter (source ABORT); replay and discard keep the books whole.
 */
class CharterBoardTest {

  private final CharterFixtures world = new CharterFixtures(CharterConcurrency.NONE);
  private final CharterBoard board = world.board;

  private static void assertCounts(CharterBoard.CharterSnapshot s, long landed, long rejected,
      long skipped, long parked, long discarded) {
    assertEquals(List.of(landed, rejected, skipped, parked, discarded),
        List.of(s.landed(), s.rejected(), s.skipped(), s.parked(), s.discarded()), "counts of " + s);
  }

  @Test
  void countsSettledOutcomesOncePerFlightAndCompletesOnceWhenAccounted() {
    String ref = world.open("run-1", 4);
    board.opened(world.published.get(0)); // a redelivered OPENED notifies nothing new
    assertEquals("mail:acme/welcome/run-1", ref);
    assertEquals(List.of(Type.CHARTER_OPENED), world.notifiedTypes());
    assertEquals("4", world.notified.get(0).detail().get("expected"));

    UUID a = UUID.randomUUID();
    board.record(report(RecordType.INTERIM, ref, a, "INCIDENT", "TRANSIENT", "HELD_1", "RETRYING"));
    board.record(landed(ref, a));
    board.record(landed(ref, a)); // redelivered report
    board.record(report(RecordType.RECORD, ref, UUID.randomUUID(), "INCIDENT", "FLIGHT_FAULT", null,
        "TERMINAL"));
    board.record(report(RecordType.RECORD, ref, UUID.randomUUID(), "SKIPPED", null, "UNSUBSCRIBED",
        "TERMINAL"));
    board.record(landed("mail:acme/welcome/never-opened", UUID.randomUUID())); // ignored, no hash
    assertEquals("OPEN", world.snapshot(ref).status());

    // A grounded gate's flight is SKIPPED GATE_GROUNDED with disposition PARKED: it counts parked.
    UUID grounded = UUID.randomUUID();
    board.record(report(RecordType.RECORD, ref, grounded, "SKIPPED", null, "GATE_GROUNDED", "PARKED"));
    board.record(landed(ref, UUID.randomUUID())); // a fifth flight past expected changes nothing

    CharterBoard.CharterSnapshot s = world.snapshot(ref);
    assertCounts(s, 2, 1, 1, 1, 0);
    assertEquals("COMPLETED", s.status());
    assertEquals("LATCH", s.source());
    assertEquals(List.of(Type.CHARTER_OPENED, Type.CHARTER_COMPLETED), world.notifiedTypes());
    NotifyEvent completed = world.notified.get(1);
    assertEquals(AIRPORT, completed.airportId());
    assertEquals(GATE, completed.gateId());
    assertEquals("LATCH", completed.reason());
    assertEquals("1", completed.detail().get("parked"));
    assertEquals(CharterBoard.CLOSED_TTL, world.redis.ttls.get(CharterKeys.key(ref)));
    assertFalse(world.redis.sets.get(CharterKeys.OPEN_SET).contains(ref));
  }

  @Test
  void abortOpensTheSkipWindowAndTheSkippedRowsCompleteItOnceWithSourceAbort() {
    String ref = world.open("run-1", 3);
    board.record(landed(ref, UUID.randomUUID()));

    assertTrue(board.abort(ref, "ops@acme"));
    assertTrue(board.abort(ref, "ops@acme")); // idempotent: nothing new fires

    // Abort opens the skip window and announces itself; it does not complete the books.
    assertTrue(board.isAborted(ref));
    assertEquals(Duration.ofDays(7), world.redis.ttls.get(CharterKeys.abortedKey(ref)));
    CharterBoard.CharterSnapshot s = world.snapshot(ref);
    assertEquals("OPEN", s.status());
    assertEquals("ops@acme", s.abortedBy());
    assertEquals(List.of(Type.CHARTER_OPENED, Type.CHARTER_ABORTED), world.notifiedTypes());
    assertEquals("ops@acme", world.notified.get(1).detail().get("actor"));

    // The data plane settles the remaining flights SKIPPED CHARTER_ABORTED; the latch completes
    // the charter once they fill the books, with source ABORT.
    UUID last = UUID.randomUUID();
    board.record(report(RecordType.RECORD, ref, UUID.randomUUID(), "SKIPPED", null,
        "CHARTER_ABORTED", "TERMINAL"));
    assertEquals("OPEN", world.snapshot(ref).status());
    board.record(report(RecordType.RECORD, ref, last, "SKIPPED", null, "CHARTER_ABORTED", "TERMINAL"));
    board.record(report(RecordType.RECORD, ref, last, "SKIPPED", null, "CHARTER_ABORTED", "TERMINAL"));

    s = world.snapshot(ref);
    assertCounts(s, 1, 0, 2, 0, 0);
    assertEquals("ABORTED", s.status());
    assertEquals("ABORT", s.source());
    assertEquals(s.expected(), s.accounted());
    assertEquals(List.of(Type.CHARTER_OPENED, Type.CHARTER_ABORTED, Type.CHARTER_COMPLETED),
        world.notifiedTypes());
    NotifyEvent completed = world.notified.get(2);
    assertEquals("ABORT", completed.reason());
    assertEquals("ABORT", completed.detail().get("source"));
    long sum = 0;
    for (String bucket : List.of("landed", "rejected", "skipped", "parked", "discarded")) {
      sum += Long.parseLong(completed.detail().get(bucket));
    }
    assertEquals(3, sum); // the completion line itself satisfies the invariant
    assertFalse(world.redis.sets.get(CharterKeys.OPEN_SET).contains(ref));

    assertFalse(board.abort("mail:acme/welcome/no-such-run", "ops@acme"));
  }

  @Test
  void abortingACompletedCharterIsANoOp() {
    String ref = world.open("run-1", 1);
    board.record(landed(ref, UUID.randomUUID()));
    assertEquals("COMPLETED", world.snapshot(ref).status());

    assertFalse(board.abort(ref, "ops@acme")); // the API's 404

    CharterBoard.CharterSnapshot s = world.snapshot(ref);
    assertEquals("COMPLETED", s.status());
    assertEquals("LATCH", s.source());
    assertNull(s.abortedBy());
    assertFalse(board.isAborted(ref)); // no skip window: a later replay of its flights still flies
    assertEquals(List.of(Type.CHARTER_OPENED, Type.CHARTER_COMPLETED), world.notifiedTypes());
  }

  /** Review Focus 5: a parked flight replayed and then landed is counted once. */
  @Test
  void parkReplayLandKeepsTheBooksWholeWithNoDoubleCountAndOneCompletion() {
    String ref = world.open("run-1", 3);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();

    board.record(landed(ref, a));
    board.record(parked(ref, b));
    board.record(parked(ref, b)); // a duplicate RECORD PARKED row
    assertCounts(world.snapshot(ref), 1, 0, 0, 1, 0);

    board.replayed(ref, b); // before the runway publish
    board.replayed(ref, b); // a retried replay of the same flight un-counts nothing more
    assertCounts(world.snapshot(ref), 1, 0, 0, 0, 0);
    board.record(landed(ref, b));
    assertCounts(world.snapshot(ref), 2, 0, 0, 0, 0);

    board.record(parked(ref, c)); // accounted reaches expected: completes, parked counts
    assertEquals(1, world.notifiedTypes().stream().filter(Type.CHARTER_COMPLETED::equals).count());
    board.replayed(ref, c);
    board.record(landed(ref, c));
    board.record(landed(ref, c));

    CharterBoard.CharterSnapshot s = world.snapshot(ref);
    assertCounts(s, 3, 0, 0, 0, 0);
    assertEquals(s.expected(), s.landed() + s.rejected() + s.skipped() + s.parked());
    assertEquals(List.of(Type.CHARTER_OPENED, Type.CHARTER_COMPLETED), world.notifiedTypes());
  }

  @Test
  void discardMovesAParkedFlightToDiscardedOnceAndStillCompletes() {
    String ref = world.open("run-1", 3);
    UUID b = UUID.randomUUID();
    board.record(landed(ref, UUID.randomUUID()));
    board.record(parked(ref, b));

    board.discarded(ref, b);
    board.discarded(ref, b); // a redelivered discard of the same flight
    assertCounts(world.snapshot(ref), 1, 0, 0, 0, 1);
    assertEquals("OPEN", world.snapshot(ref).status());

    // A flight parked without a report (e.g. the broker's delivery limit) counts when discarded.
    board.discarded(ref, UUID.randomUUID());

    CharterBoard.CharterSnapshot s = world.snapshot(ref);
    assertCounts(s, 1, 0, 0, 0, 2);
    assertEquals("COMPLETED", s.status());
    assertEquals(s.expected(), s.accounted());
    assertEquals(List.of(Type.CHARTER_OPENED, Type.CHARTER_COMPLETED), world.notifiedTypes());
  }

  @Test
  void theAbortMarkerReadFailsOpen() {
    String ref = world.open("run-1", 3);
    board.abort(ref, "ops@acme");
    world.redis.down = true;

    assertFalse(board.isAborted(ref)); // an unreadable marker never skips a flight
  }
}
