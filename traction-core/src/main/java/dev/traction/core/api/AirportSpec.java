package dev.traction.core.api;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Registration contract for one airport, built fluently and registered as a bean. Ids become
 * queue-name segments, so they are restricted to {@link #ID_PATTERN}. Call {@link #validate()}
 * before use (the registry does).
 */
public final class AirportSpec {

  public static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9:_\\-.]+$");

  /** An endpoint of the airport; {@code slotCost} is how many slots one flight consumes. */
  public record Gate(String gateId, int slotCost) { }

  private final String airportId;
  private String displayName;
  private SlotsConfig slots;
  private final Map<String, Gate> gates = new LinkedHashMap<>();
  private Pilot<?> pilot;
  private FlightReporter reporter;
  private HoldPolicy holdPolicy;

  private AirportSpec(String airportId) {
    this.airportId = checkId("airportId", airportId);
    this.displayName = airportId;
  }

  public static AirportSpec of(String airportId) {
    return new AirportSpec(airportId);
  }

  public AirportSpec displayName(String displayName) {
    this.displayName = displayName;
    return this;
  }

  public AirportSpec slots(int limit, Duration window) {
    this.slots = new SlotsConfig(limit, window);
    return this;
  }

  public AirportSpec gate(String gateId) {
    return gate(gateId, 1);
  }

  public AirportSpec gate(String gateId, int slotCost) {
    checkId("gateId", gateId);
    if (slotCost <= 0) {
      throw new IllegalArgumentException("slotCost must be positive");
    }
    if (gates.containsKey(gateId)) {
      throw new IllegalArgumentException("duplicate gate id: " + gateId);
    }
    gates.put(gateId, new Gate(gateId, slotCost));
    return this;
  }

  public AirportSpec pilot(Pilot<?> pilot) {
    this.pilot = pilot;
    return this;
  }

  public AirportSpec reporter(FlightReporter reporter) {
    this.reporter = reporter;
    return this;
  }

  /** Overrides the hold schedule for categories that hold (TRANSIENT, INTERNAL). */
  public AirportSpec holdPolicy(HoldPolicy holdPolicy) {
    this.holdPolicy = holdPolicy;
    return this;
  }

  public String airportId() { return airportId; }

  public String displayName() { return displayName; }

  public SlotsConfig slots() { return slots; }

  public Map<String, Gate> gates() { return Collections.unmodifiableMap(gates); }

  public Pilot<?> pilot() { return pilot; }

  public FlightReporter reporter() { return reporter; }

  /** The hold policy for a category: the override for TRANSIENT/INTERNAL, else the default. */
  public HoldPolicy holdPolicyFor(IncidentCategory category) {
    boolean holds = category == IncidentCategory.TRANSIENT || category == IncidentCategory.INTERNAL;
    return holds && holdPolicy != null ? holdPolicy : HoldPolicy.defaultFor(category);
  }

  /** Throws {@link IllegalStateException} when a required part (slots, pilot, reporter) is missing. */
  public void validate() {
    if (slots == null) {
      throw new IllegalStateException("slots is required for airport " + airportId);
    }
    if (pilot == null) {
      throw new IllegalStateException("pilot is required for airport " + airportId);
    }
    if (reporter == null) {
      throw new IllegalStateException("reporter is required for airport " + airportId);
    }
  }

  private static String checkId(String what, String id) {
    if (id == null || !ID_PATTERN.matcher(id).matches()) {
      throw new IllegalArgumentException(what + " must match " + ID_PATTERN.pattern());
    }
    return id;
  }
}
