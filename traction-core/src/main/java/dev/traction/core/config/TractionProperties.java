package dev.traction.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Framework settings under the {@code traction.} prefix. */
@Getter
@Setter
@ConfigurationProperties("traction")
public class TractionProperties {

  /**
   * Kill switch for consumption. When false, airports still register (queues declared, airport
   * announced) but their consumers are never started, so flights accumulate on the runway.
   */
  private boolean enabled = true;
}
