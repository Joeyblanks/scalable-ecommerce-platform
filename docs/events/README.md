# Event contracts

Domain events exchanged between services over RabbitMQ (ADR 002). These documents are the contract:
producers and consumers each keep their own classes for them (ADR 001: no shared code), and their
tests check against the field lists here. The saga that uses them is described in ADR 007.

## Topology

| Thing | Name | Notes |
|---|---|---|
| Exchange | `ecommerce.events` | durable **topic** exchange; every event goes here |
| Routing key | `<aggregate>.<event>` in lower case | `order.placed`, `order.paid`, `order.cancelled`, `payment.succeeded`, `payment.failed` |
| Queues | `<consumer-service>.<purpose>` | each consumer declares and owns its queue(s): `order-service.payment-events` (`payment.*`), `payment-service.order-events` (`order.placed`, `order.cancelled`), `notification-service.order-events` (`order.placed`, `order.paid`, `order.cancelled`) |
| Dead letters | exchange `ecommerce.events.dlx`, queue `<queue>.dlq` | a message that keeps failing is moved there after a few retries; it is never retried forever |

Declarations are idempotent: every service declares the exchange and the queues it uses at startup.

## Envelope (every event)

```json
{
  "eventId": "8b1c...uuid",
  "eventType": "OrderPlaced",
  "eventVersion": 1,
  "occurredAt": "2026-11-14T10:15:30.123Z",
  "producer": "order-service",
  "data": { "...": "event specific" }
}
```

- AMQP message properties: `message_id` = `eventId`, `type` = `eventType`,
  `content_type` = `application/json`, persistent delivery.
- **Money is a decimal string with two places** (`"59.80"`), never a JSON number, so no consumer
  ever rounds through a float. Single currency for now.
- Times are ISO-8601 UTC. Ids are UUID strings.

## Rules every producer and consumer must follow

1. **Delivery is at least once.** The same event (same `eventId`) can arrive more than once, for
   example after a producer crash or a redelivery. Consumers MUST deduplicate by `eventId` and
   make every effect conditional on current state.
2. **Order is not guaranteed.** For example, payment-service may receive `OrderCancelled` before
   `OrderPlaced` for the same order and must remember the cancellation.
3. **Producers never lose an event.** Events are published from a transactional outbox, so an event
   exists if and only if the change it describes was committed.
4. **Unknown fields must be ignored** by consumers; that is what makes additive changes safe.
5. **Versioning:** adding optional fields keeps `eventVersion`. Removing, renaming or changing the
   meaning of a field is a breaking change: publish a new `eventVersion`, and publish both versions
   until every consumer has moved.

## Events

### `OrderPlaced` v1 (order-service, `order.placed`)
Stock is reserved and the order is PENDING, waiting for payment.
Consumers: payment-service (charge `totalAmount`), cart-service (remove the ordered quantities),
notification-service.

| Field | Type | |
|---|---|---|
| `orderId` | uuid | also the stock reservation id |
| `userId` | uuid | |
| `items[]` | array | `productId` (uuid), `sku`, `name`, `unitPrice` (money), `quantity` (int > 0), `lineTotal` (money) |
| `totalAmount` | money | the amount to charge |
| `placedAt` | timestamp | |

### `OrderPaid` v1 (order-service, `order.paid`)
Consumers: notification-service.
Fields: `orderId`, `userId`, `paymentId`, `paidAt`.

### `OrderCancelled` v1 (order-service, `order.cancelled`)
The order will not be fulfilled; its stock is being released.
Consumers: payment-service, notification-service.
Fields: `orderId`, `userId`, `reason` (`CUSTOMER_REQUEST` | `PAYMENT_FAILED` | `PAYMENT_TIMEOUT`), `cancelledAt`.

**payment-service must void or refund any successful payment for a cancelled order.** This covers
the race where a payment succeeds just after the customer cancelled or the payment timed out.

### `PaymentSucceeded` v1 (payment-service, `payment.succeeded`)
Consumers: order-service (PENDING -> PAID).
Fields: `paymentId`, `orderId`, `amount` (money), `succeededAt`.

### `PaymentFailed` v1 (payment-service, `payment.failed`)
Consumers: order-service (PENDING -> CANCELLED, stock released).
Fields: `paymentId`, `orderId`, `reason` (free text; payment-service sends a stable code such as `CARD_DECLINED` or `INSUFFICIENT_FUNDS`, and new codes may be added), `failedAt`.

Machine-readable versions: [`schemas/`](schemas) (JSON Schema 2020-12) and [`examples/`](examples).
