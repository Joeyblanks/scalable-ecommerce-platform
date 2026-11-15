package io.github.joeyblanks.ecommerce.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.payment.config.AppProperties;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Which provider is used, decided only by the environment (PAYMENT_PROVIDER, STRIPE_API_KEY). */
class StripeProviderSelectionTest {

    private static final String TEST_KEY = "sk_test_51Abcdefghijkl";

    @EnableConfigurationProperties(AppProperties.Stripe.class)
    @Import({StripePaymentProvider.class, SimulatedPaymentProvider.class})
    static class Cfg {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Cfg.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void withoutAKeyStripeIsNotUsed() {
        runner.withPropertyValues("app.stripe.api-key=").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(StripePaymentProvider.class);
            assertThat(only(ctx)).isEqualTo("simulated");
        });
    }

    @Test
    void aTestKeySelectsStripeByDefault() {
        runner.withPropertyValues("app.stripe.api-key=" + TEST_KEY)
                .run(ctx -> assertThat(only(ctx)).isEqualTo("stripe-test"));
    }

    @Test
    void simulatedCanBeForcedEvenWhenAKeyIsSet() {
        runner.withPropertyValues("app.stripe.api-key=" + TEST_KEY, "app.payments.provider=simulated")
                .run(ctx -> assertThat(only(ctx)).isEqualTo("simulated"));
    }

    @Test
    void aLiveKeyStopsStartupWithoutEchoingIt() {
        runner.withPropertyValues("app.stripe.api-key=sk_live_SUPERSECRETVALUE123456").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure().toString()).doesNotContain("SUPERSECRET");
            assertThat(rootMessages(ctx.getStartupFailure())).contains("TEST key");
        });
    }

    @Test
    void forcingStripeWithoutAKeyStopsStartup() {
        runner.withPropertyValues("app.payments.provider=stripe").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void theKeyIsHiddenWhenTheSettingsArePrinted() {
        assertThat(new AppProperties.Stripe(TEST_KEY, "").toString()).doesNotContain("sk_test");
    }

    /** The name of the one and only active provider (fails if there are zero or several). */
    private static String only(org.springframework.context.ApplicationContext ctx) {
        return ctx.getBean(PaymentProvider.class).name();
    }

    private static String rootMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
