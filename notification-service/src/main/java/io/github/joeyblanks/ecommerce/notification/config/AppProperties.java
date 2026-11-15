package io.github.joeyblanks.ecommerce.notification.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All of notification-service's own settings, grouped by concern. */
public final class AppProperties {

    private AppProperties() {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.events")
    public record Events(@NotBlank String exchange) {
    }

    /**
     * @param lease          how long a worker owns a notification it claimed; after that another worker may take it
     * @param maxAttempts    after this many failed attempts the notification becomes FAILED (never retried again)
     * @param giveUpAfter    ... or after this long since it was created, whichever comes first
     * @param from           the From address of every email
     */
    @Validated
    @ConfigurationProperties(prefix = "app.notifications")
    public record Notifications(@NotNull Duration lease, @NotNull Duration retryInitialDelay, @NotNull Duration retryMaxDelay,
                                @Min(1) int maxAttempts, @NotNull Duration giveUpAfter, @Min(1) int batchSize,
                                @NotBlank String from) {
    }

    /** user-service: where an address comes from. Calls use a short-lived service token (ADR 008, 010). */
    @Validated
    @ConfigurationProperties(prefix = "app.user-service")
    public record UserService(@NotBlank String baseUrl, @NotBlank String tokenUri, @NotBlank String clientId,
                              String clientSecret, @NotBlank String scope, @NotNull Duration connectTimeout,
                              @NotNull Duration readTimeout) {
        @Override
        public String toString() {
            return "UserService[baseUrl=" + baseUrl + ", clientId=" + clientId + ", clientSecret="
                    + (clientSecret == null || clientSecret.isBlank() ? "unset" : "***") + "]";
        }
    }

    @Validated
    @ConfigurationProperties(prefix = "app.jobs")
    public record Jobs(@NotNull Duration workerInterval, @NotNull Duration cleanupInterval) {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.retention")
    public record Retention(@NotNull Duration sentNotifications) {
    }
}
