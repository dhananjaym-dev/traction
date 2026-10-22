package dev.traction.core.registry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.traction.core.publish.ConfirmedPublisher;
import java.util.List;
import org.junit.jupiter.api.Test;

class AmqpAirportDeclarationsTest {

  @Test
  void announcePublishesToTheTowerQueueWithABrokerConfirm() {
    ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
    AirportAnnouncement announcement = AirportAnnouncement.of("acme", "demo-app", "Acme CRM",
        60, 60L, 60, List.of(new AirportAnnouncement.GateAnnouncement("contacts", 1)));

    new AmqpAirportDeclarations(publisher).announce(announcement);

    verify(publisher).publish("tr.tower.queue", announcement, null);
  }
}
