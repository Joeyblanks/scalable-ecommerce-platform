# 007. Order saga (choreography) with a transactional outbox

- Status: Accepted
- Date: 2026-11-14

## Context

Placing an order touches four services, each with its own data: the cart
(Redis), the catalog (stock, PostgreSQL), orders (PostgreSQL) and, next,
payments. There is no transaction across them. A process can crash between
any two steps, any network call can time out without us knowing whether it
took effect, and RabbitMQ delivers messages at least once. We need an order to
end in a correct state whatever fails, without overselling and without
leaking reserved stock.

## Decisions

### 1. A saga, coordinated by events (choreography)

Each service reacts to the others' events; there is no central orchestrator.

```
customer -> order-service: place order
  order-service -> cart-service      read the cart (with the customer's token)
  order-service -> catalog           reserve stock + get real prices (service token, ADR 008)
  order-service -> RabbitMQ          OrderPlaced          (via the outbox)
payment-service   on OrderPlaced     charge -> PaymentSucceeded | PaymentFailed
cart-service      on OrderPlaced     remove the ordered quantities
order-service     on PaymentSucceeded  PENDING -> PAID, OrderPaid
order-service     on PaymentFailed     PENDING -> CANCELLED, release stock, OrderCancelled
payment-service   on OrderCancelled  void/refund if it had charged
```

The event contracts are in [`docs/events`](../events/README.md).

### 2. Placing an order: durable step by step, never one big transaction

A database transaction must never be held open across a remote call, so the
steps are made durable one at a time:

1. Read the cart. Nothing is written yet; if the cart is empty or the cart
   service is down, the customer gets 400 or 503 and nothing happened.
2. **TX1:** insert the order as `RESERVING`. The order id is generated here and
   is also the catalog **reservation id**, which makes every later call about
   it repeatable.
3. Reserve stock at the catalog. It reserves all items or none and returns
   the authoritative price of each item from the same database transaction,
   so the price cannot change between "read price" and "reserve".
4. **TX2:** `RESERVING -> PENDING` with those prices, **and** an `OrderPlaced`
   row in the outbox, in one transaction. The update only applies
   `WHERE status = 'RESERVING'`.

The customer's cart is emptied by cart-service when it receives `OrderPlaced`,
not by order-service. So the cart is cleared *because* the order exists.

`POST /api/orders` requires an `Idempotency-Key` header (unique per user):
repeating the request returns the same order. A failed attempt frees its key,
so the client can simply retry with it.

### 3. Transactional outbox

Events are never sent to RabbitMQ from the request. They are inserted into
`outbox_events` **in the same transaction as the change they describe**, so a
change without its event, or an event without its change, cannot exist. A
relay then publishes them:

- every 500 ms, it takes the oldest due rows with `FOR UPDATE SKIP LOCKED`
  (safe with several instances);
- it publishes with publisher confirms and `mandatory`, and marks a row
  published only after RabbitMQ has confirmed it. A nack, a timeout or an
  unroutable message is a failure: the row is retried with backoff (up to 30
  s) and its error is recorded;
- it stops at the first failure in a batch, to keep events in order.

This is **at least once**: if the relay dies after the confirm but before
marking the row, the event is published again with the **same `eventId`**.

Stock releases use the same idea: a `stock_releases` row is written in the
same transaction as the cancellation or failure that needs it, and a worker
calls the catalog's idempotent release until it succeeds (backoff up to 5
minutes).

Alternatives: publishing directly after commit (loses events on a crash);
change data capture with Debezium (reliable, but much more infrastructure);
XA transactions across the database and RabbitMQ (not supported by RabbitMQ).

### 4. Idempotent consumers: two independent guards

1. The consumed event's id is inserted into `processed_events` **in the same
   transaction as its effect**. A duplicate is skipped. If the effect rolls
   back, the id does too, and the redelivery is processed normally.
2. Every state change is conditional on the current state (`... WHERE status =
   'PENDING'`). Even a different event with the same meaning, such as a second
   PaymentFailed for another payment attempt, cannot apply twice.

We verified each guard separately. With deduplication switched off, the state
guard alone still prevented a duplicate PaymentSucceeded from paying twice.
With a state guard switched off, the tests caught the double effect.

A message that cannot be parsed is dead-lettered at once. A message that fails
(for example, for an unknown order) is retried a few times with backoff and
then dead-lettered (`<queue>.dlq`). It is never retried forever.

### 5. Self-healing jobs

- **Reconciler** (every minute): an order still `RESERVING` after 2 minutes
  means the process died, or the database failed, mid-placement. It is marked
  `FAILED` and its stock release is queued.
- **Payment timeout** (every minute): a `PENDING` order not paid within 30
  minutes is cancelled (`PAYMENT_TIMEOUT`) and its stock released, so stock is
  never held forever.
- **Retention cleanup** (hourly), deleting in small batches:
  - published outbox events after **7 days** (kept only for troubleshooting).
    Unpublished events are never deleted;
  - processed event ids after **30 days**. This must outlive any redelivery or
    dead-letter replay. An event replayed after its id was deleted is still
    harmless, because of the state guards (section 4);
  - completed stock releases after 30 days. Pending ones are never deleted.

All jobs use the same conditional transitions as requests, so they are safe to
run on several instances and to race a slow request (exactly one side wins).

## Failure cases

| What goes wrong | What happens | What the customer sees |
|---|---|---|
| Cart service down | Nothing is written | 503, cart unchanged |
| Cart empty | Nothing is written | 400 |
| **Catalog down, or the reserve times out** (outcome unknown) | Attempt `FAILED`; a release of its id is queued. If the catalog did reserve, the stock comes back. If the reserve lands even later, the catalog's tombstone refuses it. | 503 "nothing was ordered", may retry with the same key |
| **Stock gone mid-order** (sold out, or a concurrent checkout got the last units) | The catalog's all-or-nothing reservation fails; nothing is reserved; attempt `FAILED` | 409 listing each short product and how many are available; cart unchanged |
| Price changed since added to cart | The catalog's current price is used | 201; the line is flagged `priceChanged` |
| order-service crashes, or its database fails, between reserving and TX2 | The order stays `RESERVING`; the reconciler marks it `FAILED` and releases the stock. A late TX2 cannot win (conditional on `RESERVING`). | 503 or a dropped connection; no stock leaks |
| RabbitMQ down or refusing after the order was saved | The event waits in the outbox; the relay retries until the broker confirms | 201 at once; payment starts and the cart empties once the event is out |
| **"Cart cleared but event not sent"** | **Cannot happen:** the cart is cleared *by* the event, and the event exists only if the order was committed. The reverse (an event for an order that was not saved) cannot happen either: same transaction. | |
| **Duplicate events** (relay retry after a crash, broker redelivery) | Same `eventId`, so the duplicate is skipped; the state guards also hold | |
| Events arrive out of order | Consumers must tolerate it (contract rule); order-service's handlers are conditional on state | |
| Payment never arrives | Cancelled after 30 minutes, stock released | Order `CANCELLED (PAYMENT_TIMEOUT)` |
| Customer cancels while a payment succeeds | Exactly one transition wins. If the cancel wins, payment-service refunds on `OrderCancelled`. | One consistent final state |
| A stock release call fails | Retried with backoff until the catalog confirms (idempotent) | |
| Double-click on "place order" | Same `Idempotency-Key`, so the same order is returned | One order |
| A poison message | Retried a few times, then dead-lettered | |

## Consequences

Positive:
- No distributed transaction, yet every failure converges to a correct state,
  usually within seconds, at worst after the reconciler or payment timeout.
- The guarantees are local and testable. Each one above has an integration
  test against a real PostgreSQL and RabbitMQ, and each test was checked to
  fail when its protection is removed.

Negative:
- Eventual consistency: for a moment after placing an order the cart still
  shows the items, and payment starts only once the event is published.
- More moving parts in order-service (relay, worker, three jobs) and
  bookkeeping tables that need retention.
- Consumers carry the burden of idempotency and out-of-order tolerance; the
  contract makes this explicit.
- The `Idempotency-Key` header is mandatory, so clients must generate one per
  checkout attempt.

Not done yet: payment and notification services (next PRs), the outbox relay's
latency metrics, and tooling to inspect and replay dead letters.
