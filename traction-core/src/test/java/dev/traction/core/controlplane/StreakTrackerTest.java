package dev.traction.core.controlplane;

import static dev.traction.core.controlplane.Reports.AIRPORT;
import static dev.traction.core.controlplane.Reports.landed;
import static dev.traction.core.controlplane.Reports.recordIncident;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.traction.core.api.StopReason;
import dev.traction.core.status.ScopeRef;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * Streak windows end to end through the rules: the tracker runs against an in-memory stand-in
 * for the Redis sorted sets (member = domainRef, score = epoch ms), so distinctness, the
 * sliding window and clear-on-land are the real semantics, not call shapes.
 */
class StreakTrackerTest {

  private static final ScopeRef GATE = ScopeRef.gate(AIRPORT, "company-sync");

  private final Map<String, Map<String, Double>> zsets = new HashMap<>();
  private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
  private final TransitionService transitions = mock(TransitionService.class);
  private GroundStopRules rules;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ZSetOperations<String, String> z = mock(ZSetOperations.class);
    when(redis.opsForZSet()).thenReturn(z);
    when(z.add(anyString(), anyString(), anyDouble())).thenAnswer(i -> {
      zsets.computeIfAbsent(i.getArgument(0), k -> new HashMap<>())
          .put(i.getArgument(1), i.getArgument(2));
      return true;
    });
    when(z.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenAnswer(i -> {
      double min = i.getArgument(1);
      double max = i.getArgument(2);
      Map<String, Double> set = zsets.getOrDefault(i.getArgument(0), new HashMap<>());
      long before = set.size();
      set.values().removeIf(score -> score >= min && score <= max);
      return before - set.size();
    });
    when(z.zCard(anyString()))
        .thenAnswer(i -> (long) zsets.getOrDefault(i.getArgument(0), Map.of()).size());
    when(redis.delete(anyCollection())).thenAnswer(i -> {
      Collection<String> keys = i.getArgument(0);
      return keys.stream().filter(k -> zsets.remove(k) != null).count();
    });
    rules = new GroundStopRules(new StreakTracker(redis, now::get), transitions);
  }

  private void gateFault(String domainRef) {
    rules.evaluate(recordIncident("GATE_FAULT", "company-sync", domainRef));
  }

  private void verifyGateGrounded(StopReason reason) {
    verify(transitions).groundStop(eq(GATE), eq(reason), anyString(), any(), anyMap(), anyLong());
  }

  private void verifyNothingGrounded() {
    verify(transitions, never()).groundStop(any(), any(), any(), any(), any(), anyLong());
  }

  @Test
  void gateFaultTripsAtThreeDistinctDomainRefsWithinFiveMinutes() {
    gateFault("co-1");
    gateFault("co-2");
    verifyNothingGrounded();

    now.addAndGet(Duration.ofMinutes(4).toMillis());
    gateFault("co-3");
    verifyGateGrounded(StopReason.GATE_FAULT);
  }

  @Test
  void threeGateFaultsOnTheSameDomainRefNeverTrip() {
    gateFault("co-1");
    gateFault("co-1");
    gateFault("co-1");
    verifyNothingGrounded();
  }

  @Test
  void faultsOlderThanTheWindowStopCounting() {
    gateFault("co-1");
    now.addAndGet(Duration.ofMinutes(2).toMillis());
    gateFault("co-2");
    now.addAndGet(Duration.ofMinutes(4).toMillis()); // co-1 is now 6 minutes old
    gateFault("co-3");
    verifyNothingGrounded();

    gateFault("co-4");
    verifyGateGrounded(StopReason.GATE_FAULT);
  }

  @Test
  void structuralStreakMixesFlightFaultAndInternalAndTripsAtFive() {
    rules.evaluate(recordIncident("FLIGHT_FAULT", "company-sync", "co-1"));
    rules.evaluate(recordIncident("INTERNAL", "company-sync", "co-2"));
    rules.evaluate(recordIncident("FLIGHT_FAULT", "company-sync", "co-3"));
    rules.evaluate(recordIncident("INTERNAL", "company-sync", "co-4"));
    verifyNothingGrounded();

    rules.evaluate(recordIncident("FLIGHT_FAULT", "company-sync", "co-5"));
    verifyGateGrounded(StopReason.INCIDENT);
  }

  @Test
  void aLandingOnTheGateClearsBothStreaks() {
    gateFault("co-1");
    gateFault("co-2");
    for (int i = 1; i <= 4; i++) {
      rules.evaluate(recordIncident("FLIGHT_FAULT", "company-sync", "bad-" + i));
    }

    rules.evaluate(landed("company-sync"));
    assertTrue(zsets.isEmpty());

    gateFault("co-3");
    rules.evaluate(recordIncident("FLIGHT_FAULT", "company-sync", "bad-5"));
    verifyNothingGrounded();
  }
}
