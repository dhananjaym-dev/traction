package dev.traction.core.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traction.core.api.AirportStatus;
import dev.traction.core.api.CharterConcurrencyException;
import dev.traction.core.api.StopReason;
import dev.traction.core.charter.CharterBoard;
import dev.traction.core.charter.CharterBoard.CharterSnapshot;
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
import dev.traction.core.registry.AirportAnnouncement.GateAnnouncement;
import dev.traction.core.report.FlightReportMessage;
import dev.traction.core.report.FlightReportMessage.RecordType;
import dev.traction.core.status.ScopeRef;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class AdminControllerTest {

  private static final String AIRPORT = "crm:acme";
  private static final String AIRPORTS = "/traction/api/airports/";
  private static final String REF = "crm:acme/company-sync/run-1";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final AnnouncementProjector fleet = mock(AnnouncementProjector.class);
  private final StatusStore status = mock(StatusStore.class);
  private final TransitionService transitions = mock(TransitionService.class);
  private final IncidentSummaryStore incidents = mock(IncidentSummaryStore.class);
  private final CharterQueryService charters = mock(CharterQueryService.class);
  private final CharterBoard board = mock(CharterBoard.class);
  private final ParkedReplayService replay = mock(ParkedReplayService.class);
  private final ParkedDiscardService discard = mock(ParkedDiscardService.class);
  private final QueueDepthProbe depths = mock(QueueDepthProbe.class);

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = standaloneSetup(new AdminController(fleet, status, transitions, incidents, charters, board,
        replay, discard, depths)).setControllerAdvice(new AdminController.Errors()).build();
    when(fleet.airportIds()).thenReturn(Set.of(AIRPORT));
    when(fleet.declaration(AIRPORT)).thenReturn(Optional.of(AirportAnnouncement.of(AIRPORT, "demo",
        "Acme CRM", 10, 60L, 10,
        List.of(new GateAnnouncement("company-sync", 1), new GateAnnouncement("contact-sync", 3)))));
    when(fleet.declaration("nope")).thenReturn(Optional.empty());
  }

  private static JsonNode json(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private static int code(ResultActions result) {
    return result.andReturn().getResponse().getStatus();
  }

  @Test
  void fleetListsAirportsWithGatesDepthsAndSlots() throws Exception {
    when(status.snapshot(ScopeRef.airport(AIRPORT)))
        .thenReturn(new StatusSnapshot(AirportStatus.OPEN, null, null, null));
    when(status.snapshot(ScopeRef.gate(AIRPORT, "company-sync")))
        .thenReturn(new StatusSnapshot(AirportStatus.OPEN, null, null, null));
    when(status.snapshot(ScopeRef.gate(AIRPORT, "contact-sync"))).thenReturn(new StatusSnapshot(
        AirportStatus.GROUND_STOP, StopReason.GATE_FAULT, "5 in a row", 1_700_000_000_000L));
    when(depths.cachedDepth("tr.crm:acme.runway")).thenReturn(Optional.of(12L));
    when(depths.cachedDepth("tr.crm:acme.holding")).thenReturn(Optional.empty()); // broker unknown
    when(depths.cachedDepth("tr.crm:acme.parked")).thenReturn(Optional.of(4L));

    JsonNode airport = json(mvc.perform(get("/traction/api/airports"))).get(0);

    assertEquals(AIRPORT, airport.get("id").asText());
    assertEquals("Acme CRM", airport.get("displayName").asText());
    assertEquals("OPEN", airport.get("status").asText());
    assertEquals(12, airport.get("depths").get("runway").asInt());
    assertTrue(airport.get("depths").get("holding").isNull(), "an unknown depth is null, never 0");
    assertEquals(4, airport.get("depths").get("parked").asInt());
    assertEquals(10, airport.get("slots").get("limit").asInt());
    assertEquals(60, airport.get("slots").get("windowSeconds").asInt());
    JsonNode gates = airport.get("gates");
    assertEquals("company-sync", gates.get(0).get("id").asText());
    assertEquals("OPEN", gates.get(0).get("status").asText());
    assertEquals(1, gates.get(0).get("slotCost").asInt());
    assertEquals("GROUND_STOP", gates.get(1).get("status").asText());
    assertEquals(3, gates.get(1).get("slotCost").asInt());
    assertEquals("GATE_FAULT", gates.get(1).get("reason").asText());
    assertEquals(1_700_000_000_000L, gates.get(1).get("since").asLong());

    assertEquals(AIRPORT, json(mvc.perform(get(AIRPORTS + AIRPORT))).get("id").asText());
    assertEquals(404, code(mvc.perform(get(AIRPORTS + "nope"))));
  }

  @Test
  void aDepthProbeThatKnowsNothingNeverFailsTheFleetEndpoint() throws Exception {
    when(status.snapshot(any())).thenReturn(new StatusSnapshot(AirportStatus.OPEN, null, null, null));
    when(depths.cachedDepth(anyString())).thenReturn(Optional.empty());

    JsonNode airport = json(mvc.perform(get("/traction/api/airports"))).get(0);

    assertTrue(airport.get("depths").get("runway").isNull());
    assertTrue(airport.get("depths").get("parked").isNull());
  }

  @Test
  void discardWithoutTheConfirmTokenIs400AndDropsNothing() throws Exception {
    for (String payload : new String[] {"{\"confirm\":\"yes\"}", "{}"}) {
      assertEquals(400, code(mvc.perform(post(AIRPORTS + AIRPORT + "/discardParked")
          .contentType(MediaType.APPLICATION_JSON).content(payload))));
    }
    assertEquals(400, code(mvc.perform(post(AIRPORTS + AIRPORT + "/discardParked")))); // no body at all

    verifyNoInteractions(discard);
  }

  @Test
  void discardWithTheTokenDelegatesAndReportsTheCount() throws Exception {
    when(discard.discardParked(AIRPORT, "discard-all")).thenReturn(new DiscardResult(AIRPORT, 7));

    JsonNode result = json(mvc.perform(post(AIRPORTS + AIRPORT + "/discardParked")
        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"discard-all\"}")));

    assertEquals(7, result.get("dropped").asInt());
  }

  @Test
  void replayDelegatesAndAnOperationAlreadyRunningIs409() throws Exception {
    when(replay.replayParked(AIRPORT)).thenReturn(new ReplayResult(AIRPORT, 5, 1));
    JsonNode result = json(mvc.perform(post(AIRPORTS + AIRPORT + "/replayParked")));
    assertEquals(5, result.get("replayed").asInt());
    assertEquals(1, result.get("skipped").asInt());

    doThrow(new ParkedOpInProgressException(AIRPORT)).when(replay).replayParked(AIRPORT);
    assertEquals(409, code(mvc.perform(post(AIRPORTS + AIRPORT + "/replayParked"))));

    doThrow(new IllegalStateException("publish not confirmed")).when(replay).replayParked(AIRPORT);
    assertEquals(500, code(mvc.perform(post(AIRPORTS + AIRPORT + "/replayParked"))));
  }

  @Test
  void unknownAirportOrGateIs404AndNothingIsCalled() throws Exception {
    for (String path : new String[] {"/replayParked", "/reopen", "/gates/company-sync/reopen"}) {
      assertEquals(404, code(mvc.perform(post(AIRPORTS + "nope" + path))));
    }
    assertEquals(404, code(mvc.perform(post(AIRPORTS + "nope/discardParked")
        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"discard-all\"}"))));
    assertEquals(404, code(mvc.perform(post(AIRPORTS + AIRPORT + "/gates/ghost/reopen"))));
    assertEquals(404, code(mvc.perform(get(AIRPORTS + "nope/incidents"))));

    verifyNoInteractions(replay, discard, transitions);
  }

  @Test
  void reopenGoesThroughTheTransitionService() throws Exception {
    when(transitions.reopen(ScopeRef.airport(AIRPORT), "admin api")).thenReturn(true);
    when(transitions.reopen(ScopeRef.gate(AIRPORT, "company-sync"), "admin api")).thenReturn(false);

    assertTrue(json(mvc.perform(post(AIRPORTS + AIRPORT + "/reopen"))).get("reopened").asBoolean());
    assertFalse(json(mvc.perform(post(AIRPORTS + AIRPORT + "/gates/company-sync/reopen")))
        .get("reopened").asBoolean());
  }

  @Test
  void abortDelegatesWithTheActorFromTheHeaderOrAnonymous() throws Exception {
    when(board.abort(REF, "dana")).thenReturn(true);
    when(board.abort(REF, "anonymous")).thenReturn(true);

    JsonNode result = json(mvc.perform(post("/traction/api/charters/" + REF + "/abort").header("X-Actor", "dana")));
    mvc.perform(post("/traction/api/charters/" + REF + "/abort"));

    assertEquals(REF, result.get("charterRef").asText());
    assertEquals("dana", result.get("actor").asText());
    verify(board).abort(REF, "dana");
    verify(board).abort(REF, "anonymous");
  }

  @Test
  void abortMapsUnknownTo404MalformedTo400AndConcurrencyTo409() throws Exception {
    when(board.abort("crm:acme/company-sync/gone", "anonymous")).thenReturn(false);
    assertEquals(404, code(mvc.perform(post("/traction/api/charters/crm:acme/company-sync/gone/abort"))));

    assertEquals(400, code(mvc.perform(post("/traction/api/charters/crm:acme/company-sync/bad:id/abort"))));
    verify(board, never()).abort(eq("crm:acme/company-sync/bad:id"), anyString());

    when(board.abort(REF, "anonymous")).thenThrow(
        new CharterConcurrencyException("crm:acme/company-sync/run-0", "crm:acme/company-sync"));
    assertEquals(409, code(mvc.perform(post("/traction/api/charters/" + REF + "/abort"))));
  }

  @Test
  void incidentsAndChartersAreListedPerAirport() throws Exception {
    FlightReportMessage incident = new FlightReportMessage(RecordType.RECORD, UUID.randomUUID(), AIRPORT,
        "company-sync", "c-1", null, "INCIDENT", "GATE_FAULT", null, "422", null, "bad", 1, 0, "abc",
        UUID.randomUUID(), 1_700_000_000_000L, "PARKED");
    when(incidents.recent(AIRPORT)).thenReturn(List.of(incident));
    when(charters.list(AIRPORT)).thenReturn(List.of(new CharterSnapshot(REF, AIRPORT, "company-sync",
        "run-1", "OPEN", 100, 40, 2, 1, 3, 0, 1L, 2L, null, null, null, null)));

    assertEquals("GATE_FAULT",
        json(mvc.perform(get(AIRPORTS + AIRPORT + "/incidents"))).get(0).get("category").asText());
    JsonNode charter = json(mvc.perform(get("/traction/api/charters").param("airportId", AIRPORT))).get(0);
    assertEquals(REF, charter.get("charterRef").asText());
    assertEquals(100, charter.get("expected").asInt());
    assertEquals(40, charter.get("landed").asInt());
    assertEquals(404, code(mvc.perform(get("/traction/api/charters").param("airportId", "nope"))));
  }
}
