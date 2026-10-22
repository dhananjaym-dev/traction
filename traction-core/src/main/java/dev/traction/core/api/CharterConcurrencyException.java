package dev.traction.core.api;

/**
 * A charter open refused by the airport's {@link CharterConcurrency#PER_KEY} policy, because
 * another charter is still open on the same key. Map it to your own error (for example HTTP 409).
 */
public class CharterConcurrencyException extends RuntimeException {

  private final String blockingCharterRef;
  private final String scopeKey;

  public CharterConcurrencyException(String blockingCharterRef, String scopeKey) {
    super("charter admission denied for " + scopeKey + ": charter " + blockingCharterRef
        + " is still open");
    this.blockingCharterRef = blockingCharterRef;
    this.scopeKey = scopeKey;
  }

  /** The open charter holding the key. Informational: for messages and logs only. */
  public String blockingCharterRef() {
    return blockingCharterRef;
  }

  /** The admission key, {@code airportId/gateId}. */
  public String scopeKey() {
    return scopeKey;
  }
}
