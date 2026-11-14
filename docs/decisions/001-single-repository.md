# 001. Single repository (monorepo)

- Status: Accepted
- Date: 2026-11-14

## Context

The platform is split into several microservices (user, catalog, orders, ...).
We must decide whether each service lives in its own repository or all
services live together in one.

## Decision

Keep all services, shared build configuration and infrastructure definitions
in a single repository, organised as a Maven multi-module project.

## Consequences

Positive:
- Cross-service changes (e.g. an event schema and its consumers) land in one
  atomic pull request.
- One place for CI, dependency versions (parent POM BOM), and Docker Compose.
- Easier onboarding and refactoring while service boundaries are still evolving.

Negative:
- CI must build and test only what changed, or build times grow.
- Weaker enforced isolation: teams must keep discipline on module boundaries
  (no service depending on another's internals).
- Per-service access control and independent release tagging are harder.

Services remain independently deployable; only source hosting is shared. If
the repo outgrows this model, modules can be split out with history preserved.
