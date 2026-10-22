package dev.traction.core.config;

import java.time.Duration;
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

  private ControlPlane controlPlane = new ControlPlane();

  private Notify notify = new Notify();

  @Getter
  @Setter
  public static class ControlPlane {

    /**
     * Whether this process runs the control plane (tower consumer, ground-stop rules, status
     * writes, status sweep). Read by {@code @ConditionalOnProperty}; declared here for metadata.
     */
    private boolean enabled = false;

    /** How often the control plane re-broadcasts every declared scope's stored status. */
    private Duration statusSweepInterval = Duration.ofSeconds(60);
  }

  @Getter
  @Setter
  public static class Notify {

    private Log log = new Log();

    @Getter
    @Setter
    public static class Log {

      /** Whether the default {@code LogNotifyHook} is registered beside the application's hooks. */
      private boolean enabled = true;
    }
  }
}
