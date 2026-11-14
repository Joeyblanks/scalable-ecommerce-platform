package io.github.joeyblanks.ecommerce.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.catalog.config.SecurityConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** The "roles" claim written by user-service must become ROLE_* authorities here. */
class RoleMappingTest {

    private final SecurityConfig config = new SecurityConfig();

    private Jwt tokenWith(Object roles) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (roles != null) {
            b.claim("roles", roles);
        }
        return b.build();
    }

    /** Only the ROLE_* authorities: Spring Security 7 also adds an informational FACTOR_BEARER one. */
    private List<String> authorities(Jwt jwt) {
        return config.jwtAuthenticationConverter().convert(jwt).getAuthorities().stream()
                .map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_")).toList();
    }

    @Test
    void mapsRolesClaimToRoleAuthorities() {
        assertThat(authorities(tokenWith(List.of("ADMIN")))).containsExactly("ROLE_ADMIN");
        assertThat(authorities(tokenWith(List.of("CUSTOMER")))).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void tokenWithoutRolesGetsNoAuthorities() {
        assertThat(authorities(tokenWith(null))).isEmpty();
    }
}
