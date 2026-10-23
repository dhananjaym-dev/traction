# The problem: failures come in three sizes

*A retry policy treats every failure as the same size. They aren't, and the size decides the
right move.*

A queue consumer calling a third party sees one thing when it fails: an exception. But that
exception can mean "this record is bad", "this kind of request is broken", or "nothing will
work until a human fixes the credentials". Retrying all three the same way is how 1,000
messages turn into 4,000 failed calls and a dead-letter queue someone shovels back by hand.

Traction names the three sizes after the scope they break: **airport** (the whole dependency),
**gate** (one work type), and **flight** (one message). The [concepts page](concepts.md) has the
full vocabulary.

## Story one: the CRM sync

*Two work types (contact sync, company sync) against one CRM account.*

| | Airport-sized | Gate-sized | Flight-sized |
|---|---|---|---|
| **What broke** | The API token expired | The company endpoint changed its contract | One contact has an invalid email |
| **HITS** | Every call, both work types | Every company sync; contacts are fine | That one record |
| **Naive consumer** | Retries all 1,000 messages to the DLQ | Burns company retries, alerts per message | Retries a record that can never succeed |
| **RIGHT MOVE** | Stop the whole airport on the first failure; everything waits on the runway | Park company syncs, stop that gate after a streak, keep contacts landing | Reject it, keep going, re-drive from the source once the data is fixed |
| **You report** | `AIRPORT_FAULT` | `GATE_FAULT` | `FLIGHT_FAULT` |
| **Recovery** | Fix the token, one reopen curl | Fix the contract, reopen the gate, replay what parked | Fix the record upstream |

## Story two: the bulk email run

*The same shapes, again. A welcome-email run of 50,000 messages against one email provider.*

| | Airport-sized | Gate-sized | Flight-sized |
|---|---|---|---|
| **What broke** | The sending account was suspended | The welcome template was deleted | One address is malformed |
| **HITS** | Every email of every kind | Every welcome email; password resets still send | That one address |
| **Naive consumer** | 50,000 doomed sends, each retried | Welcome emails fail one by one, for hours | Retries a send that can never succeed |
| **RIGHT MOVE** | Stop the airport; the run waits, nothing is lost | Stop the welcome gate; other mail keeps flowing | Reject it; the run's tally counts it as rejected |
| **You report** | `AIRPORT_FAULT` | `GATE_FAULT` | `FLIGHT_FAULT` |
| **Recovery** | Reinstate the account, reopen | Restore the template, reopen, replay | Nothing to replay; fix the list |

Two different vendors, the same three cards. That is the point: the size of a failure is a
property of *how your work depends on the vendor*, not of the vendor. So it can be declared
once, in an `AirportSpec`, and acted on by the framework.

## And the failures that aren't a size

*Some failures say nothing about scope. They say "not right now".*

| You report | It means | Traction does |
|---|---|---|
| `TRANSIENT` | A timeout or 5xx blip | Holds the flight with backoff (30 s, 2 min, 10 min), then parks it |
| `RATE_LIMIT` | The vendor throttled you | Pauses the airport for one rate-limit window (a ground delay) and requeues the flight; never a failure |
| `INTERNAL` | Your own bug, not the vendor's | Holds once (30 s), then parks it for a human |

A streak of `FLIGHT_FAULT`s or `INTERNAL`s (5 distinct records in 5 minutes) also stops the
gate: when every record is "bad", the records probably aren't the problem.

## Why this needs a framework

*Containment is shared state. A single consumer can't see the pattern alone.*

One consumer thread sees one exception. Seeing that 3 *different* company records failed the
same way in 5 minutes, across every pod, takes a shared counter. Stopping every pod at once
takes a shared status. Pacing every pod to one vendor quota takes a shared token bucket. Then
someone has to notice, notify once (not 1,000 times), and give an operator a way to resume.

That is a lot of plumbing to write per consumer. Traction is that plumbing, written once, on
the broker and the Redis you already run. Next: [concepts](concepts.md), then
[the life of a flight](life-of-a-flight.md).
