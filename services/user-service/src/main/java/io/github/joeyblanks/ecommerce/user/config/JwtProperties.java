package io.github.joeyblanks.ecommerce.user.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** JWT settings bound from {@code app.jwt.*}. */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        // HS256 needs a 256-bit key, so refuse anything shorter than 32 bytes at startup.
        @NotNull @Size(min = 32) String secret,
        @NotNull Duration expiration) {
}
