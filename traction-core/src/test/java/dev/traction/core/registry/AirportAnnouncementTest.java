package dev.traction.core.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Wire-path deserialization: Jackson builds records through the canonical constructor. */
class AirportAnnouncementTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void announcementWithoutGatesDeserializesToAnEmptyList() throws Exception {
    String json = """
        {"announcementId":"11111111-1111-1111-1111-111111111111","airportId":"acme",
         "owningProcess":"demo-app","displayName":"Acme CRM","slotsLimit":60,
         "slotsWindowSeconds":60,"slotsBurst":60,"epochMillis":1737000000000}""";

    AirportAnnouncement result = mapper.readValue(json, AirportAnnouncement.class);

    assertTrue(result.gates().isEmpty());
    assertEquals("acme", result.airportId());
  }

  @Test
  void announcementWithANullGateElementFailsDeserialization() {
    String json = """
        {"announcementId":"11111111-1111-1111-1111-111111111111","airportId":"acme",
         "gates":[null],"epochMillis":1737000000000}""";

    assertThrows(Exception.class, () -> mapper.readValue(json, AirportAnnouncement.class));
  }
}
