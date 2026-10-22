package dev.traction.core.hooks;

import dev.traction.core.api.NotifyEvent;
import dev.traction.core.api.NotifyHook;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers each {@link NotifyEvent} to every {@link NotifyHook} bean, in order. A hook that
 * throws is logged and skipped, so one broken integration never starves the others or fails
 * the transition that raised the event.
 */
@Slf4j
public class NotifyDispatcher {

  private final List<NotifyHook> hooks;

  public NotifyDispatcher(List<NotifyHook> hooks) {
    this.hooks = List.copyOf(hooks);
  }

  public void dispatch(NotifyEvent event) {
    for (NotifyHook hook : hooks) {
      try {
        hook.onEvent(event);
      } catch (RuntimeException e) {
        log.warn("NotifyHook {} threw on {}; skipped", hook.getClass().getName(), event.type(), e);
      }
    }
  }
}
