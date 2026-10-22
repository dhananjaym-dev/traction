package dev.traction.core.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

class QueueDepthProbeTest {

  private final RabbitAdmin admin = mock(RabbitAdmin.class);
  private final QueueDepthProbe probe = new QueueDepthProbe(admin);

  @Test
  void anAbsentQueueABrokerFailureAndAMissingCountAreUnknownNeverZero() {
    when(admin.getQueueProperties("absent")).thenReturn(null);
    when(admin.getQueueProperties("down")).thenThrow(new RuntimeException("amqp down"));
    when(admin.getQueueProperties("odd")).thenReturn(new Properties());

    assertEquals(Optional.empty(), probe.depth("absent"));
    assertEquals(Optional.empty(), probe.depth("down"));
    assertEquals(Optional.empty(), probe.depth("odd"));
  }

  @Test
  void cachedDepthServesRepeatReadsWithinTheTtlWithoutHittingTheBroker() {
    Properties props = new Properties();
    props.put("QUEUE_MESSAGE_COUNT", 7);
    when(admin.getQueueProperties("q")).thenReturn(props);

    assertEquals(Optional.of(7L), probe.cachedDepth("q"));
    assertEquals(Optional.of(7L), probe.cachedDepth("q"));

    verify(admin, times(1)).getQueueProperties("q");
  }
}
