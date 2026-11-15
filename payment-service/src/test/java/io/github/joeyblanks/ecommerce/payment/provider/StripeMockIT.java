package io.github.joeyblanks.ecommerce.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.payment.config.AppProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the real Stripe SDK calls against stripe-mock, Stripe's own server that validates every request
 * against Stripe's published OpenAPI schema. It proves our requests are well-formed and our responses are
 * parsed. It does not prove behaviour of Stripe itself (declines, idempotency); that needs Stripe's real
 * test API, see the README smoke check.
 */
class StripeMockIT {

    private static final GenericContainer<?> MOCK = new GenericContainer<>(DockerImageName.parse("stripe/stripe-mock:v0.197.0"))
            .withExposedPorts(12111)
            .waitingFor(Wait.forListeningPorts(12111));

    private static StripePaymentProvider provider;

    @BeforeAll
    static void start() {
        MOCK.start();
        provider = new StripePaymentProvider(new AppProperties.Stripe("sk_test_51Abcdefghijkl",
                "http://" + MOCK.getHost() + ":" + MOCK.getMappedPort(12111)));
    }

    @AfterAll
    static void stop() {
        MOCK.stop();
    }

    @Test
    void aChargeRequestPassesStripesSchemaAndTheAnswerIsParsed() {
        ChargeResult result = provider.charge(new BigDecimal("19.99"), "EUR", "charge-it-1");

        // stripe-mock always answers with the same fixture (a PaymentIntent that is NOT succeeded), so the
        // right outcome here is "unknown", and the reason proves the request passed schema validation (a
        // malformed request would be a 400 InvalidRequestException) and the response was parsed.
        assertThat(result).isInstanceOf(ChargeResult.Transient.class);
        assertThat(((ChargeResult.Transient) result).error()).startsWith("PaymentIntent status ");
    }

    @Test
    void aRefundRequestPassesStripesSchemaAndTheAnswerIsParsed() {
        RefundResult result = provider.refund("pi_123", "refund-it-1");

        assertThat(result).isInstanceOf(RefundResult.Refunded.class);
        assertThat(((RefundResult.Refunded) result).refundRef()).startsWith("re_");
    }
}
