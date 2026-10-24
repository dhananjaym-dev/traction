# Why not Temporal?

*Durable-execution runtimes sell a new place to run your code. Traction sells rules for the
broker you already run.*

It's a fair question. Temporal and the other durable-execution runtimes retry failed steps,
survive crashes, and can rate-limit work. If you already run one, should you use it instead?
Sometimes yes. They answer a different question, though.

## Two different products

*One makes a multi-step process survive anything. The other stops one-step work from
hammering a broken dependency.*

| | Durable-execution runtime (Temporal and similar) | Traction |
|---|---|---|
| **Unit of work** | A workflow: many steps, timers, state carried between them | A flight: one call to one dependency |
| **Where it runs** | On a runtime you add (a server cluster or a hosted service) plus its workers | In your existing services, on your RabbitMQ and Redis |
| **What you rewrite** | Consumers become workflows and activities | Nothing: you add a pilot and a reporter |
| **Retry model** | A retry policy per activity | Retry by failure *size*: airport, gate, flight |
| **Shared fate** | Each activity fails and retries on its own | One dead credential stops the whole airport, once |
| **Operator moves** | Inspect, cancel, reset a workflow | Reopen, replay, discard, abort, by curl |

The core difference is the shared-fate column. A durable runtime makes each step robust *on
its own*. When the CRM token expires, 1,000 activities each discover it, each retry on their
own policy, and each fail or wait in turn. You can build a circuit breaker around that, and
then you're writing the containment layer this repo already is.

## Pick a durable runtime when

- The work is a **process**: several steps, compensation on failure, timers that run for days,
  a human approval in the middle.
- You need **state across steps** to survive a crash, not just a message to be redelivered.
- You're ready to run (or pay for) the runtime and move that code onto it.

## Pick Traction when

- The work is **one call per message** to a third party: syncs, notifications, webhooks, bulk
  sends.
- The expensive failures are **shared**: an expired token, a suspended account, a contract
  change on one endpoint.
- You want the fix to be **a declaration** in the service you already deploy, with no new
  cluster and no rewrite.

## Both, together

*They compose.* A workflow step can dispatch flights and let Traction contain the vendor; a
Traction pilot can start a workflow. Use the runtime for the process and Traction for the
dependency.

Traction is deliberately not a worse durable runtime: it has no workflows and no step state,
and it tracks no sequence across flights beyond a [charter](concepts.md#glossary)'s counts.
Back to the [README](https://github.com/dhananjaym-dev/traction#readme).
