package dev.traction.core.clearance;

/**
 * Outcome of one clearance attempt: either a token was taken, or the bucket says how long until
 * enough tokens accrue.
 */
public record ClearanceResult(boolean granted, long waitMillis) {

  /** Slots acquired; land now. */
  public static ClearanceResult cleared() {
    return new ClearanceResult(true, 0L);
  }

  /** Bucket exhausted; {@code waitMillis} until the requested cost would be available. */
  public static ClearanceResult denied(long waitMillis) {
    return new ClearanceResult(false, waitMillis);
  }
}
