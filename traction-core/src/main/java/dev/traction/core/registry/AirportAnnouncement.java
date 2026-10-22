package dev.traction.core.registry;

import dev.traction.core.api.AirportSpec;
import java.util.List;
import java.util.UUID;

/**
 * Announcement wire contract: an airport's self-description, published to the tower queue on
 * boot. The control plane projects it into Redis for the dashboard, so display context travels
 * with the declaration and the control plane never needs the airport's code. Slot fields are
 * nullable on the wire so a non-JVM publisher may omit them.
 */
public record AirportAnnouncement(
    UUID announcementId,
    String airportId,
    String owningProcess,
    String displayName,
    Integer slotsLimit,
    Long slotsWindowSeconds,
    Integer slotsBurst,
    List<GateAnnouncement> gates,
    long epochMillis) {

  /**
   * Normalizes {@code gates} on every construction path, including Jackson's canonical-constructor
   * deserialization: a remote message that omits {@code gates} becomes an empty list rather than
   * a null that would crash the projector. {@code List.copyOf} rejects null elements, so
   * {@code gates:[null]} fails deserialization and the consumer dead-letters it instead of looping.
   */
  public AirportAnnouncement {
    gates = gates == null ? List.of() : List.copyOf(gates);
  }

  /** One independently stoppable work type within the airport and what a flight on it costs. */
  public record GateAnnouncement(String gateId, int slotCost) { }

  public static AirportAnnouncement of(String airportId, String owningProcess, String displayName,
      Integer slotsLimit, Long slotsWindowSeconds, Integer slotsBurst,
      List<GateAnnouncement> gates) {
    return new AirportAnnouncement(UUID.randomUUID(), airportId, owningProcess, displayName,
        slotsLimit, slotsWindowSeconds, slotsBurst, gates, System.currentTimeMillis());
  }

  /** Describes a validated spec. */
  public static AirportAnnouncement from(AirportSpec spec, String owningProcess) {
    return of(spec.airportId(), owningProcess, spec.displayName(),
        spec.slots().limit(), spec.slots().window().toSeconds(), spec.slots().burst(),
        spec.gates().values().stream()
            .map(g -> new GateAnnouncement(g.gateId(), g.slotCost()))
            .toList());
  }
}
