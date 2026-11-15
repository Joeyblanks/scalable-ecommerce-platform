# 011. API gateway, service discovery and rate limiting

- Status: Accepted. Written in steps: each pull request of this work adds its section, and the sections not yet
  built are marked "planned".
- Date: 2026-11-15

## Context

Today every service publishes its own port, nothing sits in front of them, and three entry points have no rate limit
at all: customer login, customer register, and the service-token endpoint (`POST /oauth2/token`, ADR 008). The plan
for this work, in four pull requests:

1. **Rate limits that only user-service can enforce** (this section is built).
2. **api-gateway** as the only published port: routes, JWT first check, per-IP limits, timeouts and errors,
   correlation id (planned).
3. **Service discovery with Eureka** (planned).
4. **Correlation id through every service, log line and event** (planned).

## Decisions

### 1. Each rate limit lives where the abuse can actually be seen

| Limit | Where | Why there |
|---|---|---|
| Login and register, per client IP | gateway (planned) | Only the gateway sees real client addresses. |
| API in general, per client IP | gateway (planned) | Basic flood protection at the edge. |
| **Failed logins, per account** | **user-service** | An attacker rotates addresses; a per-IP limit alone never protects one account. The gateway does not know which account is attacked, and should not parse login bodies. |
| **`/oauth2/token`, per caller and per client** | **user-service** | The endpoint is never routed publicly (the gateway will refuse `/oauth2/**`). Only containers inside the network call it, so the gateway never sees those calls. |

Limits are in memory and per instance (a second user-service instance counts separately, so the effective limit is
at most N times larger). Memory is bounded: each key keeps only its last `max` timestamps, and at most 10,000 keys are
tracked per limiter (expired ones are dropped first, then the least recently used). Moving the counters into Redis is the
upgrade if user-service is ever scaled out; the limiter is one small class.

### 2. Failed logins are limited per account, and that lets an attacker lock someone out for a while

- **Rule.** 10 failed logins for one account within 15 minutes block that account's logins (429 with `Retry-After`)
  until the oldest failure is 15 minutes old. The key is the normalized email (trimmed, lower case), **whether or not the
  account exists**, so the answer does not reveal which emails are registered. A successful login clears the count,
  so ordinary typing mistakes never add up.
- **The trade-off, stated plainly.** Anyone who knows an email address can send bad passwords for it and keep that person
  from logging in. The block is temporary, but an attacker who keeps sending can renew it.
- **Why we accept it.**
  - The alternative is an attacker trying passwords for one account without any limit. Account takeover is worse than
    a temporary denial of login.
  - The lockout needs continuous traffic from the attacker and ends by itself: nothing is locked permanently and no
    support action is needed.
  - Nothing else is lost during a block: the person's existing tokens keep working (they are valid for an hour), and no
    other account is affected.
  - The blocked path is cheap: the check happens before the database lookup and before any password hashing, so an
    attacker cannot use the throttle to burn our CPU.
  - The per-IP limit at the gateway (planned) makes a sustained lockout from one machine hard, and the limits are
    configuration (`app.rate-limits.login.*`).
- **What would justify changing it later.** If lockouts of real users happen, the next step is to throttle by
  account *and* address together (an attacker then only locks themselves out), plus a second factor or a challenge
  for blocked accounts. Both need more than this system has today.
- **Known gap.** The check and the counting are two steps, so a burst of parallel guesses can slip a few attempts past
  the limit before the block applies. It is bounded by the number of concurrent requests, not unlimited.

### 3. The service-token endpoint: failures by address, successes by client

Two limits, keyed so that one caller can never hurt another:

- **Failed requests, per caller address**: 10 per minute. Guessing a client secret is only possible by failing, so this
  stops guessing without affecting anyone else. Status 400 and 401 count as failures.
- **Successful requests, per client id**: 30 per minute. Tokens live 5 minutes and every service caches them (ADR 008),
  so a client asking more often is misbehaving. **Only successes count towards it.** If failures counted, anyone who can
  reach the endpoint could send bad requests with `order-service` as the client id and lock the real order-service out.
  With this keying, an attacker's failures use up neither that service's budget nor its access, and the attacker's own
  address is blocked after 10 failures.
- A blocked request gets 429 with `Retry-After` and `{"error":"too_many_requests"}`, even if the secret was right, and is
  refused before client authentication, so it costs no password hashing.
- **The address is the real connection address** (`request.getRemoteAddr()`). `X-Forwarded-For` and similar headers are never
  read, because a caller can send anything in them and would use that to dodge the limit. A test sends forged headers
  and stays blocked. (The same rule holds in the gateway; behind a trusted load balancer it would change in a controlled
  way, see the gateway section when it is added.)
- The limit sits in a filter at the front of the token endpoint's filter chain only (it is deliberately not a servlet-filter bean,
  so it cannot run anywhere else).

## Consequences

Positive:
- The three unprotected entry points get limits, each in the component that can enforce it.
- A blocked caller costs us almost nothing, and cannot use the limits to harm another service or (beyond the trade-off
  in section 2) another user.

Negative:
- Counters are per instance and lost on restart (an attacker gets a fresh budget after a restart).
- The login lockout trade-off in section 2.
- Defaults are a judgement, not measured traffic; they are configuration.

## How each protection was proven

Each protection was switched off on its own (one change at a time), the full suite was run, and the source was
restored afterwards and checked identical. Every one made at least one test fail:

| Protection removed | Tests that failed |
|---|---|
| the limiter blocking at all | 16 |
| the window expiring (a block never ends) | 4 |
| a correct `Retry-After` value | 3 |
| the bound on tracked keys (memory) | 1 |
| `reset` after a successful login | 2 |
| login key normalization (case, whitespace) | 1 |
| throttling unknown emails too | 2 |
| counting failures for unknown emails / for wrong passwords | 1 / 4 |
| `Retry-After` header on the login 429 | 1 |
| token endpoint: real connection address instead of `X-Forwarded-For` | 1 unit, 1 integration |
| token endpoint: failures keyed by address, not by client id | 1 |
| token endpoint: per-client limit on successes | 1 unit, 1 integration |
| token endpoint: only successes use a client's budget | 3 |
| token endpoint: counting failures at all | 4 |
| the filter being in the token endpoint's chain | 4 (integration) |
| `Retry-After` header on the token 429 | 1 |
| the filter acting on POST only | 1 |
| 200 as the only status that counts as a success | 5 |

Honest limits: the counters are in memory and per instance (section 1); the window tests move a fake clock, so real
time is not waited for; Maven stops before the integration tests once a unit test fails, so mutations caught by a unit
test were not also run against the integration tests, except the two marked "integration", which were run on their own.
