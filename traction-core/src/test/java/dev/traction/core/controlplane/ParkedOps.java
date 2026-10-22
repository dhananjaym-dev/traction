package dev.traction.core.controlplane;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Shared fixtures for the parked replay and discard tests. */
final class ParkedOps {

  static final String AIRPORT = "crm:acme";
  static final String PARKED = "tr.crm:acme.parked";
  static final String RUNWAY = "tr.crm:acme.runway";

  private ParkedOps() { }

  /** A template whose {@code execute} runs the callback against {@code channel}. */
  @SuppressWarnings("unchecked")
  static RabbitTemplate templateOver(Channel channel) {
    RabbitTemplate template = mock(RabbitTemplate.class);
    try {
      when(template.execute(any(ChannelCallback.class)))
          .thenAnswer(inv -> ((ChannelCallback<Object>) inv.getArgument(0)).doInRabbit(channel));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return template;
  }

  /** A parked wire message; {@code remaining} is what the broker reports left behind it. */
  static GetResponse parked(long tag, UUID flightId, String charterRef, int attempts, int holds,
      int remaining) {
    String json = "{\"flightId\":\"" + flightId + "\",\"airportId\":\"" + AIRPORT + "\","
        + "\"gateId\":\"company-sync\",\"domainRef\":\"co-1\","
        + "\"charterRef\":" + (charterRef == null ? "null" : "\"" + charterRef + "\"") + ","
        + "\"attemptCount\":" + attempts + ",\"holdCount\":" + holds + ","
        + "\"correlationId\":\"corr-1\",\"payloadType\":\"java.lang.String\","
        + "\"payload\":\"p-" + tag + "\"}";
    return raw(tag, json, remaining);
  }

  static GetResponse raw(long tag, String body, int remaining) {
    return new GetResponse(new Envelope(tag, false, "", PARKED),
        new AMQP.BasicProperties.Builder().build(), body.getBytes(StandardCharsets.UTF_8), remaining);
  }
}
