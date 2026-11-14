package io.github.joeyblanks.ecommerce.cart.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.cart")
public record CartProperties(
        @NotNull Duration ttl,
        @Min(1) int maxLines,
        @Min(1) int maxQuantity,
        @Min(1) int casMaxAttempts) {
}
