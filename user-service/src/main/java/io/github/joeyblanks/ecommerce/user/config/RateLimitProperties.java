package io.github.joeyblanks.ecommerce.user.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Rate limits that only user-service can enforce (ADR 011). Per-IP limits on login and register live in the
 * gateway, which is the only component that sees client addresses.
 *
 * @param login        failed logins per account (normalized email)
 * @param token        the service-token endpoint: failed requests per caller address, successful ones per client id
 * @param maxTrackedKeys upper bound for the in-memory counters of each limiter
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limits")
public record RateLimitProperties(@Valid @NotNull Login login, @Valid @NotNull Token token, @Min(1) int maxTrackedKeys) {

    public record Login(@Min(1) int maxFailures, @NotNull Duration window) {
    }

    public record Token(@Min(1) int maxFailuresPerAddress, @NotNull Duration failureWindow,
                        @Min(1) int maxPerClient, @NotNull Duration clientWindow) {
    }
}
