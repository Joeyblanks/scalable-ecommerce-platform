# 005. Continuous integration with one GitHub Actions workflow

- Status: Accepted
- Date: 2026-11-14

## Context

The repository holds several independently buildable services (ADR 001), each
with its own `pom.xml`, Maven wrapper and Dockerfile. There is no root build,
so nothing builds "everything" in one command. CI must:

- run `./mvnw verify` for each service (unit tests and the Testcontainers
  integration tests, which need Docker);
- prove each service's Dockerfile builds, without publishing anything yet;
- build only the services a change touches, so a catalog-only change does not
  pay for the user-service build and vice versa;
- run on pull requests and on pushes to `main`, and reuse downloaded Maven
  dependencies between runs.

## Decision

One workflow, `.github/workflows/ci.yml`, with these jobs:

1. **detect**: a small script (`.github/scripts/detect-services.sh`) lists the
   services (top-level folders containing both `pom.xml` and a `Dockerfile`)
   and selects those with changed files, using `git diff` against the PR base
   or the previous `main` commit. It outputs a JSON array.
2. **test** (matrix over that array): `./mvnw -B verify` in the service folder
   on `ubuntu-latest` with Java 21 (Temurin). Surefire runs the unit tests and
   Failsafe runs the `*IT` Testcontainers tests. Reports are uploaded when it
   fails.
3. **docker** (same matrix): `docker build` using the service folder as the
   context, plus a check that the image contains the application jar. Nothing
   is pushed.
4. **ci-ok**: one stable job that fails if any other job failed or was
   cancelled. It is the single check to require in branch protection.

Rules:

- **Everything is built** when `.github/` changes (so a CI change is validated
  by running it), on a manual `workflow_dispatch`, and when the comparison base
  is unknown (first push, force-push). **Nothing is built** for changes that
  touch no service (docs, README, compose file), and `ci-ok` still passes.
- Pull requests are tested on GitHub's merge commit, so the result reflects
  what `main` would look like after merging.
- **Caching:** `actions/cache` stores `~/.m2/repository` and the Maven wrapper
  distribution (`~/.m2/wrapper`), keyed per service on its `pom.xml` and wrapper
  properties, with a per-service restore prefix so a changed `pom.xml` still
  starts from the previous cache.
- **Security:** read-only `contents: read` permission, no secrets used, and
  only first-party `actions/*` actions, pinned to full commit SHAs (version in
  a comment). No third-party actions. Untrusted values (branch names, PR
  titles) are never interpolated into shell scripts; the script only receives
  commit SHAs through environment variables.
- A newer push to the same pull request cancels the superseded run; runs on
  `main` are never cancelled.

## Alternatives considered

- **One workflow per service with `on.paths` filters.** Simple, but duplicates
  the workflow for every service and, because a path-filtered workflow does not
  report a status when skipped, makes required checks awkward. A single
  workflow with a gate job avoids both problems.
- **A path-filter action such as `dorny/paths-filter`.** Convenient, but a
  third-party dependency in the CI supply chain for something a 40-line script
  does. We keep the surface to first-party actions.
- **A root multi-module build (`mvn -pl ... -am`).** Rejected by ADR 001:
  services share no build.

## Consequences

Positive:
- Pull requests only pay for the services they change; adding a service needs
  no CI change as long as it has a `pom.xml` and a `Dockerfile` at the top
  level of its folder.
- The Dockerfile of every changed service is verified on every change, so
  deployment breakage is found before merge.
- Third-party exposure is limited to the pinned first-party actions.

Negative:
- The detection script is custom code we own. It is deliberately small, and
  its decisions are printed in the job log.
- The pinned action SHAs must be bumped by hand (or by Dependabot, which we
  have not set up yet).
- A change to something shared that is *not* under `.github/` (for example, a
  future shared contract folder) will not trigger service builds until the
  script learns about it.
- Integration tests pull `postgres:17` on each run (no image cache).

Not done yet, by design: pushing images to a registry, deploying, per-service
status badges (a badge reflects a whole workflow, not a single job), and
requiring `CI result` in branch protection. Revisit when we deploy.
