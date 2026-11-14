# 001. Single repository, independent services

- Status: Accepted (amended 2026-11-14: services share no build)
- Date: 2026-11-14

## Context

The platform is split into several microservices (user, catalog, orders, ...).
We must decide whether each service lives in its own repository or all
services live together in one, and how much build tooling they share.

## Decision

Keep all services, documentation and local infrastructure definitions in a
single repository, but make **every service independently buildable and
deployable with no shared build**:

- Each service is a top-level folder (e.g. `user-service/`) with its own
  `pom.xml`, Maven wrapper, Dockerfile and tests.
- There is no root or parent POM and no multi-module reactor build. Each
  service declares its own parent (Spring Boot) and dependencies directly.
- A service's Docker build context is its own folder. Nothing outside it is
  needed to build the image.
- Only `docs/` and the local-development `docker-compose.yml` live at the
  root, and they do not take part in any service's build.

## Consequences

Positive:
- A change to one service cannot break another service's build, and services
  can upgrade Spring Boot, Java or dependencies on their own schedule.
- CI can build and test only the folder that changed, and each service
  can be released and deployed independently.
- Moving a service into its own repository later is trivial: copy the folder.
- Cross-cutting changes (e.g. an event schema and its consumers) still land
  in one atomic pull request, and there is one place for docs and local infra.

Negative:
- Build configuration is duplicated per service (Maven wrapper, plugin and
  Spring Boot versions), and keeping versions aligned is a manual effort.
- Code is not shared through the build. Shared code must be copied or
  published as a versioned artifact, so we avoid shared libraries for now.
- Weaker enforced isolation of ownership: teams must keep discipline so no
  service reaches into another's internals.
