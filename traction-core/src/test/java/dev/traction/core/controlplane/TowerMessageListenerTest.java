package dev.traction.core.controlplane;

import static dev.traction.core.controlplane.Reports.AIRPORT;
import static dev.traction.core.controlplane.Reports.airportFault;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.charter.CharterEventMessage;
import dev.traction.core.registry.AirportAnnouncement;
import dev.traction.core.report.FlightReportMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

/** Type dispatch and the poison/retry ladder, over the real Traction wire format. */
class TowerMessageListenerTest {

  private final ObjectMapper mapper = new ObjectMapper();
  /** The publishing side, as tractionRabbitTemplate / ConfirmedPublisher convert. */
  private final Jackson2JsonMessageConverter publisher = new Jackson2JsonMessageConverter(mapper);
  private final TowerConsumer consumer = mock(TowerConsumer.class);
  private final TowerMessageListener listener =
      new TowerMessageListener(TowerMessageListener.converter(mapper), consumer, 0);

  @Test
  void reportsAnnouncementsAndCharterEventsArriveAsTheTypesThatWereSent() {
    FlightReportMessage report = airportFault();
    AirportAnnouncement announcement = AirportAnnouncement.of(AIRPORT, "demo-app", "Acme CRM",
        60, 60L, 60, List.of(new AirportAnnouncement.GateAnnouncement("contact-sync", 1)));
    CharterEventMessage opened = CharterEventMessage.opened("mail:acme/welcome/run-1", "mail:acme",
        "welcome", 3);

    listener.onMessage(publisher.toMessage(report, new MessageProperties()));
    listener.onMessage(publisher.toMessage(announcement, new MessageProperties()));
    listener.onMessage(publisher.toMessage(opened, new MessageProperties()));

    verify(consumer).onReport(report);
    verify(consumer).onAnnouncement(announcement);
    verify(consumer).onCharterEvent(opened);
  }

  @Test
  void poisonAndForeignTypesAreDeadLetteredWithoutTouchingTheConsumer() {
    Message garbage = new Message("not json".getBytes(StandardCharsets.UTF_8),
        publisher.toMessage(airportFault(), new MessageProperties()).getMessageProperties());

    assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.onMessage(garbage));
    assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.onMessage(
        publisher.toMessage(new HashMap<>(Map.of("hello", "world")), new MessageProperties())));
    verifyNoInteractions(consumer);
  }

  @Test
  void aHandlerFailureIsRethrownSoTheBrokerRedelivers() {
    IllegalStateException boom = new IllegalStateException("redis down");
    doThrow(boom).when(consumer).onAnnouncement(any());
    AirportAnnouncement announcement = AirportAnnouncement.of(AIRPORT, "demo-app", "Acme CRM",
        60, 60L, 60, List.of());

    assertSame(boom, assertThrows(IllegalStateException.class, () ->
        listener.onMessage(publisher.toMessage(announcement, new MessageProperties()))));
  }
}
