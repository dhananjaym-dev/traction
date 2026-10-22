package dev.traction.core.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** The per-airport parked-op lock: SET NX with a TTL, released only by its owner. */
class ParkedOpLockTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);
  private final ParkedOpLock lock = new ParkedOpLock(redis);

  @Test
  void acquireSetsNxWithTtlUnderTheAirportKeyAndReturnsAnOwnerToken() {
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(eq("tr:parked-op:crm:acme"), anyString(), eq(ParkedOpLock.TTL)))
        .thenReturn(true);

    String token = lock.acquire("crm:acme");

    assertNotNull(token);
    ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
    verify(values).setIfAbsent(eq("tr:parked-op:crm:acme"), stored.capture(), eq(Duration.ofMinutes(15)));
    assertEquals(token, stored.getValue());
  }

  @Test
  void acquireReturnsNullWhenAnotherOpHoldsTheAirport() {
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

    assertNull(lock.acquire("crm:acme"));
  }

  @Test
  void redisFailurePropagatesInsteadOfRunningADestructiveOpUnguarded() {
    when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));

    assertThrows(IllegalStateException.class, () -> lock.acquire("crm:acme"));
  }

  @Test
  void releaseDeletesOnlyWhenTheOwnerTokenMatches() {
    lock.release("crm:acme", "tok-1");

    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(redis).execute(any(RedisScript.class), keys.capture(), eq("tok-1"));
    assertEquals(List.of("tr:parked-op:crm:acme"), keys.getValue());
  }

  @Test
  void tokensAreUniquePerAcquire() {
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

    assertNotEquals(lock.acquire("a"), lock.acquire("a"));
  }
}
