# 003. Spring Security's built-in JWT support instead of jjwt

- Status: Accepted (the HS256 signing choice was superseded by [004](004-rs256-jwks-and-roles.md); the library choice stands)
- Date: 2026-11-14

## Context

The User Service issues JWT access tokens at login and verifies them on
protected endpoints. The common choice is the jjwt library. We build on
Spring Boot 4.1, which moved to Jackson 3 (`tools.jackson`) as its default JSON
library. The latest jjwt release at the time of writing (0.13.0) still depends
on Jackson 2 (`com.fasterxml.jackson`); we found no release with Jackson 3
support. Using it would put two Jackson generations on the classpath: Boot
auto-configures only Jackson 3, while jjwt would need Jackson 2 (or the
Gson/org.json alternatives) for its own serialisation.

## Decision

Use Spring Security's built-in JWT support (`spring-boot-starter-security-oauth2-resource-server`,
backed by Nimbus JOSE + JWT):

- `JwtEncoder` (`NimbusJwtEncoder`) issues HS256-signed tokens at login.
- `JwtDecoder` (`NimbusJwtDecoder`) plus `oauth2ResourceServer().jwt()` verifies
  signature and expiry on every request, producing a `Jwt` principal.

## Consequences

Positive:
- Versions are managed by the Spring Boot BOM, so they are tested together
  with Spring Security and move with Boot upgrades.
- No second Jackson on the classpath and no dependency on a third-party
  library's Jackson 3 roadmap.
- Token verification is the standard, widely reviewed resource-server filter
  rather than hand-written filter code, which reduces security-sensitive code
  we own.
- Other services can reuse the same resource-server configuration to validate
  tokens from this service.

Negative:
- A slightly more verbose API for building claims than jjwt's fluent builder.
- HS256 uses one shared secret, so every verifying service needs it.

Revisit if we need asymmetric keys: switching to RS256/ES256 with a JWKS
endpoint is supported by the same Spring Security APIs, and would let other
services verify tokens without holding a signing secret. Also revisit if jjwt
ships first-class Jackson 3 support and offers a concrete advantage.
