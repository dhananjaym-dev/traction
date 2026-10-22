package dev.traction.core.api;

/**
 * Failure detail carried by an incident. All fields nullable. {@code code} is a machine code
 * (HTTP status, exception simple name); {@code messageKey} a stable low-cardinality key safe to
 * group by; {@code excerpt} a bounded sample. Sanitize PII when classifying.
 */
public record Evidence(String code, String messageKey, String excerpt) {

  public static final int MAX_EXCERPT = 300;

  /** Evidence for a throwable: class simple name plus a message excerpt bounded to 300 chars. */
  public static Evidence from(Throwable t) {
    if (t == null) {
      return null;
    }
    String msg = t.getMessage();
    return new Evidence(t.getClass().getSimpleName(), null,
        msg == null ? null : msg.substring(0, Math.min(msg.length(), MAX_EXCERPT)));
  }

  /** Non-blank parts joined with " | "; null when there is nothing to show. */
  public String display() {
    StringBuilder sb = new StringBuilder();
    for (String part : new String[] {code, messageKey, excerpt}) {
      if (part != null && !part.isBlank()) {
        if (sb.length() > 0) {
          sb.append(" | ");
        }
        sb.append(part);
      }
    }
    return sb.length() == 0 ? null : sb.toString();
  }
}
