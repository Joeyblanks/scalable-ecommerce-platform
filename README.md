# Scalable E-commerce Platform

An e-commerce platform built as a set of Java 21 / Spring Boot microservices in a single repository.

## Tech stack

| Concern        | Choice                                  |
|----------------|-----------------------------------------|
| Language       | Java 21                                 |
| Framework      | Spring Boot                             |
| Build          | Maven (multi-module)                    |
| Database       | PostgreSQL, one database per service    |
| Messaging      | RabbitMQ (domain events between services) |
| Local runtime  | Docker Compose                          |

## Repository layout

```
.
├── docs/decisions/   # Architecture Decision Records (ADRs)
└── services/         # One Maven module per microservice (added as built)
```

## Architecture decisions

- [001 - Single repository](docs/decisions/001-single-repository.md)
- [002 - RabbitMQ over Kafka](docs/decisions/002-rabbitmq-over-kafka.md)

## Getting started

Prerequisites: JDK 21, Maven 3.9+, Docker with Compose.

Build and run instructions will be added with the first service
(see the "Project skeleton and User Service MVP" issue).

## Status

Early stage: project skeleton and first service in progress.
