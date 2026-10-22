package dev.traction.core.controlplane;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.traction.core.registry.AirportAnnouncement;
import dev.traction.core.registry.AirportAnnouncement.GateAnnouncement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** The sweep re-broadcasts what is stored (it never writes status), so a missed event heals. */
class StatusSweepTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final HashOperations<String, Object, Object> hash = mock(HashOperations.class);
  private final AnnouncementProjector fleet = mock(AnnouncementProjector.class);
  private final StatusSweep sweep =
      new StatusSweep(new StatusStore(redis), fleet, Duration.ofSeconds(60));

  @BeforeEach
  void setUp() {
    when(redis.opsForHash()).thenReturn(hash);
    when(fleet.airportIds()).thenReturn(Set.of("crm:acme", "mail:acme"));
    when(fleet.declaration("crm:acme")).thenReturn(Optional.of(AirportAnnouncement.of("crm:acme",
        "demo-app", "Acme CRM", 60, 60L, 60,
        List.of(new GateAnnouncement("contact-sync", 1), new GateAnnouncement("company-sync", 2)))));
    when(fleet.declaration("mail:acme")).thenReturn(Optional.empty());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void eachDeclaredAirportAndEachGroundedGateIsRebroadcastAsStored() {
    when(hash.entries("tr:status:crm:acme")).thenReturn(Map.of("status", "OPEN"));
    when(hash.entries("tr:status:crm:acme/company-sync"))
        .thenReturn(Map.of("status", "GROUND_STOP", "reason", "GATE_FAULT"));

    sweep.sweepOnce();

    verify(redis).convertAndSend("tr:events", "STATUS_CHANGED|crm:acme|OPEN|");
    verify(redis).convertAndSend("tr:events", "STATUS_CHANGED|mail:acme|OPEN|");
    verify(redis).convertAndSend("tr:events", "STATUS_CHANGED|crm:acme/company-sync|GROUND_STOP|GATE_FAULT");
    verify(redis, times(3)).convertAndSend(anyString(), anyString()); // an OPEN gate stays quiet
    verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    verify(hash, never()).putAll(anyString(), anyMap());
    verify(hash, never()).put(anyString(), any(), any());
  }

  @Test
  void aRedisFailureIsContainedAndTheNextSweepRetries() {
    when(hash.entries(anyString())).thenThrow(new IllegalStateException("redis down"))
        .thenReturn(Map.of());

    sweep.sweepOnce(); // must not throw: the scheduler would otherwise cancel the task

    sweep.sweepOnce();
    verify(redis).convertAndSend("tr:events", "STATUS_CHANGED|crm:acme|OPEN|");
  }
}
