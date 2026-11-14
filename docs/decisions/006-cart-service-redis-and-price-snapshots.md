# 006. Cart service: Redis storage, price snapshots, and compare-and-set writes

- Status: Accepted
- Date: 2026-11-14

## Context

The cart service keeps one shopping cart per user. A cart is short-lived,
read far more often than it is changed, and losing one is annoying but not
harmful. Adding or changing an item must be checked against the product
catalog (does the product exist, is there enough stock), but the catalog is a
different service that can be slow or down. We had to decide where carts live,
how prices are handled, how to talk to the catalog, and how to stay correct
when the same user's requests overlap.

## Decisions

### 1. Redis, one hash per user, with an inactivity expiry

Each cart is a Redis hash at `cart:<userId>`: one field per product id, whose
value is the cart line as JSON. The user id is the token's `sub` claim and
comes from nothing else, so there is no way to name another user's cart.

The key has a time-to-live (7 days by default) that is renewed whenever the
cart is viewed or changed, so only carts nobody has touched expire. Limits
bound memory and abuse: at most 99 of one product and 50 different products.

A relational database would also work, but expiry, atomic scripts and speed
come for free in Redis, and carts do not need relational queries. Redis keeps
an append-only file in dev so carts survive restarts; if they are lost, the
user simply starts a new cart.

### 2. The cart stores a price snapshot; it is for display only

**The question:** when a product is added, does the cart remember the price
at that moment, or look up the current price every time the cart is shown?

**Decision:** the cart stores a snapshot (`unitPrice`, `name`, `sku`) taken
from the catalog when the line is added, and refreshes it every time the line
is changed (adding more or changing the quantity). Viewing the cart reads only
Redis. The price in a cart means "the price as of `updatedAt`", not a promise.

Why not look the price up on every view:
- Viewing is the most frequent action. It would call the catalog for every
  item on every view (the catalog has no batch endpoint), adding latency and
  load.
- The cart would stop working whenever the catalog is down, even though
  nothing in the cart needs to change. With a snapshot, a shopper can still
  look at and remove things from their cart during a catalog outage.

Why a snapshot is safe: the cart is not where money is decided. The future
orders service must read the authoritative price from the catalog at checkout
and freeze it into the order. Stock is treated the same way: it is checked
when adding or changing, but it is not reserved, and checkout checks again.

The cost is that a price shown in the cart can be out of date if the catalog
changed it since. That is acceptable for a shopping list, and the response
makes it explicit by carrying `updatedAt`. If we later want fresher prices, a
"refresh prices" action can re-read the catalog on demand without changing
this design.

### 3. Synchronous catalog call with short timeouts and a clear failure

On add and quantity change, the cart calls the catalog's public
`GET /api/products/{id}` once. Connect timeout 1 s, read timeout 2 s, no
retries. Outcomes the shopper sees:

| Catalog says | Cart answers |
|---|---|
| product exists, enough stock | the change is made |
| 404 | 404, nothing stored |
| not enough stock (counting what is already in the cart) | 409 with `availableStock` |
| down, too slow, 5xx, or unreadable answer | 503 with `Retry-After`, cart unchanged |

Redis being unreachable is also a quick 503. We did not add retries or a
circuit breaker yet: a failing call returns within a couple of seconds, and
the shopper can retry. Revisit if the catalog has frequent short outages.

### 4. Compare-and-set so simultaneous requests do not lose updates

**The problem, in plain language.** Changing a cart line is a three-step job:
*read* the line, *work out* the new value, *write* it back. If two requests
for the same line overlap, each can read the same old value, and the second
write silently overwrites the first.

> Example. The cart holds 3 copies of a book. The user double-clicks "add one
> more", sending two requests at nearly the same moment.
>
> 1. Request A reads "3". Request B also reads "3".
> 2. A works out 3 + 1 = 4 and writes 4.
> 3. B works out 3 + 1 = 4 and writes 4.
>
> The cart says 4, but the user asked for two more, so it should say 5. One
> click was lost, and nobody got an error.

We reproduced this on purpose: with the protection switched off, **20
simultaneous requests that each add one copy ended with a quantity of 2
instead of 20.**

**The fix: compare-and-set.** When a request writes, it also says what it
read: "set this line to 4, but only if it is still 3". Redis checks that and
performs the write as one indivisible step (a small Lua script, which nothing
can interleave with).

> With the fix: A reads 3, B reads 3. A writes "4, if still 3": it is, so
> it succeeds. B writes "4, if still 3": it is now 4, so Redis refuses.
> B reads again (4), works out 5, and writes "5, if still 4": it succeeds.
> The cart says 5.

A refused write is not an error for the user: the request just reads again
and redoes its calculation, including the stock check, against the fresh
value. Only Redis is read again; the catalog is asked once per request.
Each request that loses a race does so at most once per competing request, so
a bounded number of attempts (10 by default) is plenty. If the line is still
changing after that, the user gets 409 "try again". The same script enforces
the 50-products limit atomically.

We chose this over a lock because there is nothing to release, forget, or
time out if a server dies mid-request, and requests for different products
never wait for each other.

## Consequences

Positive:
- Viewing and removing items works during a catalog outage; adds fail fast
  with a clear message instead of hanging.
- Overlapping requests from one user are all counted (covered by an
  integration test with 20 simultaneous requests against a real Redis).
- No cart data in a relational database to migrate or clean up.

Negative:
- Cart prices can be stale; the authoritative price must be re-read at
  checkout (a requirement on the future orders service).
- Stock is only checked at the moment of the change, so a product can sell
  out afterwards; checkout must check again.
- Redis is another piece of infrastructure to run and monitor. Carts are
  lost if Redis loses its data.
- The cart service depends on the catalog's response shape (id, sku, name,
  price, stockQuantity). This is a deliberate, small coupling; there is no
  shared code between services.

Not done yet, by design: guest carts, merging carts on login, stock
reservation, a catalog circuit breaker, price refresh action.
