package io.github.joeyblanks.ecommerce.order.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All of order-service's own settings, grouped by concern. */
public final class AppProperties {

    private AppProperties() {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.security")
    public record Security(@NotBlank String jwkSetUri, @NotBlank String issuer) {
    }

    /** This service's client credentials for user-service's token endpoint (ADR 008). */
    @Validated
    @ConfigurationProperties(prefix = "app.service-token")
    public record ServiceToken(@NotBlank String tokenUri, @NotBlank String clientId, String clientSecret,
                               @NotBlank String scope, @NotNull Duration timeout) {

        @Override
        public String toString() {
            return "ServiceToken[tokenUri=" + tokenUri + ", clientId=" + clientId + ", scope=" + scope + "]";
        }
    }

    @Validated
    @ConfigurationProperties(prefix = "app.cart")
    public record Cart(@NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.catalog")
    public record Catalog(@NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.events")
    public record Events(@NotBlank String exchange) {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.orders")
    public record Orders(@NotNull Duration paymentTimeout, @NotNull Duration stuckReservationAfter) {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.jobs")
    public record Jobs(@NotNull Duration outboxRelayInterval, @Min(1) int outboxBatchSize,
                       @NotNull Duration publishConfirmTimeout, @NotNull Duration stockReleaseInterval,
                       @NotNull Duration reconcileInterval, @NotNull Duration paymentTimeoutInterval,
                       @NotNull Duration cleanupInterval) {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.retention")
    public record Retention(@NotNull Duration publishedOutboxEvents, @NotNull Duration processedEvents,
                            @NotNull Duration completedStockReleases) {
    }
}
