package dev.traction.core.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.registry.AirportAnnouncement;
import dev.traction.core.registry.AirportAnnouncement.GateAnnouncement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Announcements project into {@code tr:airports} + {@code tr:airport:<id>} and read back. */
class AnnouncementProjectorTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final HashOperations<String, Object, Object> hash = mock(HashOperations.class);
  @SuppressWarnings("unchecked")
  private final SetOperations<String, String> set = mock(SetOperations.class);
  private final AnnouncementProjector projector =
      new AnnouncementProjector(redis, new ObjectMapper());

  private final AirportAnnouncement crm = AirportAnnouncement.of("crm:acme", "demo-app", "Acme CRM",
      60, 60L, 60, List.of(new GateAnnouncement("contact-sync", 1),
          new GateAnnouncement("company-sync", 2)));

  @BeforeEach
  void setUp() {
    when(redis.opsForHash()).thenReturn(hash);
    when(redis.opsForSet()).thenReturn(set);
  }

  @Test
  @SuppressWarnings("unchecked")
  void projectsTheAirportHashAndJoinsTheFleetAndReadsBack() {
    when(hash.putIfAbsent("tr:airport:crm:acme", "owningProcess", "demo-app")).thenReturn(true);

    projector.project(crm);

    ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
    verify(hash).putAll(org.mockito.ArgumentMatchers.eq("tr:airport:crm:acme"), fields.capture());
    assertEquals("Acme CRM", fields.getValue().get("displayName"));
    assertEquals("60", fields.getValue().get("slotsLimit"));
    verify(set).add("tr:airports", "crm:acme");

    Map<Object, Object> stored = new HashMap<>(fields.getValue());
    stored.put("owningProcess", "demo-app");
    when(hash.entries("tr:airport:crm:acme")).thenReturn(stored);
    AirportAnnouncement back = projector.declaration("crm:acme").orElseThrow();
    assertEquals(crm.gates(), back.gates());
    assertEquals(crm.displayName(), back.displayName());
    assertEquals(crm.slotsWindowSeconds(), back.slotsWindowSeconds());
    assertTrue(projector.declaration("mail:acme").isEmpty());
  }

  @Test
  void anAirportOwnedByAnotherProcessIsNotOverwritten() {
    when(hash.putIfAbsent("tr:airport:crm:acme", "owningProcess", "demo-app")).thenReturn(false);
    when(hash.get("tr:airport:crm:acme", "owningProcess")).thenReturn("billing-app");

    projector.project(crm);

    verify(hash, never()).putAll(any(), anyMap());
    verify(set, never()).add(any(), any());
  }
}
