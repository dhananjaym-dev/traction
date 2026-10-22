package dev.traction.core.charter;

import java.util.UUID;

/**
 * Seam between parked-queue replay and charter accounting. Replay calls it for each replayed
 * flight that belongs to a charter, BEFORE republishing the flight to the runway, so the charter
 * stops counting it as parked; the flight's next settled outcome then counts once. With no bean
 * registered, replay simply skips the tally.
 *
 * <p>A crash after the tally but before the message is acked replays that flight again, so an
 * implementation must be idempotent per {@code (charterRef, flightId)}.
 */
@FunctionalInterface
public interface ReplayTally {

  void replayed(String charterRef, UUID flightId);
}
