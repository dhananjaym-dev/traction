package dev.traction.core.publish;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.traction.core.publish.ConfirmedPublisher.PublishException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitOperations.OperationsCallback;

class ConfirmedPublisherTest {

  private final RabbitOperations confirmedTemplate = mock(RabbitOperations.class);
  private final RabbitOperations ops = mock(RabbitOperations.class);
  private final ConfirmedPublisher publisher = new ConfirmedPublisher(confirmedTemplate);

  /** Runs the invoke callback against {@link #ops} and returns its result. */
  @SuppressWarnings("unchecked")
  private void wireInvokeToOps() {
    when(confirmedTemplate.invoke(any(OperationsCallback.class)))
        .thenAnswer(inv -> ((OperationsCallback<Boolean>) inv.getArgument(0)).doInRabbit(ops));
  }

  @Test
  void publishSendsThenWaitsForTheBrokerConfirm() {
    wireInvokeToOps();
    when(ops.waitForConfirms(anyLong())).thenReturn(true);

    assertDoesNotThrow(() -> publisher.publish("q.demo", "payload", null));

    InOrder order = inOrder(ops);
    order.verify(ops).convertAndSend("", "q.demo", (Object) "payload");
    order.verify(ops).waitForConfirms(5000L);
  }

  @Test
  void unconfirmedPublishThrows() {
    wireInvokeToOps();
    when(ops.waitForConfirms(anyLong())).thenReturn(false);

    PublishException e = assertThrows(PublishException.class,
        () -> publisher.publish("q.demo", "payload", null));
    assertTrue(e.getMessage().contains("q.demo"));
  }

  @Test
  void ttlBecomesAPerMessageExpiration() {
    wireInvokeToOps();
    when(ops.waitForConfirms(anyLong())).thenReturn(true);

    publisher.publish("q.demo", "payload", Duration.ofSeconds(30));

    ArgumentCaptor<MessagePostProcessor> post = ArgumentCaptor.forClass(MessagePostProcessor.class);
    verify(ops).convertAndSend(eq(""), eq("q.demo"), eq((Object) "payload"), post.capture());
    Message processed = post.getValue()
        .postProcessMessage(new Message(new byte[0], new MessageProperties()));
    assertEquals("30000", processed.getMessageProperties().getExpiration());
  }

  @Test
  void brokerErrorSurfacesAsPublishException() {
    when(confirmedTemplate.invoke(any(OperationsCallback.class)))
        .thenThrow(new AmqpException("broker down"));

    assertThrows(PublishException.class, () -> publisher.publish("q.demo", "payload", null));
  }
}
