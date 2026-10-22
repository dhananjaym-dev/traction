package dev.traction.core.api;

/** Receives {@link NotifyEvent}s (alerting integration point). Implementations must not throw. */
@FunctionalInterface
public interface NotifyHook {
  void onEvent(NotifyEvent e);
}
