package dev.traction.core.registry;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.traction.core.api.AirportSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsumerReconcilerTest {

  private final AirportRegistry registry = mock(AirportRegistry.class);
  private final List<AirportSpec> specs = List.of(AirportSpec.of("acme"), AirportSpec.of("globex"));
  private final ConsumerReconciler reconciler = new ConsumerReconciler(registry, specs);

  @Test
  void bootRegistersEverySpecBeanInOneBatch() {
    reconciler.afterSingletonsInstantiated();
    verify(registry).registerAll(specs);
  }

  @Test
  void registrationFailureAbortsStartup() {
    doThrow(new IllegalStateException("declare failed")).when(registry).registerAll(specs);
    assertThrows(IllegalStateException.class, reconciler::afterSingletonsInstantiated);
  }
}
