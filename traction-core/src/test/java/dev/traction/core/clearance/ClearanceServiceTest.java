package dev.traction.core.clearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.traction.core.api.SlotsConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class ClearanceServiceTest {

  private StringRedisTemplate redis;
  private HashOperations<String, Object, Object> hashOps;
  private ClearanceService clearance;
  private final SlotsConfig slots = new SlotsConfig(120, Duration.ofMinutes(1), 20);

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    redis = mock(StringRedisTemplate.class);
    hashOps = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hashOps);
    clearance = new ClearanceService(redis);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void scriptReturns(List<Long>... results) {
    var stub = when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)));
    for (List<Long> r : results) {
      stub = stub.thenReturn(r);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private ArgumentCaptor<Object[]> verifyScriptCalls(int times, List<String> keys) {
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(redis, times(times)).execute(any(RedisScript.class), eq(keys), args.capture());
    return args;
  }

  @Test
  void grantedWhenBucketHasTokens() {
    scriptReturns(List.of(1L, 0L));
    ClearanceResult r = clearance.acquire("a", slots, 1);
    assertTrue(r.granted());
    assertEquals(0L, r.waitMillis());
    verifyScriptCalls(1, List.of("tr:slots:a"));
  }

  @Test
  void deniedReportsWaitWhenBucketExhausted() {
    scriptReturns(List.of(0L, 1_500L));
    ClearanceResult r = clearance.acquire("a", slots, 1);
    assertFalse(r.granted());
    assertEquals(1_500L, r.waitMillis());
  }

  @Test
  void costIsSentAsMilliTokenWeight() {
    scriptReturns(List.of(1L, 0L), List.of(1L, 0L));
    clearance.acquire("a", slots, 1);
    clearance.acquire("a", slots, 3);
    ArgumentCaptor<Object[]> args = verifyScriptCalls(2, List.of("tr:slots:a"));
    assertEquals("1000", args.getAllValues().get(0)[0]);
    assertEquals("3000", args.getAllValues().get(1)[0]);
  }

  private static final Map<String, String> CONFIG_120_PER_MIN_BURST_20 =
      // capacity 20 slots = 20000 milli; refill 120 per 60s = 2000 milli-tokens/sec
      Map.of("capacity_milli", "20000", "refill_per_sec_milli", "2000");

  @Test
  void firstAcquireWritesConfigOnlyEvenIfBucketAlreadyHasStaleConfig() {
    // The service never reads the hash: a stale capacity left by an earlier deploy is
    // overwritten unconditionally, and only the two config fields (never tokens) are written.
    scriptReturns(List.of(1L, 0L));
    assertTrue(clearance.acquire("a", slots, 1).granted());
    verify(hashOps).putAll("tr:slots:a", CONFIG_120_PER_MIN_BURST_20);
  }

  @Test
  void sameConfigIsNotRewrittenAndChangedConfigIs() {
    scriptReturns(List.of(1L, 0L), List.of(1L, 0L), List.of(1L, 0L));
    clearance.acquire("a", slots, 1);
    clearance.acquire("a", new SlotsConfig(120, Duration.ofMinutes(1), 20), 1);
    verify(hashOps, times(1)).putAll(eq("tr:slots:a"), any());

    clearance.acquire("a", new SlotsConfig(60, Duration.ofMinutes(1), 10), 5);
    verify(hashOps).putAll("tr:slots:a",
        Map.of("capacity_milli", "10000", "refill_per_sec_milli", "1000"));
    verify(hashOps, times(2)).putAll(eq("tr:slots:a"), any());
  }

  @Test
  void reconcileFailurePropagatesAndIsRetriedOnNextAcquire() {
    doThrow(new IllegalStateException("redis down")).doNothing().when(hashOps).putAll(any(), any());
    assertThrows(IllegalStateException.class, () -> clearance.acquire("a", slots, 1));
    verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));

    scriptReturns(List.of(1L, 0L));
    assertTrue(clearance.acquire("a", slots, 1).granted());
  }

  @Test
  void bucketVanishingMidFlightFailsClosedAndReconcilesNextTime() {
    scriptReturns(List.of(-1L, 0L), List.of(1L, 0L));
    assertThrows(IllegalStateException.class, () -> clearance.acquire("a", slots, 1));
    assertTrue(clearance.acquire("a", slots, 1).granted());
    verify(hashOps, times(2)).putAll("tr:slots:a", CONFIG_120_PER_MIN_BURST_20);
  }

  @Test
  void redisFailurePropagatesSoCallerDefersInsteadOfBypassingTheLimit() {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenThrow(new IllegalStateException("redis down"));
    assertThrows(IllegalStateException.class, () -> clearance.acquire("a", slots, 1));
  }

  @Test
  void costAboveBurstCanNeverClearAndIsRejectedWithoutTouchingRedis() {
    assertThrows(IllegalArgumentException.class, () -> clearance.acquire("a", slots, 21));
    assertThrows(IllegalArgumentException.class, () -> clearance.acquire("a", slots, 0));
    verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
  }
}
