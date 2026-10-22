package dev.traction.core.topology;

/**
 * Queue names for one airport. Everything uses the default exchange, so routing key == queue
 * name. {@code parked} is where flights wait for replay or discard.
 */
public record QueueNames(String airportId, String runway, String holding, String parked) {

  private static final String PREFIX = "tr.";
  private static final String TOWER = PREFIX + "tower.queue";
  private static final String DLQ = PREFIX + "dlq";

  public static QueueNames of(String airportId) {
    String base = PREFIX + airportId;
    return new QueueNames(airportId, base + ".runway", base + ".holding", base + ".parked");
  }

  public static String runway(String airportId) {
    return of(airportId).runway();
  }

  public static String holding(String airportId) {
    return of(airportId).holding();
  }

  public static String parked(String airportId) {
    return of(airportId).parked();
  }

  /** The control plane's single event stream: announcements, flight reports, charter events. */
  public static String tower() {
    return TOWER;
  }

  /** Dead-letter target for the tower queue: a message that keeps failing is parked here for a human. */
  public static String deadLetter() {
    return DLQ;
  }
}
