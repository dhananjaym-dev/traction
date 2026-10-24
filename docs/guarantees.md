---
description: "What Traction promises, stated precisely: at-least-once delivery, every flight accounted for, balanced charter books, and the known limits of v1."
---

# Guarantees

*What Traction promises, stated precisely, and where the promises stop.*

## Delivery: at least once

*A flight can arrive twice. It never silently disappears.*

Every flight is delivered **at least once**. Duplicates are rare but real: a consumer that
crashes before its ack, a deferral whose republish fails and falls back to a broker requeue, a
replay interrupted between its runway publish and its ack. So **pilots must be idempotent on
`domainRef`**, the business id you pass at dispatch:

```java
tower.dispatch("crm:acme", "contact-sync", contact, new DispatchOptions(contact.id(), null));
```

Key your upserts on it. Don't key them on `flightId`: that is minted per dispatch, so two
dispatches of the same contact are two flights.

## Every flight is accounted for

*Each flight ends in exactly one outcome, with a disposition that says whether it's done.*

| Outcome | Disposition | Means |
|---|---|---|
| `LANDED` | `TERMINAL` | Done |
| `SKIPPED` | `TERMINAL` | Deliberately not flown and not kept (a flight of an aborted charter) |
| `SKIPPED` | `PARKED` | Not flown yet: a stopped gate's flight, parked for replay |
| `INCIDENT` | `TERMINAL` | Rejected (`FLIGHT_FAULT`): no retry as-is |
| `INCIDENT` | `PARKED` | Waiting for a human; recoverable, **never counted as failed** |
| `INCIDENT` | `RETRYING` | Still in progress (an `INTERIM` row) |

**INTERIM rows** describe a step, not an outcome: `HELD_1`, `HELD_2`, … (a holding round) and
`DEFERRED` (back to the runway tail after `AIRPORT_FAULT` or `RATE_LIMIT`). They carry outcome
`INCIDENT` and disposition `RETRYING`, they reach your `FlightEventHook` and the incident list,
and they are **never counted**. Only `RECORD` rows count.

**Unreadable is not rejected.** Only a payload whose JSON won't convert to its stamped class is
rejected (`FLIGHT_FAULT`). A stamped class that doesn't load in this deploy (renamed, moved,
deleted) parks the flight as `INTERNAL`, so a replay after the next deploy recovers it. A message
that isn't a flight for its airport at all is parked as it came, with no row. Traction reads the
envelope with its own JSON mapper (Jackson defaults), whatever each service's `ObjectMapper`
settings.

## The charter books balance

*For a bulk run, the counts add up to what you said you'd send.*

When a charter reaches completion:

```
landed + rejected + skipped + parked + discarded == expected
```

- A flight's bucket is decided by its disposition first (`PARKED` counts as parked, whatever
  the outcome), then its outcome. Counting is deduplicated per `flightId`, so a duplicate row
  never counts twice.
- **Replay** un-counts a parked flight before republishing it, so its next outcome counts once.
  **Discard** moves it from `parked` to `discarded`.
- **Completion fires once**, on a latch, the moment the books fill: `CHARTER_COMPLETED` with
  `source=LATCH`.
- **After an abort**, each of the run's remaining flights is counted `SKIPPED` as it reaches a
  consumer, and then acked without flying: it is accounted for, but not kept, and nothing can
  replay it. The skip window lasts 7 days, so late flights of the run are skipped too.
  Completion fires when those skipped rows fill the books, with `source=ABORT`. Until then the
  charter reads `OPEN` with `abortedBy` set. An aborted run can't be resumed: to redo the
  skipped work, re-dispatch it from your source as a new charter.
- **Backstop:** a charter with no progress for 24 hours on a drained gate is closed by the
  hourly sweep with `source=SWEEP`. SWEEP means the books never added up and it was closed
  anyway.

## Discard is the only destructive action

*Discard is the one action that drops work Traction still holds. Abort skips work on purpose,
and counts it.*

Reopen and replay lose nothing. Abort is deliberate and accounted: the run's remaining flights
settle `SKIPPED` and are counted, but are not kept, so re-dispatch from the source to redo
them. `discardParked` drops flights Traction is still holding (an airport's parked queue) for
good, so it requires the body `{"confirm":"discard-all"}`; anything else is an HTTP 400 and
drops nothing. Discarded flights that belong to a charter are tallied as `discarded`. See the
[runbook](operations.md#discard-parked-flights).

## When Redis is unreachable

*Pacing fails closed; status reads fail open.*

- **Clearance** can't get a slot, so the flight is deferred to the runway tail. It is never
  flown unpaced.
- **Status and charter-abort reads** fail open: an unreadable status reads `OPEN`.
- **Replay and discard** won't start without their lock.
- **Reports** the control plane can't apply are redelivered (up to 20 times, then `tr.dlq`).

## Known limits

*Honest edges of v1. Each is a deliberate trade, not an accident.*

- **Head-of-line holds.** Each airport has one holding queue with per-message expiry, and
  RabbitMQ only expires the message at the head. A 30 s hold can wait behind a 10 min hold.
- **Dead-letter hops are at-most-once.** A hold returns to the runway, and a crash-looping
  flight reaches parked, by quorum-queue dead-lettering, which RabbitMQ does at-most-once by
  default. A broker failure during that hop can drop the message.
- **The parked-op lock has a 15-minute TTL.** Replay and discard run one at a time per airport
  within it. An operation that runs past 15 minutes is no longer exclusive: it is not a strict
  mutex beyond the TTL.
- **Only Tower should publish to `tr.*` queues.** A flight's `payloadType` names the class the
  consumer converts the payload into. Treat those queues as internal; don't let other
  publishers write to them.
- **One hold override.** `AirportSpec.holdPolicy(...)` replaces the schedule for both
  `TRANSIENT` and `INTERNAL`.
