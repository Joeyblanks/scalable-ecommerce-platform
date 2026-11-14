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

## Getting started

Prerequisites: JDK 21 and Docker with Compose. Maven is not required: each service bundles its own wrapper (`./mvnw`).

```bash
cp .env.example .env              # then set JWT_SECRET (min 32 chars) and DB password
docker compose up --build         # PostgreSQL + user-service on http://localhost:8080
```

Build and test a single service from its own folder:

```bash
cd user-service
./mvnw verify                     # unit tests + integration tests (needs Docker)
```

### Tests

- Unit tests (`*Test`, Surefire) run in the `test` phase and need nothing external.
- Integration tests (`*IT`, Failsafe) run in the `verify` phase against a real PostgreSQL
  started by Testcontainers, so **Docker must be running**.

### User Service API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/users/register` | none | `{email, password, fullName}` -> 201 profile (409 if email taken) |
| POST | `/api/users/login` | none | `{email, password}` -> `{accessToken, tokenType, expiresIn}` |
| GET | `/api/users/me` | Bearer JWT | Current user's profile |
| GET | `/actuator/health` | none | Health check |

```bash
curl -H 'Content-Type: application/json' -d '{"email":"ann@example.com","password":"password123","fullName":"Ann"}' localhost:8080/api/users/register
TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"email":"ann@example.com","password":"password123"}' localhost:8080/api/users/login | jq -r .accessToken)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/users/me
```

## Status

Early stage: project skeleton and User Service MVP.
