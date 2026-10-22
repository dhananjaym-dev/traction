package dev.traction.core.api;

import dev.traction.core.charter.CharterOpener;

/**
 * Opens charters: a charter is a bulk run of flights tracked to completion. Open it before
 * dispatching, then pass the returned ref on every flight of the run with
 * {@code Tower.dispatch(airportId, gateId, payload, new DispatchOptions(domainRef, charterRef))}.
 *
 * <pre>
 *   String run = charterDesk.open("mail:acme", "welcome", "welcome-2026-10-22", contacts.size());
 *   for (Contact c : contacts) {
 *     tower.dispatch("mail:acme", "welcome", c, new DispatchOptions(c.id(), run));
 *   }
 * </pre>
 *
 * The control plane counts every flight's settled outcome ({@code landed, rejected, skipped,
 * parked, discarded}) and fires {@code CHARTER_COMPLETED} once they add up to {@code expected}.
 * Only airports registered in this process can be used, like {@link Tower}. Call it from the
 * request path: a {@link CharterConcurrencyException} has to reach the caller.
 */
public final class CharterDesk {

  private final CharterOpener opener;

  public CharterDesk(CharterOpener opener) {
    this.opener = opener;
  }

  /**
   * Opens the charter {@code charterId} on an airport's gate and returns its
   * {@code charterRef = airportId/gateId/charterId}. Opening a charter that is still open
   * returns the same ref (a safe retry; its expected count stays the first one).
   *
   * @param charterId the run's own id, unique per gate: letters, digits, {@code _ - .}
   * @param expected how many flights the run will dispatch; 0 completes it immediately
   * @throws IllegalArgumentException unknown airport or gate, a malformed charterId, a negative
   *     expected, or a charterId whose charter already ran (pick a new one)
   * @throws CharterConcurrencyException the gate already has an open charter under
   *     {@link CharterConcurrency#PER_KEY}
   */
  public String open(String airportId, String gateId, String charterId, long expected) {
    return opener.open(airportId, gateId, charterId, expected);
  }
}
