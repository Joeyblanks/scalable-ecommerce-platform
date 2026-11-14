package io.github.joeyblanks.ecommerce.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joeyblanks.ecommerce.user.config.JwtConfig;
import io.github.joeyblanks.ecommerce.user.config.JwtProperties;
import io.github.joeyblanks.ecommerce.user.dto.TokenResponse;
import io.github.joeyblanks.ecommerce.user.model.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-123";
    private final JwtConfig config = new JwtConfig();
    private final User user = new User(UUID.randomUUID(), "a@b.com", "hash", "Ann", Instant.now());

    private JwtService serviceAt(Instant now, JwtProperties props) {
        return new JwtService(config.jwtEncoder(props), props, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuedTokenRoundTripsWithSubjectAndEmail() {
        JwtProperties props = new JwtProperties(SECRET, Duration.ofHours(1));
        TokenResponse token = serviceAt(Instant.now(), props).issueToken(user);

        Jwt jwt = config.jwtDecoder(props).decode(token.accessToken());

        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.<String>getClaim("email")).isEqualTo("a@b.com");
        assertThat(token.tokenType()).isEqualTo("Bearer");
        assertThat(token.expiresIn()).isEqualTo(3600);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtProperties props = new JwtProperties(SECRET, Duration.ofMinutes(5));
        TokenResponse token = serviceAt(Instant.now().minus(Duration.ofHours(2)), props).issueToken(user);

        assertThatThrownBy(() -> config.jwtDecoder(props).decode(token.accessToken()))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtProperties props = new JwtProperties(SECRET, Duration.ofHours(1));
        JwtProperties other = new JwtProperties("another-secret-another-secret-1234567", Duration.ofHours(1));
        TokenResponse token = serviceAt(Instant.now(), props).issueToken(user);
        JwtDecoder otherDecoder = config.jwtDecoder(other);

        assertThatThrownBy(() -> otherDecoder.decode(token.accessToken())).isInstanceOf(Exception.class);
    }
}
