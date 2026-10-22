package dev.traction.core.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** The admin API's response shapes. Nullable fields are real: null means unknown or not set. */
public final class AdminDtos {

  private AdminDtos() { }

  /** One airport of the fleet. {@code reason}, {@code detail}, {@code since} appear while stopped. */
  public record AirportView(String id, String displayName, String status,
      @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
      @JsonInclude(JsonInclude.Include.NON_NULL) String detail,
      @JsonInclude(JsonInclude.Include.NON_NULL) Long since,
      List<GateView> gates, Depths depths, Slots slots) { }

  public record GateView(String id, String status, int slotCost,
      @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
      @JsonInclude(JsonInclude.Include.NON_NULL) String detail,
      @JsonInclude(JsonInclude.Include.NON_NULL) Long since) { }

  /** Message counts per queue; null when the broker could not say (never a fabricated zero). */
  public record Depths(Long runway, Long holding, Long parked) { }

  public record Slots(Integer limit, Long windowSeconds) { }

  /** Body of {@code POST .../discardParked}: {@code confirm} must be {@code discard-all}. */
  public record DiscardRequest(String confirm) { }

  public record ReplayResponse(String airportId, int replayed, int skipped) { }

  public record DiscardResponse(String airportId, int dropped) { }

  /** {@code reopened} is false when the scope was already open. {@code gateId} is null for an airport. */
  public record ReopenResponse(String airportId, @JsonInclude(JsonInclude.Include.NON_NULL) String gateId,
      boolean reopened) { }

  public record AbortResponse(String charterRef, boolean aborted, String actor) { }

  public record ErrorResponse(String error) { }
}
