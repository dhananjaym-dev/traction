package dev.traction.core.controlplane;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.traction.core.api.AirportStatus;
import dev.traction.core.api.StopReason;
import dev.traction.core.status.ScopeRef;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The store's contract around its compare-and-set script: the documented status key, the
 * script's arguments, and a {@code tr:events} broadcast only when this caller won the change.
 */
class StatusStoreTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final StatusStore store = new StatusStore(redis);

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void scriptReturns(String first, String... rest) {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(first, (Object[]) rest);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void groundStopBroadcastsOnlyWhenThisCallChangedTheStatus() {
    scriptReturns("OPEN", (String) null);
    ScopeRef gate = ScopeRef.gate("crm:acme", "company-sync");

    assertTrue(store.groundStop(gate, StopReason.GATE_FAULT, "3 distinct GATE_FAULT failures", 42L));
    assertFalse(store.groundStop(gate, StopReason.GATE_FAULT, "3 distinct GATE_FAULT failures", 43L));

    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(redis, times(2)).execute(any(RedisScript.class),
        eq(List.of("tr:status:crm:acme/company-sync")), args.capture());
    Object[] first = args.getAllValues().get(0);
    assertEquals("stop", first[0]);
    assertArrayEquals(new Object[] {"GATE_FAULT", "3 distinct GATE_FAULT failures", "42"},
        Arrays.copyOfRange(first, 2, 5));
    verify(redis, times(1)).convertAndSend("tr:events",
        "STATUS_CHANGED|crm:acme/company-sync|GROUND_STOP|GATE_FAULT");
  }

  @Test
  void reopenAlwaysBroadcastsOpenButReportsAChangeOnlyOnce() {
    scriptReturns("GROUND_STOP", (String) null);
    ScopeRef airport = ScopeRef.airport("crm:acme");

    assertTrue(store.reopen(airport));
    assertFalse(store.reopen(airport));

    // Idempotent re-broadcast: a consumer that paused itself but missed the stop still resumes.
    verify(redis, times(2)).convertAndSend("tr:events", "STATUS_CHANGED|crm:acme|OPEN|");
  }

  @Test
  void aFailedBroadcastDoesNotUndoTheTransition() {
    scriptReturns("OPEN");
    when(redis.convertAndSend(anyString(), anyString())).thenThrow(new IllegalStateException("down"));

    assertTrue(store.groundStop(ScopeRef.airport("crm:acme"), StopReason.MANUAL));
  }

  @Test
  @SuppressWarnings("unchecked")
  void snapshotReadsTheStatusHashAndAMissingKeyIsOpen() {
    HashOperations<String, Object, Object> hash = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hash);
    when(hash.entries("tr:status:crm:acme")).thenReturn(Map.of("status", "GROUND_STOP",
        "reason", "AIRPORT_FAULT", "detail", "401 | token expired", "since", "1700000000000"));
    when(hash.entries("tr:status:mail:acme")).thenReturn(Map.of());

    StatusStore.StatusSnapshot grounded = store.snapshot(ScopeRef.airport("crm:acme"));
    assertEquals(AirportStatus.GROUND_STOP, grounded.status());
    assertEquals(StopReason.AIRPORT_FAULT, grounded.reason());
    assertEquals("401 | token expired", grounded.detail());
    assertEquals(1_700_000_000_000L, grounded.sinceEpochMillis());

    StatusStore.StatusSnapshot open = store.snapshot(ScopeRef.airport("mail:acme"));
    assertEquals(AirportStatus.OPEN, open.status());
    assertNull(open.reason());
    verify(redis, never()).convertAndSend(anyString(), anyString());
  }
}
