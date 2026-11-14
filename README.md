# Scalable E-commerce Platform

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
├── user-service/        # Registration, login (JWT), profile
│   ├── pom.xml          # standalone (Spring Boot 4.1.x parent, Java 21)
│   ├── mvnw, .mvn/      # the service's own Maven wrapper
│   ├── Dockerfile
│   └── src/
├── docker-compose.yml   # local dev: databases + all services
└── .env.example
```

Adding a service means adding a new top-level folder like `user-service/` and a
matching entry in `docker-compose.yml`.

## Architecture decisions

- [001 - Single repository](docs/decisions/001-single-repository.md)
- [002 - RabbitMQ over Kafka](docs/decisions/002-rabbitmq-over-kafka.md)
- [003 - Spring Security JWT instead of jjwt](docs/decisions/003-spring-security-jwt-over-jjwt.md)
- [004 - RS256 with JWKS instead of a shared HS256 secret](docs/decisions/004-rs256-jwks-and-roles.md)

## Getting started

Prerequisites: JDK 21 and Docker with Compose. Maven is not required: each service bundles its own wrapper (`./mvnw`).

```bash
cp .env.example .env              # then set JWT_PRIVATE_KEY (see "Signing key") and the DB password
docker compose up --build         # PostgreSQL + user-service on http://localhost:8080
```

Build and test a single service from its own folder:

```bash
cd user-service
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

## Status

Early stage: project skeleton and User Service MVP.
