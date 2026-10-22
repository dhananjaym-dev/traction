package dev.traction.core.status;

import dev.traction.core.api.AirportStatus;
import dev.traction.core.api.StopReason;
import java.util.Optional;

/**
 * The {@code tr:events} pub/sub contract: how the control plane tells every process that a
 * status changed. The payload is a pipe-delimited flat string, so any language can read it
 * without JSON:
 * <pre>
 *   STATUS_CHANGED|&lt;scopeRef&gt;|&lt;status&gt;|&lt;reason or empty&gt;
 *   e.g. STATUS_CHANGED|crm:acme|GROUND_STOP|AIRPORT_FAULT
 *        STATUS_CHANGED|crm:acme/company-sync|OPEN|
 * </pre>
 * Pub/sub is at-most-once: the durable status hash ({@code tr:status:*}, read per flight by
 * {@link StatusReader}) stays the truth for a process that missed a broadcast.
 */
public final class Events {

  public static final String CHANNEL = "tr:events";
  public static final String STATUS_CHANGED = "STATUS_CHANGED";

  /** A parsed STATUS_CHANGED payload; {@code reason} is null when empty. */
  public record StatusChanged(ScopeRef scope, AirportStatus status, String reason) { }

  private Events() { }

  public static String statusChanged(ScopeRef scope, AirportStatus status, StopReason reason) {
    return String.join("|", STATUS_CHANGED, scope.value(), status.name(),
        reason == null ? "" : reason.name());
  }

  /** Empty for anything that is not a well-formed STATUS_CHANGED payload. */
  public static Optional<StatusChanged> parse(String payload) {
    String[] parts = payload == null ? new String[0] : payload.split("\\|", -1);
    if (parts.length < 3 || !STATUS_CHANGED.equals(parts[0]) || parts[1].isEmpty()) {
      return Optional.empty();
    }
    AirportStatus status;
    try {
      status = AirportStatus.valueOf(parts[2]);
    } catch (IllegalArgumentException unknown) {
      return Optional.empty();
    }
    String reason = parts.length > 3 && !parts[3].isEmpty() ? parts[3] : null;
    return Optional.of(new StatusChanged(ScopeRef.raw(parts[1]), status, reason));
  }
}
