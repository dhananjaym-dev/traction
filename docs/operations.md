# Operations

*Every operator action is one curl. The dashboard only watches.*

The admin API lives on the control-plane service under `/traction/api`. Examples use the demo's
`http://localhost:8080`; ids in a path go in as typed (`crm:acme`, no encoding).

## Look

```bash
curl -s http://localhost:8080/traction/api/airports | jq             # the fleet: status, gates, depths, slots
curl -s http://localhost:8080/traction/api/airports/crm:acme | jq    # one airport
curl -s http://localhost:8080/traction/api/airports/crm:acme/incidents | jq          # newest 100 incident rows
curl -s 'http://localhost:8080/traction/api/charters?airportId=mail:acme' | jq       # charters, newest first
```

A stopped airport or gate carries `reason` and `detail`, for example
`"detail":"3 distinct GATE_FAULT failures in 5m flight=7f6d67"`. Queue depths are cached for
up to 10 s, and `null` means the broker couldn't say (never a made-up zero).

## Act

*Fix the cause first, then reopen, then replay. Replaying into a stopped gate just parks the
flights again.*

```bash
# Reopen an airport (after AIRPORT_FAULT)
curl -X POST http://localhost:8080/traction/api/airports/crm:acme/reopen

# Reopen one gate (after a streak); also clears that gate's streaks
curl -X POST http://localhost:8080/traction/api/airports/crm:acme/gates/company-sync/reopen

# Replay every parked flight of an airport back to its runway
curl -X POST http://localhost:8080/traction/api/airports/crm:acme/replayParked

# Abort a charter; X-Actor is optional and recorded as abortedBy (default "anonymous")
curl -X POST -H 'X-Actor: dana' \
  http://localhost:8080/traction/api/charters/mail:acme/welcome/run-1792774953/abort
```

The charter path is the `charterRef` (`airportId/gateId/charterId`) spelled as three segments.

### Discard parked flights

This is the only action that destroys work, so it requires a confirm token:

```bash
curl -X POST http://localhost:8080/traction/api/airports/crm:acme/discardParked \
  -H 'Content-Type: application/json' -d '{"confirm":"discard-all"}'
```

### Responses

| Action | Success body | Errors |
|---|---|---|
| reopen | `{"airportId":"crm:acme","reopened":true}` (`false` = already open) | 404 unknown airport or gate |
| replayParked | `{"airportId":"crm:acme","replayed":50,"skipped":0}` | 404; 409 a replay or discard is already running; 500 the broker didn't confirm a republish |
| discardParked | `{"airportId":"crm:acme","dropped":7}` | 400 token missing or wrong (nothing dropped); 404; 409 |
| abort | `{"charterRef":"…","aborted":true,"actor":"dana"}` | 400 malformed id; 404 no such charter |

Errors come back as `{"error":"…"}`.

## Notifications

*One log line per event out of the box; add your own hook for anything else.*

The default `LogNotifyHook` writes one line per event, with a stable prefix you can count or
alert on:

```
traction.notify type=GROUND_STOP airport=crm:acme gate=- reason=AIRPORT_FAULT evidence="AuthException | 401 {\"error\":\"token expired\"}" category=AIRPORT_FAULT flight=b4d5fd
```

The shape is `traction.notify type=<TYPE> airport=<id> gate=<id> reason=<r> evidence=<e>`
followed by detail `key=value` pairs in key order; an absent value prints `-`. Types:
`GROUND_STOP`, `REOPEN`, `FLIGHT_INCIDENT` (at most 25 an hour per airport and disposition), `CHARTER_OPENED`,
`CHARTER_COMPLETED`, `CHARTER_ABORTED`, `PARKED_REPLAYED`, `PARKED_DISCARDED`. To add your own,
declare a `NotifyHook` bean; every hook gets every event, beside the log hook:

```java
@Bean
NotifyHook groundStopsToChat(RestClient.Builder http) {
    RestClient chat = http.baseUrl("https://chat.example.com").build();
    return event -> {
        if (event.type() != NotifyEvent.Type.GROUND_STOP) {
            return;
        }
        try {
            chat.post().uri("/hooks/ops")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", event.airportId() + " stopped: " + event.reason()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // a hook must never throw; Traction logs and skips one that does
        }
    };
}
```

Hooks run inline on the control plane's thread, so hand slow work off. Every flight row
(including `INTERIM` ones) also goes to any `FlightEventHook` bean, on the data plane.

## Configuration

| Property | Default | What it does |
|---|---|---|
| `traction.control-plane.enabled` | `false` | Makes this service the control plane (rules, admin API, dashboard). Exactly one service. |
| `traction.enabled` | `true` | `false` declares the queues but starts no consumers, so flights wait on the runway. |
| `traction.notify.log.enabled` | `true` | `false` removes the default log hook. |
| `traction.control-plane.status-sweep-interval` | `60s` | How often stored statuses are re-broadcast. |
| `traction.control-plane.charter-sweep-interval` | `1h` | How often open charters are swept. |
| `traction.control-plane.charter-stale-after` | `24h` | A charter idle this long on a drained gate is closed (`source=SWEEP`). |

Log lines of a flight carry its ids in MDC as `correlationId` and `flightId`; the demo's
pattern prints them as `[cid=… flight=…]`.

## Dashboard tour

*Open <http://localhost:8080/traction>: it polls the admin API every 3 seconds.*

- **Tiles:** airports, airports stopped, gates stopped, parked flights, open charters.
- **Fleet:** one row per airport (status, runway, holding and parked depths, slots), with a row
  per gate (status, slot cost, reason).
- **Incidents and charters:** each airport's recent incidents with their evidence, and its
  charters as progress bars (landed, rejected, skipped, parked, discarded, against expected).

It has no buttons: actions stay in the API. The RabbitMQ management UI
(<http://localhost:15673> in the demo) shows the raw queues: `tr.<airportId>.runway`,
`.holding`, `.parked`, `tr.tower.queue` and `tr.dlq`.

## Securing it in production

*v1 ships no authentication on the admin API or the dashboard.*

Anyone who can reach `/traction/**` on the control-plane service can reopen, replay, abort and
discard. Put those paths behind your own security chain (for example a Spring Security rule
that requires an operator role for `/traction/**`), keep them off the public ingress, or both.
Traction deliberately doesn't ship its own auth: it's your service, so it's your login.
