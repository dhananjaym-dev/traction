package dev.traction.core.api;

/**
 * Classifies the outcome of a landing into a {@link FlightReport}. {@code error} is null when
 * the pilot returned normally. Must not return null; a null or a throw is treated as INTERNAL.
 */
@FunctionalInterface
public interface FlightReporter {
  FlightReport report(Flight<?> flight, Throwable error);
}
