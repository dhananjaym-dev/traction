package dev.traction.core.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class GroundDelayServiceTest {

  private ValueOperations<String, String> valueOps;
  private GroundDelayService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(valueOps);
    service = new GroundDelayService(redis);
  }

  @Test
  void delayWritesSelfExpiringMarker() {
    service.delay("a", Duration.ofSeconds(120));
    // The TTL is what makes the delay self-expiring: Redis drops the key, nobody resumes it.
    verify(valueOps).set(eq("tr:grounddelay:a"), anyString(), eq(Duration.ofSeconds(120)));
  }

  @Test
  void remainingReturnsPositiveDurationForFutureMarker() {
    when(valueOps.get("tr:grounddelay:a"))
        .thenReturn(String.valueOf(System.currentTimeMillis() + 60_000));
    Optional<Duration> remaining = service.remaining("a");
    assertTrue(remaining.isPresent());
    assertTrue(remaining.get().toMillis() > 0 && remaining.get().toMillis() <= 60_000);
  }

  @Test
  void remainingEmptyWhenNoMarkerOrAlreadyExpired() {
    assertEquals(Optional.empty(), service.remaining("a"));
    when(valueOps.get("tr:grounddelay:b"))
        .thenReturn(String.valueOf(System.currentTimeMillis() - 1_000));
    assertEquals(Optional.empty(), service.remaining("b"));
  }

  @Test
  void remainingFailsOpenOnRedisError() {
    when(valueOps.get("tr:grounddelay:a")).thenThrow(new IllegalStateException("down"));
    assertEquals(Optional.empty(), service.remaining("a"));
  }
}
