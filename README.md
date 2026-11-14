# Scalable E-commerce Platform

An e-commerce platform built as a set of Java 21 / Spring Boot microservices in a single repository.

## Tech stack

| Concern        | Choice                                  |
|----------------|-----------------------------------------|
| Language       | Java 21                                 |
| Framework      | Spring Boot 4.1 (Spring Security 7, Jackson 3) |
| Build          | Maven (multi-module)                    |
| Database       | PostgreSQL, one database per service    |
| Messaging      | RabbitMQ (domain events between services) |
| Local runtime  | Docker Compose                          |

## Repository layout

```
.
├── docs/decisions/   # Architecture Decision Records (ADRs)
├── services/         # One Maven module per microservice
│   └── user-service/ # Registration, login (JWT), profile
├── docker-compose.yml
└── pom.xml           # Parent POM (Spring Boot 4.1.x, Java 21)
```

## Architecture decisions

- [001 - Single repository](docs/decisions/001-single-repository.md)
- [002 - RabbitMQ over Kafka](docs/decisions/002-rabbitmq-over-kafka.md)
- [003 - Spring Security JWT instead of jjwt](docs/decisions/003-spring-security-jwt-over-jjwt.md)

## Getting started

Prerequisites: JDK 21 and Docker with Compose. Maven is not required: use the bundled wrapper (`./mvnw`).

```bash
cp .env.example .env          # then set JWT_SECRET (min 32 chars) and DB password
./mvnw verify                 # build + run all tests
docker compose up --build     # PostgreSQL + user-service on http://localhost:8080
```

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
