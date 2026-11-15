package io.github.joeyblanks.ecommerce.payment.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All of payment-service's own settings, grouped by concern. */
public final class AppProperties {

    private AppProperties() {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.security")
    public record Security(@NotBlank String jwkSetUri, @NotBlank String issuer) {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.events")
    public record Events(@NotBlank String exchange) {
    }

    /**
     * @param lease            how long a worker owns a payment it claimed; after that another worker may take it
     * @param retryInitialDelay first retry delay after a transient provider problem (doubles, capped by retryMaxDelay)
     * @param needsReviewAfter a payment still without a definitive provider answer this long after creation
     *                         stops being retried and is flagged NEEDS_REVIEW
     */
    @Validated
    @ConfigurationProperties(prefix = "app.payments")
    public record Payments(@NotBlank String currency, @NotNull Duration lease, @NotNull Duration retryInitialDelay,
                           @NotNull Duration retryMaxDelay, @NotNull Duration needsReviewAfter, @Min(1) int batchSize) {
    }

    /**
     * Stripe test-mode settings. The key comes only from the environment ({@code STRIPE_API_KEY}); it is
     * never committed and never printed ({@code toString} hides it).
     *
     * @param apiBase override of Stripe's API URL (tests point it at stripe-mock); empty = Stripe
     */
    @ConfigurationProperties(prefix = "app.stripe")
    public record Stripe(String apiKey, String apiBase) {
        @Override
        public String toString() {
            return "Stripe[apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "***") + ", apiBase=" + apiBase + "]";
        }
    }

    @Validated
    @ConfigurationProperties(prefix = "app.jobs")
    public record Jobs(@NotNull Duration outboxRelayInterval, @Min(1) int outboxBatchSize,
                       @NotNull Duration publishConfirmTimeout, @NotNull Duration chargeWorkerInterval,
                       @NotNull Duration refundWorkerInterval, @NotNull Duration cleanupInterval) {
    }

    @Validated
    @ConfigurationProperties(prefix = "app.retention")
    public record Retention(@NotNull Duration publishedOutboxEvents, @NotNull Duration processedEvents) {
    }
}
