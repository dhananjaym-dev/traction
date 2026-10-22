package dev.traction.core.api;

import java.time.Duration;

/** Shared rate limit for an airport: {@code limit} slots per {@code window}, up to {@code burst}. */
public record SlotsConfig(int limit, Duration window, int burst) {
  public SlotsConfig {
    if (limit <= 0 || burst <= 0 || window == null || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException(
          "SlotsConfig requires positive limit/burst and a positive window");
    }
  }

  /** Burst defaults to the limit. */
  public SlotsConfig(int limit, Duration window) {
    this(limit, window, limit);
  }
}
