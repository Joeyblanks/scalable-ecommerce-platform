# 009. Payment service: charging exactly once, cancellation races, provider interface

- Status: Accepted
- Date: 2026-11-15

## Context

payment-service is the last step of the order saga (ADR 007). It receives
`OrderPlaced`, charges the customer through a payment provider, and answers with
`PaymentSucceeded` or `PaymentFailed`. It also receives `OrderCancelled`, which
can arrive before, during or after the charge, and must then make sure the
customer does not stay charged.

Two facts make this hard. A call to a payment provider cannot be part of a
database transaction, and it can time out after the provider already took the
money. And RabbitMQ delivers at least once, in no guaranteed order. The one
rule we cannot break is: **never charge an order twice.**

## Decisions

### 1. One row per order is the single meeting point

The `payments` table has a **unique `order_id`**. Every event handler and every
worker changes that one row, and every change is a single conditional update
(`UPDATE ... WHERE status = '...'`). A caller writes a side effect (an outbox
event) only when its own update changed the row. That is the same rule that
fixed the order-service race (ADR 007, section 5).

States: `CHARGING`, then `SUCCEEDED`, `FAILED` (provider declined),
`CANCELLED` (voided: nothing was charged), `REFUND_PENDING`, `REFUNDED`, and
`NEEDS_REVIEW` (see section 4). Customers see a simpler set: `PROCESSING`,
`PAID`, `FAILED`, `CANCELLED`, `REFUNDING`, `REFUNDED`.

### 2. Never charging twice: four independent layers

1. **Unique `order_id`.** A second `OrderPlaced` for an order, with the same or a
   different event id, finds the existing row and does nothing.
2. **Claim with a lease.** `OrderPlaced` does not charge. It only records the
   payment and the event id, in one short transaction. A worker then *claims* due
   payments (`FOR UPDATE SKIP LOCKED`, sets a 2-minute lease, counts the attempt).
   Another worker cannot take a payment whose lease is valid. If a worker dies,
   the lease expires and another one takes over.
3. **Idempotency key on the provider call.** The key is `charge-<payment id>`:
   the same for every attempt at this payment, different for every payment. A
   retry after a timeout or a crash gets the original result back from the
   provider instead of a second charge.
4. **Conditional completion.** Recording the result is
   `... WHERE status = 'CHARGING'`, and the outbox event is written only if that
   update changed a row. A second completion (for example after a lease takeover)
   changes nothing and announces nothing.

No database transaction is ever open during a provider call. That costs a
fraction of a second of latency (the worker polls every 500 ms) and buys
crash safety: a crash at any point is recovered by the lease expiring, and the
retry is safe because of layer 3.

### 3. Cancellation: before, during and after the charge

`OrderCancelled` works on the same row, trying the following in order. Each
statement is conditional on the state it expects and states only move forward,
so whichever applies wins, and if a worker moves the row between two statements
the next statement catches the new state.

| `OrderCancelled` arrives... | What happens |
|---|---|
| **before `OrderPlaced`** (no row yet) | A *tombstone* row is created, already `CANCELLED`. When `OrderPlaced` arrives later it finds the row, never charges, and fills in the order's details. Both events use the same unique row, so they cannot miss each other. |
| after `OrderPlaced`, before any worker claimed it | `CANCELLED`: voided, nothing charged |
| **during the charge** (a provider call may be in flight) | The row gets `cancel_requested`. When the charge finishes, one atomic update chooses the next state: a success becomes `REFUND_PENDING` (and **no `PaymentSucceeded` is published**, since order-service would ignore it for a cancelled order anyway); a decline becomes `CANCELLED` and **no `PaymentFailed` is published**. |
| after success | `SUCCEEDED` becomes `REFUND_PENDING`, then `REFUNDED` |
| already `FAILED`, `CANCELLED`, or refunding | nothing |

The *void only if never claimed* rule uses `charge_attempts = 0`. If a cancel
voided a payment a worker had already claimed, the worker's money would be taken
but its result could not be recorded, so a claimed payment is only ever flagged.

Refunds have their own lease, retries with backoff, and idempotency, exactly
like charges. A zero-total order is never sent to the provider (providers reject
zero amounts and nothing is owed): it succeeds at once, and has nothing to refund.

### 4. An unknown outcome is never a decline

If the provider times out, errors or is unreachable, the payment stays
`CHARGING` and is retried with backoff (1 s, doubling, up to 1 minute) and the
**same idempotency key**, until the provider gives a definitive answer. Marking
it `FAILED` on a timeout could cancel an order the customer was in fact charged
for. While the provider is down, order-service cancels the unpaid order after
30 minutes (ADR 007), and our `cancel_requested` flag turns any late success into
a refund.

After 2 hours without a definitive answer the payment moves to `NEEDS_REVIEW`:
retrying stops, an ERROR is logged, and a person must look. Customers still see
`PROCESSING`.

One residual case, documented rather than hidden: if a cancellation arrives
after a lost response, re-driving the charge with the same key can create a
charge and then refund it. The customer ends up whole and never charged twice.

### 5. The provider is an interface with two implementations

`PaymentProvider.charge(amount, currency, idempotencyKey)` returns
**Succeeded** (with a reference for refunds), **Declined(reason)** or
**Transient(error)**; `refund(reference, key)` returns **Refunded** or
**Transient**. Any exception from an implementation is treated as Transient.

**Simulated provider (default, this PR).** No account, no real money,
deterministic. It keeps a ledger in its own table keyed by idempotency key, as a
real provider does on its side, so idempotency survives restarts and tests can
assert how often money moved. Orders carry no card details, so the outcome is
decided by the cents of the order total (like Stripe's test card numbers, but
readable in a demo): `.13` is declined (`CARD_DECLINED`), `.14` is declined
(`INSUFFICIENT_FUNDS`), `.15` fails twice with a provider error and then
succeeds, anything else succeeds. The service logs a warning at startup that no
real money moves.

**Stripe test mode (next PR).** Used only when a Stripe *test* key is present in
the environment; keys are never committed. Real checkout would collect a payment
method from the customer, which the `OrderPlaced` contract does not carry; that
is out of scope here.

### 6. Events, queues and cleanup

- Consumes `OrderPlaced` and `OrderCancelled` from the queue
  `payment-service.order-events` (bound to `order.placed` and `order.cancelled`),
  with dead-letter queue `payment-service.order-events.dlq`. Nothing relies on
  order. A malformed message is dead-lettered at once; a failing one is retried
  a few times with backoff, then dead-lettered.
- Publishes `PaymentSucceeded` and `PaymentFailed` exactly as in
  [`docs/events`](../events/README.md), through a transactional outbox with the
  same relay as order-service (publisher confirms, `mandatory`, `SKIP LOCKED`,
  backoff). `reason` is a stable code (`CARD_DECLINED`, `INSUFFICIENT_FUNDS`);
  adding codes is additive, so the event version is unchanged. `paymentId` is
  our payment's id.
- Each event id is stored in `processed_events` in the same transaction as its
  effect. Retention matches order-service: published outbox events 7 days,
  processed event ids 30 days; unpublished events are never deleted. Payment
  rows are the financial record and are kept.

### 7. The customer endpoint

`GET /api/payments/orders/{orderId}` returns the status of the caller's own
order. The user comes from the token's subject, never from the URL. An unknown
order, someone else's order, a payment not recorded yet, and a cancellation
tombstone all give 404. Service tokens get 403 like every customer endpoint
(ADR 008). Provider references and internal states are never exposed.

## Failure cases

| What goes wrong | What happens |
|---|---|
| Duplicate `OrderPlaced` (same event id) | Skipped by `processed_events` |
| Same order, different event id | Unique `order_id`: one payment |
| Two payment-service instances / two workers | One claims the payment; the other sees the lease |
| Provider charged, response lost | The retry (same key) returns the original charge; money moved once |
| Worker crashes before or after the charge | The lease expires; another worker re-drives with the same key |
| Card declined | One `PaymentFailed`; order-service cancels the order and returns the stock |
| Provider down | Retried with backoff; the order is cancelled after 30 minutes, a late success is refunded; `NEEDS_REVIEW` after 2 hours |
| RabbitMQ down | Events wait in the outbox |
| Cancel before / during / after the charge | See section 3 |
| A late `PaymentSucceeded` for an already-cancelled order | Never announced (cancel during the charge) or ignored by order-service, and the money is refunded |
| Poison message | Dead-lettered |
| Zero-total order | Provider skipped; succeeds at once |

## Consequences

Positive:
- Every layer of "never charge twice" is separate and has its own test that
  fails when the layer is removed (see below).
- No distributed transaction and no long-held database transaction.
- CI and the demo need no external account.

Negative:
- A charge starts up to about half a second after the event (the worker poll).
- More moving parts: two workers, a relay, and bookkeeping tables.
- The simulated provider cannot show real provider behaviour such as 3-D
  Secure, webhooks or partial refunds; none of these exist in the contracts yet.

Not done yet: the Stripe test-mode provider, a `PaymentRefunded` event,
multiple currencies, tooling to resolve `NEEDS_REVIEW` payments and to replay
dead letters.

## How each protection was proven

Each protection was switched off on its own (one change at a time), the full
suite was run, and the source was restored afterwards and checked identical.
Every one made at least one test fail:

| Protection removed | Tests that failed |
|---|---|
| unique `order_id` | 12 (duplicate events, cancel/place races, ...) |
| charge lease (claim) | 3 (competing workers, crashed worker takeover) |
| refund lease | 1 (competing refund workers) |
| stable idempotency key | 3 (lost response charges twice, crashed worker) |
| conditional completion (`WHERE status = 'CHARGING'`) | 1 |
| cancel-before-order tombstone | 4 |
| `cancel_requested` flag | 4 |
| honouring the flag on success / on decline | 2 / 1 |
| announce only when this call made the transition (success / failure) | 1 / 1 |
| void only if never claimed (`charge_attempts = 0`) | 4 |
| refund after success | 5 |
| event-id deduplication | 1 |
| ownership check on the status endpoint | 2 |
| service-token refusal | 1 |
| retention keeps unpublished outbox rows / keeps recent event ids | 1 / 1 |
| `NEEDS_REVIEW` after 2 hours | 1 |
| unknown outcome is not a decline | 3 |
| zero-total skips the provider | 1 |
| tombstone gets the order's details when `OrderPlaced` arrives | 1 |
| unroutable event counts as a failure in the relay | 1 |

Honest limits: event-id deduplication is a second guard behind the state
conditions, so its test asserts that a redelivery is reported as a duplicate
rather than a visible double effect. The simulated provider ignores the refund
idempotency key (it refunds a charge at most once by itself), so only the refund
*lease* is proven at service level. The race tests (`cancel` racing the charge,
`cancel` racing `OrderPlaced`) run 40 rounds each.
