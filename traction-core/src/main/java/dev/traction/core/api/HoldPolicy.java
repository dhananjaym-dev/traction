package dev.traction.core.api;

import java.time.Duration;
import java.util.List;

/**
 * Holding rounds (and backoff per round) a category gets before the flight parks. An empty
 * schedule means the category is never held.
 */
public record HoldPolicy(List<Duration> schedule) {

  public HoldPolicy {
    schedule = List.copyOf(schedule);
  }

  public static final HoldPolicy TRANSIENT_DEFAULT =
      new HoldPolicy(List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10)));
  public static final HoldPolicy INTERNAL_DEFAULT = new HoldPolicy(List.of(Duration.ofSeconds(30)));
  public static final HoldPolicy NEVER = new HoldPolicy(List.of());

  public static HoldPolicy defaultFor(IncidentCategory category) {
    return switch (category) {
      case TRANSIENT -> TRANSIENT_DEFAULT;
      case INTERNAL -> INTERNAL_DEFAULT;
      default -> NEVER;
    };
  }
}
