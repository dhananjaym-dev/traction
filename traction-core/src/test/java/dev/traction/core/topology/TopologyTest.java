package dev.traction.core.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

class TopologyTest {

  private final RabbitAdmin admin = mock(RabbitAdmin.class);
  private final Topology topology = new Topology(admin);

  private List<Queue> declared(int count) {
    ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
    verify(admin, times(count)).declareQueue(captor.capture());
    return captor.getAllValues();
  }

  private static Queue named(List<Queue> queues, String name) {
    return queues.stream().filter(q -> q.getName().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void queueNamesFollowTheTrPrefixConvention() {
    QueueNames names = QueueNames.of("crm.acme");
    assertEquals("tr.crm.acme.runway", names.runway());
    assertEquals("tr.crm.acme.holding", names.holding());
    assertEquals("tr.crm.acme.parked", names.parked());
    assertEquals("tr.tower.queue", QueueNames.tower());
    assertEquals("tr.crm.acme.runway", QueueNames.runway("crm.acme"));
  }

  @Test
  void declaresRunwayHoldingAndParkedWithContainmentArgs() {
    topology.declare("crm.acme");
    List<Queue> queues = declared(3);

    Queue runway = named(queues, "tr.crm.acme.runway");
    assertEquals("quorum", runway.getArguments().get("x-queue-type"));
    assertEquals("", runway.getArguments().get("x-dead-letter-exchange"));
    assertEquals("tr.crm.acme.parked", runway.getArguments().get("x-dead-letter-routing-key"));
    assertEquals(20, runway.getArguments().get("x-delivery-limit"));
    assertTrue(runway.isDurable());

    // Holding delays through a per-message expiration set at publish time (no queue-level TTL),
    // then dead-letters back to the runway.
    Queue holding = named(queues, "tr.crm.acme.holding");
    assertEquals("quorum", holding.getArguments().get("x-queue-type"));
    assertEquals("", holding.getArguments().get("x-dead-letter-exchange"));
    assertEquals("tr.crm.acme.runway", holding.getArguments().get("x-dead-letter-routing-key"));
    assertEquals(-1, holding.getArguments().get("x-delivery-limit"));
    assertNull(holding.getArguments().get("x-message-ttl"));

    // Parked is the end of the line: no DLX, and the delivery limit is pinned to unlimited so
    // brokers that default to 20 can never silently drop a parked flight.
    Queue parked = named(queues, "tr.crm.acme.parked");
    assertEquals("quorum", parked.getArguments().get("x-queue-type"));
    assertNull(parked.getArguments().get("x-dead-letter-exchange"));
    assertEquals(-1, parked.getArguments().get("x-delivery-limit"));
  }

  @Test
  void parkedIsDeclaredBeforeRunwayBecauseItIsTheDeadLetterTarget() {
    topology.declare("crm.acme");
    List<String> order = declared(3).stream().map(Queue::getName).toList();
    assertTrue(order.indexOf("tr.crm.acme.parked") < order.indexOf("tr.crm.acme.runway"), order.toString());
  }

  @Test
  void towerQueueDeadLettersToTheDlqSoReportsAreNeverSilentlyDropped() {
    topology.declareTower();
    List<Queue> queues = declared(2);

    Queue tower = named(queues, "tr.tower.queue");
    assertEquals("quorum", tower.getArguments().get("x-queue-type"));
    assertEquals(20, tower.getArguments().get("x-delivery-limit"));
    assertEquals("", tower.getArguments().get("x-dead-letter-exchange"));
    assertEquals("tr.dlq", tower.getArguments().get("x-dead-letter-routing-key"));

    Queue dlq = named(queues, "tr.dlq");
    assertEquals(-1, dlq.getArguments().get("x-delivery-limit"));
    assertNull(dlq.getArguments().get("x-dead-letter-exchange"));
    assertTrue(queues.indexOf(dlq) < queues.indexOf(tower));
  }
}
