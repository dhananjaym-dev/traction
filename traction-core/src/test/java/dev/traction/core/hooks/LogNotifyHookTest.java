package dev.traction.core.hooks;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.traction.core.api.Evidence;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.api.NotifyEvent.Type;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/** One line per event with a stable prefix, so scripts and log queries can count them. */
class LogNotifyHookTest {

  private final Logger logger = mock(Logger.class);
  private final LogNotifyHook hook = new LogNotifyHook(logger);

  @Test
  void aGroundStopLogsOneWarnLineWithScopeReasonAndEvidence() {
    hook.onEvent(new NotifyEvent(Type.GROUND_STOP, "crm:acme", null, "AIRPORT_FAULT",
        new Evidence("401", null, "token expired"),
        Map.of("flight", "abc123", "category", "AIRPORT_FAULT")));

    verify(logger).warn("traction.notify type=GROUND_STOP airport=crm:acme gate=- "
        + "reason=AIRPORT_FAULT evidence=\"401 | token expired\" category=AIRPORT_FAULT flight=abc123");
  }

  @Test
  void routineEventsLogInfoAndValuesNeverBreakTheLine() {
    hook.onEvent(new NotifyEvent(Type.REOPEN, "crm:acme", "company-sync", "manual",
        new Evidence(null, null, "line one\nline \"two\""), null));

    verify(logger).info("traction.notify type=REOPEN airport=crm:acme gate=company-sync "
        + "reason=manual evidence=\"line one line \\\"two\\\"\"");
    verify(logger, never()).warn(anyString());
  }

  @Test
  void aLoggingFailureNeverEscapesTheHook() {
    doThrow(new IllegalStateException("appender down")).when(logger).warn(anyString());

    hook.onEvent(new NotifyEvent(Type.GROUND_STOP, "crm:acme", null, null, null, null));
  }
}
