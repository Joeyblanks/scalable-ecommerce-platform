package io.github.joeyblanks.ecommerce.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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
}
