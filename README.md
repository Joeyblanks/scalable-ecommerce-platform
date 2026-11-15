# Scalable E-commerce Platform

[![CI](https://github.com/Joeyblanks/scalable-ecommerce-platform/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Joeyblanks/scalable-ecommerce-platform/actions/workflows/ci.yml?query=branch%3Amain)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f)

An e-commerce platform built as a set of Java 21 / Spring Boot microservices in a single repository.

## Tech stack

| Concern        | Choice                                  |
|----------------|-----------------------------------------|
| Language       | Java 21                                 |
| Framework      | Spring Boot 4.1 (Spring Security 7, Jackson 3) |
| Build          | Maven, one standalone project per service (each with its own wrapper) |
| Database       | PostgreSQL, one database per service    |
| Messaging      | RabbitMQ (domain events between services) |
| Local runtime  | Docker Compose                          |

## Repository layout

One repository, but every service is a fully independent project: it has its own
`pom.xml`, Maven wrapper, Dockerfile and tests, and there is no shared parent POM or
root build. Each service is built, tested and deployed on its own.

```
.
├── docs/decisions/      # Architecture Decision Records (ADRs)
├── user-service/        # Registration, login (RS256 JWT + JWKS), profile, roles
│   ├── pom.xml          # standalone (Spring Boot 4.1.x parent, Java 21)
│   ├── mvnw, .mvn/      # the service's own Maven wrapper
│   ├── Dockerfile
│   └── src/
├── product-catalog-service/   # Products and categories (same standalone layout)
├── cart-service/        # One shopping cart per user, stored in Redis (same standalone layout)
├── order-service/       # Orders: saga, transactional outbox, payment events (same standalone layout)
├── payment-service/     # Payments: charging via a provider interface, refunds, outbox (same standalone layout)
├── docs/events/         # Event contracts (envelope, topology, JSON schemas, examples)
├── docker-compose.yml   # local dev: databases + all services
├── .github/workflows/   # CI (see "Continuous integration")
└── .env.example
```

Adding a service means adding a new top-level folder like `user-service/` and a
matching entry in `docker-compose.yml`.

## Architecture decisions

- [001 - Single repository](docs/decisions/001-single-repository.md)
- [002 - RabbitMQ over Kafka](docs/decisions/002-rabbitmq-over-kafka.md)
- [003 - Spring Security JWT instead of jjwt](docs/decisions/003-spring-security-jwt-over-jjwt.md)
- [004 - RS256 with JWKS instead of a shared HS256 secret](docs/decisions/004-rs256-jwks-and-roles.md)
- [005 - CI with one GitHub Actions workflow](docs/decisions/005-ci-github-actions.md)
- [006 - Cart service: Redis, price snapshots, compare-and-set](docs/decisions/006-cart-service-redis-and-price-snapshots.md)
- [007 - Order saga with a transactional outbox](docs/decisions/007-order-saga-and-transactional-outbox.md)
- [008 - Service-to-service tokens (OAuth2 client credentials)](docs/decisions/008-service-to-service-tokens.md)
- [009 - Payment service: charging exactly once, cancellation races, provider interface](docs/decisions/009-payment-service.md)

## Getting started

Prerequisites: JDK 21 and Docker with Compose. Maven is not required: each service bundles its own wrapper (`./mvnw`).

```bash
cp .env.example .env              # then set JWT_PRIVATE_KEY (see "Signing key") and the DB password
docker compose up --build         # user :8080, catalog :8081, cart :8082, order :8083, payment :8084 (PostgreSQL / Redis behind each)
```

Build and test a single service from its own folder:

```bash
cd user-service                   # or product-catalog-service
./mvnw verify                     # unit tests + integration tests (needs Docker)
```

### Signing key (RS256)

user-service signs JWTs with an RSA private key and publishes the matching public key at
`GET /.well-known/jwks.json`. Other services verify tokens against that JWKS, so they never hold
a secret. The private key is **never committed**; you provide it per environment.

Generate one (once) and put it in your gitignored `.env`:

```bash
echo "JWT_PRIVATE_KEY=$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | base64 | tr -d '\n')" >> .env
```

- Keep the same key across restarts. A new key invalidates every token already issued.
- Alternative to the env var: mount a PKCS#8 PEM file and set `JWT_PRIVATE_KEY_FILE=/path/to/key.pem`
  (set exactly one of the two). The key must be RSA, at least 2048 bits, unencrypted PKCS#8
  (`BEGIN PRIVATE KEY`).
- The token header carries a `kid` (the public key's thumbprint) and the payload a `roles` claim,
  e.g. `["ADMIN"]`.

### Roles and the first admin

Users are `CUSTOMER` or `ADMIN`. Self-registration always creates a `CUSTOMER`; there is no
endpoint that creates or promotes admins. To create the first admin, set both variables in `.env`
before starting the service:

```bash
BOOTSTRAP_ADMIN_EMAIL=admin@example.com
BOOTSTRAP_ADMIN_PASSWORD=<12-72 characters, strong and unique>
```

The admin is created once at startup. There is no default password. If the email already
belongs to an account, that account is left unchanged. You can remove the variables afterwards.

### Service tokens (service-to-service calls)

Services authenticate to each other with short-lived (5 minute) tokens from user-service, using the
OAuth2 **client credentials** grant ([ADR 008](docs/decisions/008-service-to-service-tokens.md)).
They come from the same issuer and are signed with the same key as customer tokens, so every
service verifies them with the existing JWKS; no other issuer or JWKS exists.

```bash
curl -s -u order-service:<client secret> -d grant_type=client_credentials -d scope=stock:reserve \
  localhost:8080/oauth2/token
# {"access_token":"...","scope":"stock:reserve","token_type":"Bearer","expires_in":299}
```

- A service token carries `client_id`, `scope` and `aud`, and never `roles`. Customer tokens never
  carry `client_id` or `scope`.
- **Request the scopes you need explicitly.** A token requested without `scope` has none and is
  accepted nowhere.
- Customer-facing endpoints refuse service tokens (403); internal endpoints refuse user and ADMIN
  tokens (403).
- **Contact lookup (internal):** `GET /api/internal/users/{id}/contact` returns `userId`, `email` and
  `fullName` (nothing else). Only a service token with scope `users:read-contact` and audience
  `user-service` (the `notification-service` client) can call it; customer, admin and other services'
  tokens get 403, no token 401, an unknown user 404.
- Clients are configured in `user-service/src/main/resources/application.yml`. user-service stores
  only a **bcrypt hash** of each secret (startup fails if a plain secret is put there). Generate one:

  ```bash
  docker run --rm httpd:2.4-alpine htpasswd -nbBC 12 "" '<the secret>' | tr -d ':\n'
  ```

  Put it in `.env` as `ORDER_SERVICE_CLIENT_SECRET_HASH`. The plain secret goes only to the calling
  service (order-service, once it exists). In local dev both happen to live in the same `.env`; in a
  real deployment they belong in each service's own secret store.

### Tests

- Unit tests (`*Test`, Surefire) run in the `test` phase and need nothing external.
- Integration tests (`*IT`, Failsafe) run in the `verify` phase against a real PostgreSQL
  started by Testcontainers, so **Docker must be running**.

### Continuous integration

One GitHub Actions workflow (`.github/workflows/ci.yml`) runs on pull requests and on pushes to
`main`. It detects which services a change touches and, for each one, runs `./mvnw verify`
(unit tests and Testcontainers integration tests) and builds the Docker image (nothing is pushed).
Changes that touch no service, such as docs, build nothing; changes under `.github/` build every
service. Maven dependencies are cached. The `CI result` job is the single check to require in
branch protection. Details and trade-offs: [ADR 005](docs/decisions/005-ci-github-actions.md).

### User Service API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/users/register` | none | `{email, password, fullName}` -> 201 profile (409 if email taken) |
| POST | `/api/users/login` | none | `{email, password}` -> `{accessToken, tokenType, expiresIn}` |
| GET | `/api/users/me` | Bearer JWT | Current user's profile (includes `role`) |
| GET | `/.well-known/jwks.json` | none | Public signing key (JWKS) for other services |
| GET | `/actuator/health` | none | Health check |

```bash
curl -H 'Content-Type: application/json' -d '{"email":"ann@example.com","password":"password123","fullName":"Ann"}' localhost:8080/api/users/register
TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"email":"ann@example.com","password":"password123"}' localhost:8080/api/users/login | jq -r .accessToken)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/users/me
```

### Product Catalog Service API (port 8081)

Reads are public. Writes need a Bearer token from user-service with the `ADMIN` role
(no token -> 401, `CUSTOMER` token -> 403). The service verifies tokens with user-service's JWKS and
holds no secret.

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| GET | `/api/products` | public | Paged list. Params: `page` (>= 0), `size` (1-100, default 20), `sort` (`name`, `price`, `sku`, `stockQuantity`, `createdAt`; e.g. `price,desc`), `categoryId`, `q` (name contains, case-insensitive) |
| GET | `/api/products/{id}` | public | One product |
| POST / PUT / DELETE | `/api/products[/{id}]` | ADMIN | Create (201), full update, delete (204). Body: `sku`, `name`, `description`, `price`, `stockQuantity`, `categoryId`; **PUT also needs `version`** (see below) |
| GET | `/api/categories`, `/api/categories/{id}` | public | Paged list (`sort`: `name`, `createdAt`) / one category |
| POST / PUT / DELETE | `/api/categories[/{id}]` | ADMIN | Body: `name`, `description` |

Lists are `{items, page, size, totalElements, totalPages}`. Errors are RFC 9457 problem JSON:
400 validation (including a `categoryId` that does not exist), 404 unknown id, 409 duplicate SKU or
category name, or deleting a category that still has products. SKUs are stored uppercase.

**Updating a product needs the `version` you read.** Every product response has a `version`, which
changes on every edit and every stock reservation. `PUT` must send it back: a missing version is 400,
a stale one is 409 ("reload and apply your change again"). This stops an admin's edit, made from an
old read, from overwriting stock that orders reserved in the meantime.

**Internal stock reservations (service-to-service only).** These need a service token with scope
`stock:reserve` and audience `product-catalog-service` ([ADR 008](docs/decisions/008-service-to-service-tokens.md)).
User and ADMIN tokens get 403; service tokens get 403 on all the customer endpoints above.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/internal/stock/reservations` | `{reservationId, items:[{productId, quantity}]}`: reserves **all items or none**. Returns each item's current `unitPrice`, `sku` and `name`, read in the same transaction (the authoritative price). 201 when new; 200 when the same id is repeated with the same items (nothing more is taken); 409 with `shortages` (per product: `requested`, `available`, `reason`) when anything is short or missing; 409 when the id was already released or used for different items. |
| POST | `/internal/stock/reservations/{id}/release` | Returns the stock exactly once; always 200. Releasing an id that was never reserved records it, so a reserve that arrives later with that id is refused. |

Guarantees (tested against a real PostgreSQL): no overselling under concurrent orders, no deadlocks
between orders listing the same products in different orders, and every repeat is harmless.

Try it (after setting `BOOTSTRAP_ADMIN_*` in `.env`):

```bash
TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"email":"admin@example.com","password":"<your admin password>"}' localhost:8080/api/users/login | jq -r .accessToken)
CAT=$(curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"Books"}' localhost:8081/api/categories | jq -r .id)
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"sku\":\"CC-1\",\"name\":\"Clean Code\",\"price\":29.90,\"stockQuantity\":10,\"categoryId\":\"$CAT\"}" localhost:8081/api/products
curl -s 'localhost:8081/api/products?sort=price,desc&size=5'   # public, no token
```

### Cart Service API (port 8082)

One cart per user, stored in Redis. **Every endpoint needs a Bearer token** from user-service (any
role). The cart is chosen by the token's subject, never by anything in the request, so users can only
reach their own cart. Inactive carts expire after 7 days (any access renews the timer).

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/cart` | The cart: `items`, `itemCount`, `totalQuantity`, `total`. Empty cart = 200 with no items. Works even if the catalog is down. |
| POST | `/api/cart/items` | `{productId, quantity}`: **adds** to the existing line (or creates it). Returns the cart. |
| PUT | `/api/cart/items/{productId}` | `{quantity}`: **sets** the quantity (1-99). 404 if the line is not in the cart. |
| DELETE | `/api/cart/items/{productId}` | Removes the line, returns the cart. 404 if not in the cart. |
| DELETE | `/api/cart` | Clears the cart (204, idempotent) |

On add and quantity change the service asks product-catalog-service (1 s connect / 2 s read timeout).
Errors are RFC 9457 problem JSON: **404** unknown product or line, **409** not enough stock (includes
`availableStock`; counts what is already in the cart), too many of one product (99) or too many
different products (50), **400** invalid input, **503** with `Retry-After` when the catalog or Redis is
unavailable (the cart is left unchanged).

Prices in the cart are a **snapshot** taken when a line was last added or changed (see
[ADR 006](docs/decisions/006-cart-service-redis-and-price-snapshots.md)); checkout must re-read the
catalog price.

```bash
# TOKEN: log in as any user (see the User Service API above); CAT_PRODUCT_ID: an id from the catalog
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"productId\":\"$CAT_PRODUCT_ID\",\"quantity\":2}" localhost:8082/api/cart/items
curl -s -H "Authorization: Bearer $TOKEN" localhost:8082/api/cart
```

Redis is not published to the host. To look inside it: `docker compose exec cart-redis redis-cli`.

**After you place an order**, cart-service removes what you ordered when it receives the
`OrderPlaced` event (usually within a second). It **subtracts** the ordered quantities rather than
clearing the cart, so anything you added after ordering stays. A redelivered event is applied only
once ([ADR 006 amendment](docs/decisions/006-cart-service-redis-and-price-snapshots.md)). The cart
API keeps working if RabbitMQ is down; the cleanup catches up when it is back. Service tokens are
refused (403) on every cart endpoint.

### Order Service API (port 8083)

Places orders from the caller's cart and follows them through payment
([ADR 007](docs/decisions/007-order-saga-and-transactional-outbox.md)). Every endpoint needs a
customer token; service tokens get 403. Orders always belong to the token's subject, and another
user's order is a 404.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/orders` | Places an order from your cart. **Requires an `Idempotency-Key` header** (1-100 of `A-Z a-z 0-9 - _`); repeating it returns the same order (200) instead of a second one. 201 with the order (`PENDING`) at the catalog's current prices; lines whose price differs from what the cart showed have `priceChanged: true`. |
| GET | `/api/orders` | Your orders, newest first (`page`, `size` 1-50) |
| GET | `/api/orders/{id}` | One of your orders |
| POST | `/api/orders/{id}/cancel` | Cancels a `PENDING` order (409 otherwise); its stock is released |

Errors: **400** empty cart, missing or invalid key; **409** some items are short (`shortages` lists
each product with `requested` and `available`; nothing was ordered and your cart is unchanged), or
the same key is still being processed; **503** with `Retry-After` when the cart, the catalog or
user-service is unavailable (nothing was ordered), or when the order could not be confirmed (for
example a storage error while saving). In every case, **retry with the same key**: if the order did
go through you get it back (200), otherwise it is placed or fails cleanly.

Order states: `PENDING` (stock reserved, waiting for payment), then `PAID` or `CANCELLED`
(`CUSTOMER_REQUEST`, `PAYMENT_FAILED`, or `PAYMENT_TIMEOUT` after 30 minutes unpaid).

How it stays correct when things fail (details and the full failure table are in ADR 007):

- Events are written to a **transactional outbox** with the order change and published by a relay
  that waits for RabbitMQ's confirm, so an order is never saved without its event or vice versa.
- Payment events are handled **idempotently** (event id deduplication plus state-conditional
  updates); poison messages go to a dead-letter queue.
- Background jobs fail stuck attempts, cancel unpaid orders, retry stock releases, and clean up old
  bookkeeping rows (published outbox events after 7 days, processed event ids after 30 days).

Event contracts (envelope, routing keys, every event's fields, versioning rules, JSON Schemas and
examples) are in [`docs/events`](docs/events/README.md).

To place an order locally, order-service needs its client secret: set
`ORDER_SERVICE_CLIENT_SECRET` and the matching `ORDER_SERVICE_CLIENT_SECRET_HASH` in `.env` (see
"Service tokens"). The RabbitMQ management UI is at http://localhost:15672. Once an order is placed,
cart-service removes the ordered items from your cart, and payment-service charges it (below).

### Payment Service API (port 8084)

Consumes `OrderPlaced` and `OrderCancelled`, charges through a provider, and publishes
`PaymentSucceeded` / `PaymentFailed` ([ADR 009](docs/decisions/009-payment-service.md)). It calls no
other service: it works from events and its own PostgreSQL.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/payments/orders/{orderId}` | The payment status of **your own** order: `PROCESSING`, `PAID`, `FAILED` (with `failureReason`), `CANCELLED`, `REFUNDING` or `REFUNDED`, plus `amount`, `currency`, `updatedAt`. 404 for an unknown order, someone else's order, or an order whose payment is not recorded yet. Service tokens get 403. |

**The simulated provider (default)** needs no account and moves no money. Orders carry no card
details, so it decides by the **cents of the order total**:

| Total ends in | Result |
|---|---|
| anything else | succeeds |
| `.13` | declined (`CARD_DECLINED`): the order is cancelled and its stock returned |
| `.14` | declined (`INSUFFICIENT_FUNDS`) |
| `.15` | two provider errors, then success (shows the retry; charged once) |

To see both outcomes locally, create products priced so the total ends in `.13` and in a normal
amount (for example `19.99` and `20.13`), place an order for each, and follow them with
`GET /api/orders/{id}` and `GET /api/payments/orders/{id}`: the first becomes `PAID`; the second
becomes `CANCELLED` with its stock back in the catalog.

**Stripe test mode (optional).** Set a Stripe **test** key in `.env` and payment-service charges
through Stripe's test API instead (no account needed otherwise; this is never required):

```bash
echo 'STRIPE_API_KEY=sk_test_...' >> .env      # .env is gitignored; a live key (sk_live_) stops startup
docker compose up -d --build payment-service   # the log says: Using the STRIPE provider in TEST MODE
```

Orders carry no card details, so the same cents rule picks Stripe's test payment methods
(`.13` declined, `.14` insufficient funds, anything else `pm_card_visa`). Charges are PaymentIntents
with `confirm=true`, amounts in cents, and our idempotency key. Remove the line (or set
`PAYMENT_PROVIDER=simulated`) to go back to the simulated provider. Smoke check against Stripe's real
test API: place a `19.99` order and see it `PAID` in `GET /api/payments/orders/{id}` and in the
Stripe dashboard's test-mode payments; then cancel a paid one and see the refund there.

How it never charges twice (details and the failure table are in ADR 009): one payment row per
order (unique `order_id`), a worker lease so two workers never charge the same payment, a stable
idempotency key on every provider call, and conditional state changes. An unknown outcome (timeout)
is retried, never treated as a decline. A cancellation that arrives before, during or after the
charge is honoured: nothing is charged, or the charge is refunded.

## Status

Early stage: User, Product Catalog, Cart, Order and Payment service MVPs.
