# 008. Service-to-service authentication with OAuth2 client credentials

- Status: Accepted
- Date: 2026-11-14

## Context

The order saga (ADR 007) needs order-service to reserve and release stock in
product-catalog-service. Those endpoints must be callable by services only:
not by customers and not by ADMIN users. Every service already verifies
RS256 tokens from user-service against its JWKS (ADR 004), so the question is
how a *service* gets a token, and how every service tells a service token
apart from a user token.

Constraints: one issuer and one JWKS endpoint only; customer login must not
change.

## Decision

### Issuing: the OAuth2 `client_credentials` grant, in user-service

- `POST /oauth2/token` with `grant_type=client_credentials`, the client
  authenticating with HTTP Basic (`client_id:client_secret`). It is the only
  grant supported; every other grant is a 400.
- It is implemented with the Spring Security authorization server library,
  **used as a library only**. Boot's authorization-server auto-configuration is
  deliberately not used: it would create its own random signing key, a second
  JWKS endpoint, and OIDC and login endpoints. Our filter chain matches only
  `/oauth2/token`, so none of the library's other endpoints (authorize,
  introspection, revocation, metadata, its own JWKS) are reachable.
- Tokens are signed by the application's existing `JwtEncoder` (the same RSA
  key) and get the same `iss` as customer tokens, so they verify against the one
  existing `/.well-known/jwks.json`. No second key, issuer or JWKS exists.
- Tokens are short-lived (5 minutes) self-contained JWTs that are never
  refreshed, introspected or revoked. The authorization server therefore stores
  nothing about them (its default in-memory store would keep every token
  forever).

### What a service token looks like

```json
{ "iss": "user-service", "sub": "order-service", "client_id": "order-service",
  "aud": ["product-catalog-service"], "scope": ["stock:reserve"],
  "iat": ..., "exp": "iat + 5 min" }
```

- `client_id` (from RFC 9068, the JWT access token profile) is what marks a
  token as a service token. Tokens issued at customer login never have it, nor
  `scope`; service tokens never have `roles`.
- `aud` names the one service the token is meant for; each client is
  configured with its audience.
- **Scopes must be requested explicitly** (`scope=stock:reserve`). A request
  without `scope` gets a token with no scopes, which is accepted nowhere:
  least privilege by default.

### Separation, enforced in every service

- **Customer-facing endpoints refuse service tokens** with 403, including the
  public ones (register, login, catalog reads). Infrastructure endpoints that
  services legitimately need (the JWKS, health) stay open.
- **Internal endpoints (`/internal/**`) require a service token** with the
  right scope and audience. User tokens (CUSTOMER or ADMIN) have no scopes, so
  they are refused with 403.
- Each service implements this check itself (there is no shared code, ADR
  001), and each has tests proving both directions.

### Client secrets

- Clients are configured per environment. In user-service only a **bcrypt
  hash** of the secret is stored (`ORDER_SERVICE_CLIENT_SECRET_HASH`); the plain
  secret lives only in the calling service's environment. Nothing is committed.
- A plain secret mistakenly put where the hash belongs fails startup (the
  value must look like a bcrypt hash), and the error does not echo it.
- A client without a hash is disabled.

## Alternatives considered

- **A shared static API key per internal endpoint.** No identity, no expiry,
  and the same secret on both sides; rotation means changing every caller and
  callee at once.
- **Network isolation only** ("internal endpoints are not exposed"). Not real
  protection in our setup: in Compose every container is on one network and
  ports are published. Useful later as defense in depth at an API gateway.
- **mTLS between services.** The strongest option, but it needs a certificate
  authority and rotation tooling; disproportionate for now.
- **A hand-written token endpoint.** Small, but it is security-critical code
  (client authentication, error semantics) that the library already provides
  and that is widely reviewed (the same reasoning as ADR 003).
- **Telling tokens apart by `scope` or `roles` alone.** Fragile: a user token
  could gain a scope claim one day, and test tooling commonly adds `scope` to
  mock tokens. A dedicated `client_id` claim is explicit.

## Consequences

Positive:
- One issuer, one key, one JWKS for every token in the system; services verify
  service tokens with configuration they already have.
- Adding a service client is configuration only (client id, scopes, audience,
  secret hash).
- Tokens expire after 5 minutes, which limits the damage of a leaked token.

Negative:
- user-service becomes a dependency for service calls too (cached tokens soften
  this: a caller only needs a new token every few minutes).
- There is no revocation for service tokens; a leaked one is valid until it
  expires (at most 5 minutes). Rotate the client secret to stop new ones.
- The token endpoint has no rate limiting yet; bcrypt makes brute force slow but
  not impossible. Revisit with an API gateway.

Note on a library detail: the authorization server's POM excludes
`commons-logging` from its `spring-core` dependency, which under Maven's
nearest-wins rule removes it from the classpath. user-service therefore
declares `commons-logging` explicitly (see its `pom.xml`).
