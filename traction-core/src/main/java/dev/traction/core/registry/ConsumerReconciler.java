package dev.traction.core.registry;

import dev.traction.core.api.AirportSpec;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Boot-time registration of every {@link AirportSpec} bean (static airports only). Runs once,
 * after all singletons exist, so a misconfigured airport fails startup before the application
 * serves traffic and {@code Tower.dispatch} works from any later lifecycle phase. Fail-fast:
 * any validation, broker declare or announce failure propagates and aborts boot.
 */
public class ConsumerReconciler implements SmartInitializingSingleton {

  private final AirportRegistry registry;
  private final List<AirportSpec> specs;

  public ConsumerReconciler(AirportRegistry registry, List<AirportSpec> specs) {
    this.registry = registry;
    this.specs = List.copyOf(specs);
  }

  @Override
  public void afterSingletonsInstantiated() {
    registry.registerAll(specs);
  }
}
