package io.github.joeyblanks.ecommerce.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import io.github.joeyblanks.ecommerce.user.config.JwtConfig;
import io.github.joeyblanks.ecommerce.user.config.JwtProperties;
import io.github.joeyblanks.ecommerce.user.config.RsaKeyLoader;
import io.github.joeyblanks.ecommerce.user.dto.TokenResponse;
import io.github.joeyblanks.ecommerce.user.model.Role;
import io.github.joeyblanks.ecommerce.user.model.User;
import io.github.joeyblanks.ecommerce.user.support.TestKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class JwtServiceTest {

    private final JwtConfig config = new JwtConfig();
    private final JwtProperties props = new JwtProperties(TestKeys.sharedBase64Pem(), null, "user-service", Duration.ofHours(1));
    private final RSAKey key = config.jwtSigningKey(props);

    private User user(Role role) {
        return new User(UUID.randomUUID(), "a@b.com", "hash", "Ann", role, Instant.now());
    }

    private JwtService serviceAt(Instant now, JwtProperties p, RSAKey signingKey) {
        return new JwtService(config.jwtEncoder(signingKey), p, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** Verifies the way another service would: using only the published public key. */
    private JwtDecoder verifierUsingOnlyPublicKey(RSAKey signingKey) throws Exception {
        return NimbusJwtDecoder.withPublicKey(signingKey.toPublicJWK().toRSAPublicKey()).build();
    }

    @Test
    void tokenIsRs256WithKidSubjectEmailAndRoles() throws Exception {
        User user = user(Role.ADMIN);
        TokenResponse token = serviceAt(Instant.now(), props, key).issueToken(user);

        Jwt jwt = verifierUsingOnlyPublicKey(key).decode(token.accessToken());

        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", key.getKeyID());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.<String>getClaim("iss")).isEqualTo("user-service");
        assertThat(jwt.<String>getClaim("email")).isEqualTo("a@b.com");
        assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("ADMIN");
        assertThat(token.tokenType()).isEqualTo("Bearer");
        assertThat(token.expiresIn()).isEqualTo(3600);
    }

    @Test
    void customerTokenCarriesCustomerRole() throws Exception {
        TokenResponse token = serviceAt(Instant.now(), props, key).issueToken(user(Role.CUSTOMER));

        assertThat(verifierUsingOnlyPublicKey(key).decode(token.accessToken()).<List<String>>getClaim("roles"))
                .containsExactly("CUSTOMER");
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        RSAKey attacker = RsaKeyLoader.load(
                new JwtProperties(TestKeys.base64(TestKeys.pem(2048)), null, "user-service", Duration.ofHours(1)));
        TokenResponse forged = serviceAt(Instant.now(), props, attacker).issueToken(user(Role.ADMIN));

        assertThatThrownBy(() -> verifierUsingOnlyPublicKey(key).decode(forged.accessToken()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        String token = serviceAt(Instant.now(), props, key).issueToken(user(Role.CUSTOMER)).accessToken();
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"x\",\"roles\":[\"ADMIN\"]}".getBytes());

        assertThatThrownBy(() -> verifierUsingOnlyPublicKey(key).decode(parts[0] + "." + forgedPayload + "." + parts[2]))
                .isInstanceOf(Exception.class);
    }

    @Test
    void expiredTokenIsRejectedByOwnDecoder() {
        JwtProperties shortLived = new JwtProperties(TestKeys.sharedBase64Pem(), null, "user-service", Duration.ofMinutes(5));
        TokenResponse token = serviceAt(Instant.now().minus(Duration.ofHours(2)), shortLived, key)
                .issueToken(user(Role.CUSTOMER));

        assertThatThrownBy(() -> {
            JwtDecoder decoder = config.jwtDecoder(key, shortLived);
            decoder.decode(token.accessToken());
        }).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void tokenFromWrongIssuerIsRejectedByOwnDecoder() throws Exception {
        JwtProperties other = new JwtProperties(TestKeys.sharedBase64Pem(), null, "someone-else", Duration.ofHours(1));
        TokenResponse token = serviceAt(Instant.now(), other, key).issueToken(user(Role.CUSTOMER));

        assertThatThrownBy(() -> config.jwtDecoder(key, props).decode(token.accessToken()))
                .isInstanceOf(JwtValidationException.class);
    }
}
