package dev.traction.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.traction.core.registry.AirportRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class TowerTest {

  record Contact(String email) { }

  private final AirportRegistry registry = mock(AirportRegistry.class);
  private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
  private final Tower tower = new Tower(registry, rabbit);

  @BeforeEach
  void knownAirport() {
    when(registry.gatesOf("acme")).thenReturn(Optional.of(Map.of(
        "contacts", new AirportSpec.Gate("contacts", 1))));
    when(registry.gatesOf("nope")).thenReturn(Optional.empty());
  }

  @SuppressWarnings("unchecked")
  private Flight<Object> published(int times) {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(rabbit, times(times)).convertAndSend(eq(""), eq("tr.acme.runway"), captor.capture());
    return (Flight<Object>) captor.getValue();
  }

  @Test
  void dispatchMintsIdsAndStampsGateAndPayloadType() {
    Contact payload = new Contact("a@b.c");

    Flight<Object> returned = tower.dispatch("acme", "contacts", payload);

    Flight<Object> sent = published(1);
    assertSame(returned, sent);
    assertNotNull(sent.flightId());
    assertNotNull(sent.correlationId());
    assertEquals("acme", sent.airportId());
    assertEquals("contacts", sent.gateId());
    assertEquals(0, sent.attemptCount());
    assertEquals(0, sent.holdCount());
    assertEquals(Contact.class.getName(), sent.payloadType());
    assertSame(payload, sent.payload());
    assertNull(sent.domainRef());
    assertNull(sent.charterRef());
  }

  @Test
  void dispatchMintsAFreshFlightIdEachTime() {
    Flight<Object> first = tower.dispatch("acme", "contacts", new Contact("1"));
    Flight<Object> second = tower.dispatch("acme", "contacts", new Contact("2"));
    assertNotEquals(first.flightId(), second.flightId());
  }

  @Test
  void optionsCarryDomainRefAndCharterRef() {
    tower.dispatch("acme", "contacts", new Contact("a@b.c"),
        new DispatchOptions("contact-42", "charter-7"));

    Flight<Object> sent = published(1);
    assertEquals("contact-42", sent.domainRef());
    assertEquals("charter-7", sent.charterRef());
  }

  @Test
  void unknownAirportIsRejectedAndNothingIsPublished() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> tower.dispatch("nope", "contacts", new Contact("a@b.c")));
    assertTrue(e.getMessage().contains("nope"));
    assertThrows(IllegalArgumentException.class,
        () -> tower.dispatch(null, "contacts", new Contact("a@b.c"))); // never an NPE
    verifyNoInteractions(rabbit);
  }

  @Test
  void unknownOrMissingGateIsRejectedAndNothingIsPublished() {
    IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
        () -> tower.dispatch("acme", "exports", new Contact("a@b.c")));
    assertTrue(unknown.getMessage().contains("acme") && unknown.getMessage().contains("exports"));
    assertThrows(IllegalArgumentException.class,
        () -> tower.dispatch("acme", null, new Contact("a@b.c")));
    verifyNoInteractions(rabbit);
  }

  @Test
  void nullPayloadIsRejectedBecauseItsTypeCannotBeStamped() {
    assertThrows(IllegalArgumentException.class, () -> tower.dispatch("acme", "contacts", null));
    verifyNoInteractions(rabbit);
  }
}
