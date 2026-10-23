# Traction

**Automatic failure containment for queue consumers.**

When your async work starts failing, Traction stops the right scope (one work type, or a whole
dependency) instead of burning retries and flooding your alerts. It runs on the RabbitMQ and
Redis you already have, and the control plane lives inside your own service. No new
infrastructure.

---

## Why this exists

At 2 a.m. the CRM's API token expires. A thousand queued contact syncs churn through their
retries, every one of them logs an error, and the next morning someone shovels a dead-letter
queue back by hand, hoping nothing else was in there. The fix next sprint is a hand-rolled
circuit breaker for *that one consumer*, and the same code gets written again for the next one.

Every queue-based system ends up needing the same controls: back off when the dependency is
rate-limited, stop consuming when it's down, park work instead of dropping it, and tell a human
*which* thing broke. Traction makes those controls a declaration instead of a project.

## Quickstart

Protecting a rate-limited dependency takes four short pieces: declare it, send work, do the
work, and report the outcomes. This is the demo's real code
([CrmAirportConfig.java](demo-app/src/main/java/dev/traction/demo/governed/CrmAirportConfig.java)),
trimmed for width.

```java
// 1. Declare what you're protecting (an "airport" = one shared quota, credential set and
//    outage fate) and wire in your two pieces of code. Queues, consumers and the rate
//    limit all come from this.
@Bean
AirportSpec crmAcme() {
    return AirportSpec.of("crm:acme")
            .slots(20, Duration.ofSeconds(1))   // the shared rate limit
            .gate("contact-sync")               // a gate = one independently stoppable work type
            .gate("company-sync")
            .pilot(this::land)                  // your work
            .reporter(this::report);            // your failure classification
}

// 2. Send work from anywhere in your system. The domainRef (contact.id()) is the business id
//    your pilot stays idempotent on: delivery is at least once.
tower.dispatch("crm:acme", "contact-sync", contact, new DispatchOptions(contact.id(), null));

// 3. Do the work. A pilot lands one flight; it never touches the broker.
private static final Map<String, String> PATHS =   // gate -> Acme endpoint
        Map.of("contact-sync", "/contacts", "company-sync", "/companies");

void land(Flight<?> flight) {
    acme.upsert(PATHS.get(flight.gateId()), flight.payload());
}

// 4. Report outcomes: classify every failure to the level it breaks.
FlightReport report(Flight<?> flight, Throwable t) {
    if (t == null)                               return landed();
    if (t instanceof AuthException)              return incident(AIRPORT_FAULT, Evidence.from(t)); // dead token: airport stops
    if (t instanceof ContractException)          return incident(GATE_FAULT, Evidence.from(t));    // a streak stops this gate
    if (t instanceof BadPayloadException)        return incident(FLIGHT_FAULT, Evidence.from(t));  // bad record: rejected
    if (t instanceof VendorUnavailableException) return incident(TRANSIENT, Evidence.from(t));     // blip: hold, then park
    if (t instanceof RateLimitedException)       return incident(RATE_LIMIT, Evidence.from(t));    // throttled: pace down
    return incident(INTERNAL, Evidence.from(t));                                                   // our bug: hold, then park
}
```

That is the whole integration. From the declaration, Traction declares the queues on your
broker, starts the consumers, enforces the rate limit, and makes containment decisions from
your reports.

### Run the demo

You need Docker. The images build with Gradle inside Docker, so no JDK is needed; the scenario
scripts use bash, curl and jq.

```bash
git clone <this repo> traction && cd traction
docker compose up -d --build --wait
./demo/scenario-naive.sh      # a plain @RabbitListener with retry limit + DLQ, token expired
./demo/scenario-traction.sh   # the same 1,000 messages through Traction
./demo/scenario-gate.sh       # one gate breaks, the rest of the airport keeps flying
./demo/scenario-charter.sh    # a 600-email bulk run, aborted mid-way
```

Each script resets the demo first, so run them in any order, as often as you like. A measured
run on a laptop printed this (your numbers will vary):

| Scenario | Last line it printed |
|---|---|
| naive | `lost: 1000 · error lines: ~4000 · recovery: manual` |
| traction | `lost: 0 · notifications: 1 · recovery: one curl` |

The naive consumer's retry limit of 3 means 3 redeliveries, so 4 deliveries in all: every
message calls the dead API 4 times and logs 4 errors. Traction's `notifications: 1` counts
ground-stop notifications; your own reopen curl logs one more line, a `REOPEN`.

While they run, watch the dashboard at <http://localhost:8080/traction>. The RabbitMQ
management UI is at <http://localhost:15673> (guest/guest; set `RABBIT_MGMT_PORT` to move it),
and the simulated vendor at <http://localhost:9090>. RabbitMQ and Redis are not published to
your machine, so the demo won't touch a Redis or RabbitMQ you may already run locally.
`docker compose down -v` removes everything.

## What you get

| You write | You get |
|---|---|
| An `AirportSpec` bean: ids, slots, gates | Queues on your broker (runway, holding, parked), consumers started, one rate limit shared by every pod |
| A `Pilot`: the call, for one flight | Ground stops scoped to the airport or one gate, backoff, parking, replay and discard |
| A `FlightReporter`: what each failure means | Notifications, incident summaries with evidence, bulk-run tracking, a dashboard and an admin API |

- **Scoped containment.** An `AIRPORT_FAULT` (dead credentials) ground-stops the whole airport
  on the first report; a streak of `GATE_FAULT`s stops only that gate while the rest keep
  flying. You reopen explicitly when the dependency recovers.
- **Shared rate limiting.** One token bucket per airport in Redis, enforced across every pod.
  Gates share the airport's slots, and a gate can cost more than one slot per flight.
- **No lost work.** Every flight ends **Landed**, **Skipped**, or **Incident**. A parked flight
  is recoverable, never counted as failed: replay it to the runway, or discard it, explicitly.
- **Bulk runs (charters).** Dispatch a 50k run as one charter and get progress, completion,
  abort, and an optional admission policy (off by default; `.charterConcurrency(PER_KEY)` allows
  one open run per gate).
- **Incident summaries with evidence.** Which gate is failing, with what category and a bounded
  excerpt, from Traction's own state in Redis. No logging stack required.
- **Hooks, not opinions.** Ground stops, incidents and charter events fire a `NotifyHook`;
  every flight row streams through a `FlightEventHook`. Core ships one hook, a log line, and
  never assumes Slack, Prometheus or a log backend.
- **Embedded dashboard.** A read-only status page served by the control plane: the fleet,
  gates, queue depths, parked counts and bulk runs, live. Every operator action is an admin
  API call, so the runbook is curl.
- **Control plane in your own service.** One service sets `traction.control-plane.enabled=true`
  and becomes the brain. No new box in your architecture diagram.

## How it works

Your services host the **data plane**: consumers that get clearance from Redis (a rate-limit
slot and a status check) before each flight, and report every outcome to the **tower**, the
control plane's event queue. The control plane applies your reports to streak rules and flips
the statuses that consumers obey. The broker carries the work; Redis carries the decisions.
Those are the only two dependencies.

**Broker:** RabbitMQ (quorum queues). **Store:** Redis, or a Redis-protocol store that runs Lua
scripts (the demo runs Valkey). **Language:** Java 21 / Spring Boot.

## Status

Reference implementation. The concepts come from a production system; this project is a clean,
runnable reference: read it, run it, adapt it. The admin API has no authentication in v1 (see
[operations](docs/operations.md#securing-it-in-production)). A packaged library may follow,
based on interest.

— *[the problem](docs/the-problem.md) · [concepts & glossary](docs/concepts.md) ·
[life of a flight](docs/life-of-a-flight.md) · [guarantees](docs/guarantees.md) ·
[operations](docs/operations.md) · [why not Temporal?](docs/why-not-temporal.md)*
