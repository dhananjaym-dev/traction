package dev.traction.core.charter;

import static dev.traction.core.charter.CharterFixtures.AIRPORT;
import static dev.traction.core.charter.CharterFixtures.GATE;
import static dev.traction.core.charter.CharterFixtures.OTHER_GATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.traction.core.api.AirportStatus;
import dev.traction.core.api.CharterConcurrency;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.status.ScopeRef;
import dev.traction.core.status.StatusReader;
import dev.traction.core.topology.QueueNames;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

/**
 * The stale-charter backstop: an open charter with no progress for the stale threshold, on a
 * gate that is consuming with a provably empty runway and holding queue, is force-completed with
 * {@code source=SWEEP} (status ABORTED when it had been aborted). Anything that cannot be proven
 * drained waits.
 */
class CharterSweepTest {

  private static final Duration STALE = Duration.ofHours(24);

  private final CharterFixtures world = new CharterFixtures(CharterConcurrency.PER_KEY);
  private final StatusReader status = mock(StatusReader.class);
  private final AmqpAdmin admin = mock(AmqpAdmin.class);
  private final QueueNames names = QueueNames.of(AIRPORT);

  private CharterSweep sweepAt(Instant now) {
    return new CharterSweep(world.board, status, admin, Duration.ofHours(1), STALE,
        Clock.fixed(now, ZoneOffset.UTC));
  }

  private void depths(Long runway, Long holding) {
    when(admin.getQueueInfo(names.runway())).thenReturn(
        runway == null ? null : new QueueInformation(names.runway(), runway.intValue(), 1));
    when(admin.getQueueInfo(names.holding())).thenReturn(
        holding == null ? null : new QueueInformation(names.holding(), holding.intValue(), 0));
  }

  @Test
  void aStaleDrainedCharterIsForceCompletedWithSourceSweepAndAFreshOneIsLeftOpen() {
    when(status.get(any())).thenReturn(AirportStatus.OPEN);
    depths(0L, 0L);
    String stale = world.open("run-1", 10);
    Instant later = Instant.now().plus(STALE).plusSeconds(60);
    String fresh = world.open(OTHER_GATE, "run-2", 10);
    world.redis.hashes.get(CharterKeys.key(fresh))
        .put("lastProgressAt", String.valueOf(later.toEpochMilli()));

    sweepAt(later).sweepOnce();

    assertEquals("COMPLETED", world.snapshot(stale).status());
    assertEquals("SWEEP", world.snapshot(stale).source());
    assertEquals("OPEN", world.snapshot(fresh).status());
    NotifyEvent completed = world.notified.get(world.notified.size() - 1);
    assertEquals(NotifyEvent.Type.CHARTER_COMPLETED, completed.type());
    assertEquals("SWEEP", completed.reason());
    assertEquals(stale, completed.detail().get("charterRef"));
  }

  @Test
  void anAbortedCharterWhoseRemainderNeverReportsIsClosedBySweepAsAborted() {
    when(status.get(any())).thenReturn(AirportStatus.OPEN);
    depths(0L, 0L);
    String ref = world.open("run-1", 10);
    world.board.abort(ref, "ops@acme"); // its flights were lost: no skipped rows ever arrive

    sweepAt(Instant.now().plus(STALE).plusSeconds(60)).sweepOnce();

    assertEquals("ABORTED", world.snapshot(ref).status());
    assertEquals("SWEEP", world.snapshot(ref).source());
    NotifyEvent completed = world.notified.get(world.notified.size() - 1);
    assertEquals(NotifyEvent.Type.CHARTER_COMPLETED, completed.type());
    assertEquals("SWEEP", completed.reason());
  }

  @Test
  void aStaleCharterThatCannotBeProvenDrainedWaits() {
    String ref = world.open("run-1", 10);
    CharterSweep sweep = sweepAt(Instant.now().plus(STALE).plusSeconds(60));
    when(status.get(any())).thenReturn(AirportStatus.OPEN);

    depths(null, 0L); // depth unknown is not empty
    sweep.sweepOnce();
    depths(0L, 3L); // flights still holding
    sweep.sweepOnce();
    depths(0L, 0L);
    when(status.get(ScopeRef.gate(AIRPORT, GATE))).thenReturn(AirportStatus.GROUND_STOP);
    sweep.sweepOnce(); // a grounded gate waits for a human

    assertEquals("OPEN", world.snapshot(ref).status());
  }

  @Test
  void anOpenEntryWhoseHashIsGoneIsAbandonedAndFreesItsAdmissionKey() {
    String ref = world.open("run-1", 10);
    world.redis.expire(CharterKeys.key(ref));
    assertTrue(world.redis.strings.containsKey(CharterKeys.admissionKey(AIRPORT + "/" + GATE)));

    sweepAt(Instant.now()).sweepOnce();

    assertFalse(world.redis.sets.get(CharterKeys.OPEN_SET).contains(ref));
    assertFalse(world.redis.strings.containsKey(CharterKeys.admissionKey(AIRPORT + "/" + GATE)));
    world.open("run-2", 1); // the gate admits again
  }
}
