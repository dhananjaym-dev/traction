package dev.traction.core.hooks;

import dev.traction.core.api.NotifyEvent;
import dev.traction.core.api.NotifyEvent.Type;
import dev.traction.core.api.NotifyHook;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@link NotifyHook}: one log line per event, so a fresh install alerts somewhere
 * before any integration is written. Registered alongside any other hooks (every hook gets
 * every event); opt out with {@code traction.notify.log.enabled=false}.
 *
 * <p>Line format (single line, stable prefix, so scripts and log queries can count events):
 * <pre>
 * traction.notify type=&lt;TYPE&gt; airport=&lt;id&gt; gate=&lt;id&gt; reason=&lt;reason&gt; evidence=&lt;evidence&gt; [&lt;detail k=v&gt;...]
 * traction.notify type=GROUND_STOP airport=crm:acme gate=- reason=AIRPORT_FAULT evidence="401 | token expired" category=AIRPORT_FAULT flight=abc123
 * </pre>
 * An absent value prints {@code -}. A value containing a space, quote or {@code =} is
 * double-quoted with inner quotes escaped; line breaks and tabs become spaces. Detail entries
 * follow in key order. Operator-attention events (GROUND_STOP, FLIGHT_INCIDENT, CHARTER_ABORTED,
 * PARKED_DISCARDED) log at WARN, the rest at INFO.
 */
public class LogNotifyHook implements NotifyHook {

  public static final String PREFIX = "traction.notify";

  private static final Set<Type> WARN = EnumSet.of(
      Type.GROUND_STOP, Type.FLIGHT_INCIDENT, Type.CHARTER_ABORTED, Type.PARKED_DISCARDED);

  private final Logger log;

  public LogNotifyHook() {
    this(LoggerFactory.getLogger(LogNotifyHook.class));
  }

  LogNotifyHook(Logger log) {
    this.log = log;
  }

  @Override
  public void onEvent(NotifyEvent e) {
    try {
      String line = format(e);
      if (WARN.contains(e.type())) {
        log.warn(line);
      } else {
        log.info(line);
      }
    } catch (RuntimeException ignored) {
      // A NotifyHook must never throw; a broken appender has nowhere better to report to.
    }
  }

  static String format(NotifyEvent e) {
    StringBuilder line = new StringBuilder(PREFIX)
        .append(" type=").append(e.type())
        .append(" airport=").append(value(e.airportId()))
        .append(" gate=").append(value(e.gateId()))
        .append(" reason=").append(value(e.reason()))
        .append(" evidence=").append(value(e.evidence() == null ? null : e.evidence().display()));
    if (e.detail() != null) {
      for (Map.Entry<String, String> d : new TreeMap<>(e.detail()).entrySet()) {
        line.append(' ').append(d.getKey()).append('=').append(value(d.getValue()));
      }
    }
    return line.toString();
  }

  private static String value(String raw) {
    if (raw == null || raw.isBlank()) {
      return "-";
    }
    String v = raw.replaceAll("[\\r\\n\\t]+", " ");
    if (v.indexOf(' ') < 0 && v.indexOf('"') < 0 && v.indexOf('=') < 0) {
      return v;
    }
    return '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }
}
