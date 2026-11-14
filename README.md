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

## Getting started

Prerequisites: JDK 21 and Docker with Compose. Maven is not required: each service bundles its own wrapper (`./mvnw`).

```bash
cp .env.example .env              # then set JWT_PRIVATE_KEY (see "Signing key") and the DB password
docker compose up --build         # user-service :8080, product-catalog-service :8081, one PostgreSQL each
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
| POST / PUT / DELETE | `/api/products[/{id}]` | ADMIN | Create (201), full update, delete (204). Body: `sku`, `name`, `description`, `price`, `stockQuantity`, `categoryId` |
| GET | `/api/categories`, `/api/categories/{id}` | public | Paged list (`sort`: `name`, `createdAt`) / one category |
| POST / PUT / DELETE | `/api/categories[/{id}]` | ADMIN | Body: `name`, `description` |

Lists are `{items, page, size, totalElements, totalPages}`. Errors are RFC 9457 problem JSON:
400 validation (including a `categoryId` that does not exist), 404 unknown id, 409 duplicate SKU or
category name, or deleting a category that still has products. SKUs are stored uppercase.

Try it (after setting `BOOTSTRAP_ADMIN_*` in `.env`):

```bash
TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"email":"admin@example.com","password":"<your admin password>"}' localhost:8080/api/users/login | jq -r .accessToken)
CAT=$(curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"Books"}' localhost:8081/api/categories | jq -r .id)
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"sku\":\"CC-1\",\"name\":\"Clean Code\",\"price\":29.90,\"stockQuantity\":10,\"categoryId\":\"$CAT\"}" localhost:8081/api/products
curl -s 'localhost:8081/api/products?sort=price,desc&size=5'   # public, no token
```

## Status

Early stage: User Service and Product Catalog Service MVPs.
