package dev.traction.core.charter;

import dev.traction.core.api.AirportSpec;
import java.util.regex.Pattern;

/**
 * Every charter Redis key and the charterRef format, in one place.
 *
 * <pre>
 *   tr:charter:&lt;ref&gt;           hash    airportId gateId charterId expected status openedAt
 *                                         lastProgressAt landed rejected skipped parked discarded
 *                                         [openNotifiedAt completedAt source abortedAt abortedBy]
 *                                         no TTL while open; 7 days once completed
 *   tr:charter:&lt;ref&gt;:seen      hash    flightId -&gt; the bucket the flight is counted in
 *                                         (the per-flight dedup); expires with the charter hash
 *   tr:charter:&lt;ref&gt;:aborted   string  the aborting actor; the 7-day skip window
 *   tr:charters-open            set     refs of the open charters (completion and the sweep)
 *   tr:charters:&lt;airportId&gt;     zset    every retained charter of the airport, score = openedAt
 *                                         (listing; read-side pruned once a hash has expired)
 *   tr:charter-admission:&lt;airportId&gt;/&lt;gateId&gt;
 *                               string  the open charter holding the gate under PER_KEY;
 *                                         no TTL, released by the charter's completion
 * </pre>
 *
 * <p>{@code charterRef = airportId/gateId/charterId}. Airport and gate ids never contain
 * {@code /} and a charterId contains neither {@code /} nor {@code :}, so the ref always has
 * exactly three segments and a suffixed key ({@code :seen}, {@code :aborted}) can never be some
 * other charter's hash.
 */
public final class CharterKeys {

  public static final Pattern CHARTER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-.]+$");

  public static final String PREFIX = "tr:charter:";
  public static final String OPEN_SET = "tr:charters-open";
  static final String AIRPORT_INDEX_PREFIX = "tr:charters:";
  static final String ADMISSION_PREFIX = "tr:charter-admission:";

  private CharterKeys() { }

  /**
   * {@code airportId/gateId/charterId}.
   *
   * @throws IllegalArgumentException an id does not match its pattern
   */
  public static String composeRef(String airportId, String gateId, String charterId) {
    check("airportId", airportId, AirportSpec.ID_PATTERN);
    check("gateId", gateId, AirportSpec.ID_PATTERN);
    check("charterId", charterId, CHARTER_ID_PATTERN);
    return airportId + "/" + gateId + "/" + charterId;
  }

  public static String key(String charterRef) {
    return PREFIX + charterRef;
  }

  public static String seenKey(String charterRef) {
    return key(charterRef) + ":seen";
  }

  public static String abortedKey(String charterRef) {
    return key(charterRef) + ":aborted";
  }

  public static String airportIndexKey(String airportId) {
    return AIRPORT_INDEX_PREFIX + airportId;
  }

  /** The admission key of a scope {@code airportId/gateId}. */
  public static String admissionKey(String scopeKey) {
    return ADMISSION_PREFIX + scopeKey;
  }

  /** The {@code airportId/gateId} scope of a ref (the whole ref when it has no {@code /}). */
  static String scopeOf(String charterRef) {
    int last = charterRef.lastIndexOf('/');
    return last < 0 ? charterRef : charterRef.substring(0, last);
  }

  private static void check(String what, String id, Pattern pattern) {
    if (id == null || !pattern.matcher(id).matches()) {
      throw new IllegalArgumentException(what + " must match " + pattern.pattern() + " but was: " + id);
    }
  }
}
