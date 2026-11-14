package io.github.joeyblanks.ecommerce.catalog.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Where to find the token issuer's public keys. No secret is needed to verify tokens. */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        @NotBlank String jwkSetUri,
        @NotBlank String issuer,
        // The "aud" value a service token must carry to call this service's internal endpoints.
        @NotBlank String audience) {
}
