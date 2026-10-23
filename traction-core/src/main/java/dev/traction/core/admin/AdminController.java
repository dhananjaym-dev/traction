package dev.traction.core.admin;

import dev.traction.core.admin.AdminDtos.AbortResponse;
import dev.traction.core.admin.AdminDtos.AirportView;
import dev.traction.core.admin.AdminDtos.Depths;
import dev.traction.core.admin.AdminDtos.DiscardRequest;
import dev.traction.core.admin.AdminDtos.DiscardResponse;
import dev.traction.core.admin.AdminDtos.ErrorResponse;
import dev.traction.core.admin.AdminDtos.GateView;
import dev.traction.core.admin.AdminDtos.ReopenResponse;
import dev.traction.core.admin.AdminDtos.ReplayResponse;
import dev.traction.core.admin.AdminDtos.Slots;
import dev.traction.core.api.CharterConcurrencyException;
import dev.traction.core.charter.CharterBoard;
import dev.traction.core.charter.CharterBoard.CharterSnapshot;
import dev.traction.core.charter.CharterKeys;
import dev.traction.core.charter.CharterQueryService;
import dev.traction.core.controlplane.AnnouncementProjector;
import dev.traction.core.controlplane.DiscardResult;
import dev.traction.core.controlplane.IncidentSummaryStore;
import dev.traction.core.controlplane.ParkedDiscardService;
import dev.traction.core.controlplane.ParkedOpInProgressException;
import dev.traction.core.controlplane.ParkedReplayService;
import dev.traction.core.controlplane.QueueDepthProbe;
import dev.traction.core.controlplane.ReplayResult;
import dev.traction.core.controlplane.StatusStore;
import dev.traction.core.controlplane.StatusStore.StatusSnapshot;
import dev.traction.core.controlplane.TransitionService;
import dev.traction.core.registry.AirportAnnouncement;
import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.status.ScopeRef;
import dev.traction.core.topology.QueueNames;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The control plane's REST surface, under {@code /traction/api}: the fleet (read from the
 * announcement projection, so the control plane never needs an airport's code), manual reopen,
 * parked replay and discard, incidents, charters and charter abort. No authentication is applied
 * here; put the path behind your own security.
 *
 * <p>An airport (or gate) that was never announced is a 404 and nothing is touched. The one
 * destructive call, discard, checks its confirm token here, before any other work, so a missing
 * or wrong token is a 400 that never reaches the service.
 */
@Slf4j
@RestController
@RequestMapping("/traction/api")
public class AdminController {

  static final String REOPEN_NOTE = "admin api";
  static final String DEFAULT_ACTOR = "anonymous";

  private final AnnouncementProjector fleet;
  private final StatusStore statusStore;
  private final TransitionService transitions;
  private final IncidentSummaryStore incidents;
  private final CharterQueryService charters;
  private final CharterBoard charterBoard;
  private final ParkedReplayService replay;
  private final ParkedDiscardService discard;
  private final QueueDepthProbe depths;

  public AdminController(AnnouncementProjector fleet, StatusStore statusStore,
      TransitionService transitions, IncidentSummaryStore incidents, CharterQueryService charters,
      CharterBoard charterBoard, ParkedReplayService replay, ParkedDiscardService discard,
      QueueDepthProbe depths) {
    this.fleet = fleet;
    this.statusStore = statusStore;
    this.transitions = transitions;
    this.incidents = incidents;
    this.charters = charters;
    this.charterBoard = charterBoard;
    this.replay = replay;
    this.discard = discard;
    this.depths = depths;
  }

  @GetMapping("/airports")
  public List<AirportView> airports() {
    List<AirportView> out = new ArrayList<>();
    for (String id : fleet.airportIds()) {
      fleet.declaration(id).ifPresent(declaration -> out.add(view(declaration)));
    }
    return out;
  }

  @GetMapping("/airports/{airportId}")
  public AirportView airport(@PathVariable String airportId) {
    return view(declared(airportId));
  }

  @PostMapping("/airports/{airportId}/reopen")
  public ReopenResponse reopen(@PathVariable String airportId) {
    declared(airportId);
    return new ReopenResponse(airportId, null,
        transitions.reopen(ScopeRef.airport(airportId), REOPEN_NOTE));
  }

  @PostMapping("/airports/{airportId}/gates/{gateId}/reopen")
  public ReopenResponse reopenGate(@PathVariable String airportId, @PathVariable String gateId) {
    boolean known = declared(airportId).gates().stream().anyMatch(g -> gateId.equals(g.gateId()));
    if (!known) {
      throw new NotFoundException("unknown gate " + gateId + " on airport " + airportId);
    }
    return new ReopenResponse(airportId, gateId,
        transitions.reopen(ScopeRef.gate(airportId, gateId), REOPEN_NOTE));
  }

  @PostMapping("/airports/{airportId}/replayParked")
  public ReplayResponse replayParked(@PathVariable String airportId) {
    declared(airportId);
    ReplayResult result = replay.replayParked(airportId);
    return new ReplayResponse(result.airportId(), result.replayed(), result.skipped());
  }

  @PostMapping("/airports/{airportId}/discardParked")
  public DiscardResponse discardParked(@PathVariable String airportId,
      @RequestBody(required = false) DiscardRequest request) {
    if (request == null || !ParkedDiscardService.CONFIRM_TOKEN.equals(request.confirm())) {
      throw new IllegalArgumentException(
          "discard requires {\"confirm\":\"" + ParkedDiscardService.CONFIRM_TOKEN + "\"}");
    }
    declared(airportId);
    DiscardResult result = discard.discardParked(airportId, request.confirm());
    return new DiscardResponse(result.airportId(), result.dropped());
  }

  @GetMapping("/airports/{airportId}/incidents")
  public List<FlightReportMessage> incidents(@PathVariable String airportId) {
    declared(airportId);
    return incidents.recent(airportId);
  }

  /** One airport's charters (newest first), or every airport's when {@code airportId} is omitted. */
  @GetMapping("/charters")
  public List<CharterSnapshot> charters(@RequestParam(required = false) String airportId) {
    if (airportId != null) {
      declared(airportId);
      return charters.list(airportId);
    }
    List<CharterSnapshot> all = new ArrayList<>();
    for (String id : fleet.airportIds()) {
      all.addAll(charters.list(id));
    }
    return all;
  }

  /**
   * Aborts a charter. The charterRef {@code airportId/gateId/charterId} is spelled as three path
   * segments (no URL-encoded slash, which servlet containers reject by default):
   * {@code POST /traction/api/charters/crm:acme/company-sync/run-1/abort}.
   */
  @PostMapping("/charters/{airportId}/{gateId}/{charterId}/abort")
  public AbortResponse abort(@PathVariable String airportId, @PathVariable String gateId,
      @PathVariable String charterId,
      @RequestHeader(value = "X-Actor", required = false) String actorHeader) {
    String ref = CharterKeys.composeRef(airportId, gateId, charterId); // 400 when malformed
    String actor = actorHeader == null || actorHeader.isBlank() ? DEFAULT_ACTOR : actorHeader.trim();
    if (!charterBoard.abort(ref, actor)) {
      throw new NotFoundException("no charter " + ref);
    }
    return new AbortResponse(ref, true, actor);
  }

  private AirportAnnouncement declared(String airportId) {
    return fleet.declaration(airportId)
        .orElseThrow(() -> new NotFoundException("unknown airport " + airportId));
  }

  private AirportView view(AirportAnnouncement a) {
    String id = a.airportId();
    StatusSnapshot airport = statusStore.snapshot(ScopeRef.airport(id));
    List<GateView> gates = a.gates().stream().map(g -> {
      StatusSnapshot s = statusStore.snapshot(ScopeRef.gate(id, g.gateId()));
      return new GateView(g.gateId(), s.status().name(), g.slotCost(),
          s.reason() == null ? null : s.reason().name(), s.detail(), s.sinceEpochMillis());
    }).toList();
    QueueNames queues = QueueNames.of(id);
    Depths queueDepths = new Depths(depths.cachedDepth(queues.runway()).orElse(null),
        depths.cachedDepth(queues.holding()).orElse(null),
        depths.cachedDepth(queues.parked()).orElse(null));
    return new AirportView(id, a.displayName(), airport.status().name(),
        airport.reason() == null ? null : airport.reason().name(), airport.detail(),
        airport.sinceEpochMillis(), gates, queueDepths,
        new Slots(a.slotsLimit(), a.slotsWindowSeconds()));
  }

  /** An airport, gate or charter the control plane does not know. */
  static class NotFoundException extends RuntimeException {
    NotFoundException(String message) {
      super(message);
    }
  }

  /**
   * HTTP mapping for this controller only. The in-progress subclass is mapped on its own, so it
   * is a 409 and not the generic 500 of its {@code IllegalStateException} parent. A broker
   * failure ({@code AmqpException}) is the same documented 500 {@code {"error": ...}} body.
   */
  @RestControllerAdvice(assignableTypes = AdminController.class)
  public static class Errors {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
      return error(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
      return error(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(ParkedOpInProgressException.class)
    ResponseEntity<ErrorResponse> inProgress(ParkedOpInProgressException e) {
      return error(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(CharterConcurrencyException.class)
    ResponseEntity<ErrorResponse> concurrency(CharterConcurrencyException e) {
      return error(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResponse> failed(IllegalStateException e) {
      log.warn("admin operation failed: {}", e.getMessage());
      return error(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    @ExceptionHandler(AmqpException.class)
    ResponseEntity<ErrorResponse> brokerFailed(AmqpException e) {
      log.warn("admin operation failed: {}", e.getMessage());
      return error(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, RuntimeException e) {
      return ResponseEntity.status(status).body(new ErrorResponse(e.getMessage()));
    }
  }
}
