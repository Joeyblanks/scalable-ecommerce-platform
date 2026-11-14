package io.github.joeyblanks.ecommerce.user.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT settings bound from {@code app.jwt.*}.
 *
 * <p>The RSA private key is never part of the repository. Provide it with exactly one of:
 * <ul>
 *   <li>{@code privateKey} ({@code JWT_PRIVATE_KEY}): the PKCS#8 PEM, either base64-encoded or raw;</li>
 *   <li>{@code privateKeyFile} ({@code JWT_PRIVATE_KEY_FILE}): path to a mounted PKCS#8 PEM file.</li>
 * </ul>
 * The key must be the same across restarts so previously issued tokens stay valid.
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String privateKey,
        String privateKeyFile,
        @NotBlank String issuer,
        @NotNull Duration expiration) {

    @Override
    public String toString() {
        // Never let key material end up in logs.
        return "JwtProperties[issuer=" + issuer + ", expiration=" + expiration + "]";
    }
}
