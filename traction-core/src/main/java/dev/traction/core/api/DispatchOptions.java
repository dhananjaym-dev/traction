package dev.traction.core.api;

/**
 * Optional dispatch metadata. {@code domainRef} identifies the business entity the flight acts on
 * (the idempotency and streak key); {@code charterRef} attaches the flight to a charter. Both are
 * nullable.
 */
public record DispatchOptions(String domainRef, String charterRef) {

  private static final DispatchOptions NONE = new DispatchOptions(null, null);

  public static DispatchOptions none() {
    return NONE;
  }
}
