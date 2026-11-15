package io.github.joeyblanks.ecommerce.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.payment.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private final PaymentWorkers workers = new PaymentWorkers(null, null, null,
            new AppProperties.Payments("EUR", Duration.ofMinutes(2), Duration.ofSeconds(1), Duration.ofSeconds(30),
                    Duration.ofHours(2), 10), Clock.systemUTC());

    @Test
    void retryDelayDoublesAndIsCapped() {
        assertThat(workers.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(workers.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(workers.backoff(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(workers.backoff(6)).isEqualTo(Duration.ofSeconds(30));
        assertThat(workers.backoff(10_000)).isEqualTo(Duration.ofSeconds(30));
    }
}
