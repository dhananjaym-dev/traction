package dev.traction.core.consume;

import dev.traction.core.api.Flight;
import dev.traction.core.api.HoldPolicy;
import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.topology.QueueNames;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * The holding pattern (a TTL retry queue): while the flight has hold budget left, publish it to
 * the airport's holding queue with the backoff for its round (the {@link HoldPolicy} schedule
 * indexed by its current hold count); the broker dead-letters it back to the runway on expiry.
 * Budget spent: park it on the parked queue, never drop it. For retries only, never for pacing
 * (that is clearance). Known, accepted caveat: per-message TTL expires only at the queue head.
 * Publishes are confirmed and a failure propagates (the caller must not ack).
 */
@Slf4j
public class HoldingPatternService {

  public enum HoldOutcome { HELD, PARKED }

  private final ConfirmedPublisher confirmedPublisher;

  public HoldingPatternService(ConfirmedPublisher confirmedPublisher) {
    this.confirmedPublisher = confirmedPublisher;
  }

  public HoldOutcome holdOrPark(Flight<?> flight, HoldPolicy policy) {
    QueueNames names = QueueNames.of(flight.airportId());
    int rounds = policy.schedule().size();
    if (flight.holdCount() >= rounds) {
      confirmedPublisher.publish(names.parked(), flight, null);
      log.info("flight {} exhausted its hold budget ({} rounds); parked", flight.flightId(), rounds);
      return HoldOutcome.PARKED;
    }
    Duration ttl = policy.schedule().get(flight.holdCount());
    Flight<?> held = flight.withIncrementedHold();
    confirmedPublisher.publish(names.holding(), held, ttl);
    log.info("flight {} holding for {} (round {}/{})", flight.flightId(), ttl, held.holdCount(), rounds);
    return HoldOutcome.HELD;
  }
}
