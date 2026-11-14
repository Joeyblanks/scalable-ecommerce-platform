# 004. RS256 with a JWKS endpoint, and roles in the token

- Status: Accepted
- Date: 2026-11-14

## Context

user-service originally signed tokens with HS256, a symmetric secret. That
works for one service, but every other service that wants to verify tokens
(starting with product-catalog-service) would need the same secret. Anyone
holding it can also *forge* tokens, so each extra verifier widens the blast
radius of a leak. We also need authorisation by role (CUSTOMER / ADMIN),
which the token did not carry.

## Decision

1. **Sign with RS256.** user-service holds an RSA private key (2048+ bits).
   It publishes only the public key at `GET /.well-known/jwks.json`. Other
   services verify with `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`
   and hold no secret.
2. **Key handling.**
   - The private key is never committed. It is supplied per environment as
     `JWT_PRIVATE_KEY` (base64 of a PKCS#8 PEM) or `JWT_PRIVATE_KEY_FILE`
     (mounted PEM); exactly one must be set, or the service refuses to start.
   - The key must stay stable across restarts, otherwise all issued tokens
     become invalid.
   - The public key is derived from the private key (one secret to manage).
     The `kid` header is the RFC 7638 thumbprint of the public key, so it is
     deterministic and changes automatically when the key changes.
   - Error messages and `toString()` never include key material.
3. **Roles.** `users.role` is `CUSTOMER` or `ADMIN` (DB check constraint,
   default `CUSTOMER`). The token carries `"roles": ["ADMIN"]`; resource
   servers map it to `ROLE_ADMIN`. `/me` returns the role.
4. **Admin creation.** Self-registration always creates a CUSTOMER and ignores
   any supplied role. The first ADMIN is created at startup from
   `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` (both or neither, no
   default password, minimum 12 characters). It is idempotent and never
   modifies or promotes an existing account. There is no endpoint that can
   create or promote admins.

## Consequences

Positive:
- A compromised catalog (or any verifying service) cannot mint tokens: it
  only has the public key.
- Verifiers need no shared secret and no coordination with user-service
  beyond the JWKS URL.
- Key rotation is possible without downtime: publish old and new public keys
  in the JWKS together (verifiers select by `kid`) while signing with the
  new one. Not implemented yet; the format already supports it.

Negative:
- RSA signing and verification are slower than HMAC (irrelevant at our scale).
- Verifying services depend on user-service being reachable to fetch the JWKS
  the first time (Spring caches it afterwards), and the private key must be
  provisioned and backed up securely by operators.
- Roles in the token are only as fresh as the token (1 hour): a demoted admin
  keeps the role until the token expires. Acceptable now; revisit with
  shorter lifetimes or token revocation if needed.
- Breaking change: tokens signed with the old HS256 secret stop working.

Revisit when we need multiple active signing keys, key rotation tooling, or
an external identity provider.
