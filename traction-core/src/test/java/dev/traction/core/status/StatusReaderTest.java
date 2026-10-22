package dev.traction.core.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.traction.core.api.AirportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Data-plane status reads: hash {@code tr:status:<scope>} field {@code status}; anything else is OPEN. */
class StatusReaderTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final HashOperations<String, Object, Object> hash = mock(HashOperations.class);
  private final StatusReader reader = new StatusReader(redis);

  @BeforeEach
  void setUp() {
    when(redis.opsForHash()).thenReturn(hash);
  }

  @Test
  void readsTheDocumentedKeysAndHonorsAStoredStatus() {
    when(hash.get("tr:status:crm:acme", "status")).thenReturn("GROUND_STOP");
    when(hash.get("tr:status:crm:acme/company", "status")).thenReturn("OPEN");

    assertEquals(AirportStatus.GROUND_STOP, reader.get(ScopeRef.airport("crm:acme")));
    assertEquals(AirportStatus.OPEN, reader.get(ScopeRef.gate("crm:acme", "company")));
  }

  @Test
  void absentKeyIsOpen() {
    assertEquals(AirportStatus.OPEN, reader.get(ScopeRef.airport("crm:acme")));
  }

  @Test
  void redisFailureAndCorruptValuesFailOpen() {
    when(hash.get("tr:status:crm:acme", "status")).thenReturn("NONSENSE");
    when(hash.get("tr:status:mail:acme", "status")).thenThrow(new RuntimeException("redis down"));

    assertEquals(AirportStatus.OPEN, reader.get(ScopeRef.airport("crm:acme")));
    assertEquals(AirportStatus.OPEN, reader.get(ScopeRef.airport("mail:acme")));
  }
}
