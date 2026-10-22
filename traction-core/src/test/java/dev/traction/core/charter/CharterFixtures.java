package dev.traction.core.charter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.traction.core.api.AirportSpec;
import dev.traction.core.api.CharterConcurrency;
import dev.traction.core.api.CharterDesk;
import dev.traction.core.api.NotifyEvent;
import dev.traction.core.hooks.NotifyDispatcher;
import dev.traction.core.registry.AirportRegistry;
import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.report.FlightReportMessage.RecordType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One charter world for the tests: an in-memory Redis, the real board and desk over it, a
 * registry that knows {@code mail:acme} (gates {@code welcome}, {@code digest}), and reports
 * shaped the way the flight consumer emits them.
 */
final class CharterFixtures {

  static final String AIRPORT = "mail:acme";
  static final String GATE = "welcome";
  static final String OTHER_GATE = "digest";

  final CharterRedisModel redis = new CharterRedisModel();
  final List<NotifyEvent> notified = new ArrayList<>();
  final CharterBoard board = new CharterBoard(redis.redis, new NotifyDispatcher(List.of(notified::add)));
  final AirportRegistry registry = mock(AirportRegistry.class);
  final CharterEventPublisher events = mock(CharterEventPublisher.class);
  final List<CharterEventMessage> published = new ArrayList<>();
  final CharterDesk desk = new CharterDesk(new CharterOpener(registry, redis.redis, events));

  CharterFixtures(CharterConcurrency policy) {
    when(registry.gatesOf(AIRPORT)).thenReturn(Optional.of(Map.of(
        GATE, new AirportSpec.Gate(GATE, 1), OTHER_GATE, new AirportSpec.Gate(OTHER_GATE, 1))));
    when(registry.charterConcurrencyOf(AIRPORT)).thenReturn(Optional.of(policy));
    doAnswer(inv -> published.add(inv.getArgument(0))).when(events).publish(any());
  }

  /** Opens a charter on the {@code welcome} gate and lets the control plane apply its OPENED event. */
  String open(String charterId, long expected) {
    return open(GATE, charterId, expected);
  }

  String open(String gateId, String charterId, long expected) {
    String ref = desk.open(AIRPORT, gateId, charterId, expected);
    board.opened(published.get(published.size() - 1));
    return ref;
  }

  CharterBoard.CharterSnapshot snapshot(String ref) {
    return board.snapshot(ref).orElseThrow();
  }

  List<NotifyEvent.Type> notifiedTypes() {
    return notified.stream().map(NotifyEvent::type).toList();
  }

  static FlightReportMessage report(RecordType type, String ref, UUID flightId, String outcome,
      String category, String reason, String disposition) {
    return new FlightReportMessage(type, flightId, AIRPORT, GATE, "user-1", ref, outcome, category,
        reason, null, null, null, 1, 0, "abc123", UUID.randomUUID(), System.currentTimeMillis(),
        disposition);
  }

  static FlightReportMessage landed(String ref, UUID flightId) {
    return report(RecordType.RECORD, ref, flightId, "LANDED", null, null, "TERMINAL");
  }

  /** A GATE_FAULT park: RECORD, disposition PARKED. */
  static FlightReportMessage parked(String ref, UUID flightId) {
    return report(RecordType.RECORD, ref, flightId, "INCIDENT", "GATE_FAULT", null, "PARKED");
  }
}
