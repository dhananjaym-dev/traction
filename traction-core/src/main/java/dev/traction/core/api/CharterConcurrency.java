package dev.traction.core.api;

/**
 * Charter admission policy, declared per airport with {@link AirportSpec#charterConcurrency}.
 */
public enum CharterConcurrency {
  /** Any number of charters may be open at once (the default). */
  NONE,
  /**
   * At most one open charter per key, the key being the {@code (airport, gate)} pair: a second
   * open on the same gate is rejected with {@link CharterConcurrencyException} until the first
   * charter completes (or is aborted).
   */
  PER_KEY
}
