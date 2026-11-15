package io.github.joeyblanks.ecommerce.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ServiceTokensTest {

    private static JwtAuthenticationToken token(boolean withClientId) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("x")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (withClientId) {
            b.claim("client_id", "order-service").claim("scope", List.of("stock:reserve"));
        } else {
            b.claim("roles", List.of("ADMIN"));
        }
        return new JwtAuthenticationToken(b.build());
    }

    @Test
    void aTokenWithClientIdIsAServiceToken() {
        assertThat(ServiceTokens.isServiceToken(token(true))).isTrue();
    }

    @Test
    void userTokensAnonymousAndNoAuthenticationAreNot() {
        assertThat(ServiceTokens.isServiceToken(token(false))).isFalse();
        assertThat(ServiceTokens.isServiceToken(new AnonymousAuthenticationToken("k", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).isFalse();
        assertThat(ServiceTokens.isServiceToken(null)).isFalse();
    }

    private static JwtAuthenticationToken service(Object scope, List<String> audience) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("notification-service")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("client_id", "notification-service");
        if (scope != null) {
            b.claim("scope", scope);
        }
        if (audience != null) {
            b.audience(audience);
        }
        return new JwtAuthenticationToken(b.build());
    }

    private static boolean allows(Authentication a) {
        return ServiceTokens.serviceTokenWith("users:read-contact", "user-service")
                .authorize(() -> a, null).isGranted();
    }

    @Test
    void theContactGuardNeedsAServiceTokenWithTheScopeAndTheAudience() {
        assertThat(allows(service(List.of("users:read-contact"), List.of("user-service")))).isTrue();
        assertThat(allows(service("users:read-contact other", List.of("user-service")))).isTrue();
        assertThat(allows(service(List.of("other", "users:read-contact"), List.of("x", "user-service")))).isTrue();
    }

    @Test
    void theContactGuardRefusesEverythingElse() {
        assertThat(allows(service(List.of("stock:reserve"), List.of("user-service")))).as("wrong scope").isFalse();
        assertThat(allows(service(null, List.of("user-service")))).as("no scope requested").isFalse();
        assertThat(allows(service(List.of("users:read-contact"), List.of("product-catalog-service")))).as("other audience").isFalse();
        assertThat(allows(service(List.of("users:read-contact"), null))).as("no audience").isFalse();
        assertThat(allows(service("users:read-contacts", List.of("user-service")))).as("scope prefix is not the scope").isFalse();
        assertThat(allows(token(false))).as("customer/admin token").isFalse();
        assertThat(allows(new AnonymousAuthenticationToken("k", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).isFalse();
    }

    @Test
    void aUserTokenCannotBecomeOneByCarryingAScopeAndAudience() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).claim("roles", List.of("ADMIN"))
                .claim("scope", List.of("users:read-contact")).audience(List.of("user-service")).build();

        assertThat(allows(new JwtAuthenticationToken(jwt))).isFalse(); // no client_id: not a service token
    }
}
