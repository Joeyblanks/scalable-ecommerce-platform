package io.github.joeyblanks.ecommerce.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxBackoffTest {

    @Test
    void outboxBackoffDoublesAndIsCappedAt30Seconds() {
        assertThat(OutboxRelay.backoff(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxRelay.backoff(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(OutboxRelay.backoff(1000)).isEqualTo(Duration.ofSeconds(30));
    }
}
