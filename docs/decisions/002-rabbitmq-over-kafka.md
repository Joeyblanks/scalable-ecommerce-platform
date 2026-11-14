# 002. RabbitMQ over Kafka for messaging

- Status: Accepted
- Date: 2026-11-14

## Context

Services need asynchronous communication for domain events (e.g. user
registered, order placed). The two main candidates are RabbitMQ and Apache Kafka.

## Decision

Use RabbitMQ as the message broker.

## Rationale

- Our workload is task/event routing with modest throughput, not
  high-volume log streaming; RabbitMQ handles this comfortably.
- Flexible routing (topic/direct/fanout exchanges), per-message acks,
  retries and dead-letter queues map directly to our needs.
- Much lighter to run locally and operate than Kafka (no ZooKeeper/KRaft
  cluster tuning, partition planning) and simple in Docker Compose.
- Spring AMQP gives first-class Spring Boot support.

## Consequences

Positive:
- Lower operational and learning overhead for a small team.
- Straightforward local development setup.

Negative:
- No built-in long-term event log or easy replay of historical events.
- Throughput ceiling is lower than Kafka's; very high-volume streaming
  (e.g. clickstream analytics) would need a revisit.

Revisit if we need event sourcing, replay, or sustained very high throughput.
