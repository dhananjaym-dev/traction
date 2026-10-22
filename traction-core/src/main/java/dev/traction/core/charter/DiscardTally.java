package dev.traction.core.charter;

import java.util.UUID;

/**
 * Seam between parked-queue discard and charter accounting. Discard calls it once for each
 * discarded flight that belongs to a charter, so the charter can count it as discarded. With no
 * bean registered, discard simply skips the tally.
 *
 * <p>A crash after the tally but before the message is acked redelivers that flight on the next
 * discard, so an implementation must be idempotent per {@code (charterRef, flightId)}.
 */
@FunctionalInterface
public interface DiscardTally {

  void discarded(String charterRef, UUID flightId);
}
