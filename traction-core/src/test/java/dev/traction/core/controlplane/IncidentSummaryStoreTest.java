package dev.traction.core.controlplane;

import static dev.traction.core.controlplane.Reports.airportFault;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.report.FlightReportMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class IncidentSummaryStoreTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final IncidentSummaryStore store = new IncidentSummaryStore(redis, new ObjectMapper());

  @Test
  @SuppressWarnings("unchecked")
  void recordPushesNewestFirstTrimmedToAHundredAndReadsBack() {
    ListOperations<String, String> list = mock(ListOperations.class);
    when(redis.opsForList()).thenReturn(list);
    FlightReportMessage report = airportFault();

    store.record(report);

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(list).leftPush(eq("tr:incidents:crm:acme"), json.capture());
    verify(list).trim("tr:incidents:crm:acme", 0, 99);

    when(list.range("tr:incidents:crm:acme", 0, 99)).thenReturn(List.of(json.getValue(), "{bad"));
    assertEquals(List.of(report), store.recent("crm:acme")); // an unreadable entry is skipped
  }

  @Test
  @SuppressWarnings("unchecked")
  void incidentNotificationsAreCappedPerAirportAndDispositionAndFailOpen() {
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.increment("tr:incident-notify-cap:crm:acme:PARKED")).thenReturn(25L, 26L);

    assertTrue(store.allowNotify("crm:acme", "PARKED"));
    assertFalse(store.allowNotify("crm:acme", "PARKED"));

    when(values.increment(anyString())).thenThrow(new IllegalStateException("redis down"));
    assertTrue(store.allowNotify("crm:acme", "TERMINAL"));
  }
}
