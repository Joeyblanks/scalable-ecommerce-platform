package io.github.joeyblanks.ecommerce.notification.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.notification.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private final NotificationWorker worker = new NotificationWorker(null, null, null, null, null,
            new AppProperties.Notifications(Duration.ofMinutes(2), Duration.ofSeconds(1), Duration.ofMinutes(1), 10,
                    Duration.ofHours(1), 10, "a@b.c"), Clock.systemUTC());

    @Test
    void doublesFromTheInitialDelayAndStopsAtTheCap() {
        assertThat(worker.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(worker.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(worker.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(worker.backoff(7)).isEqualTo(Duration.ofSeconds(60));
        assertThat(worker.backoff(1000)).isEqualTo(Duration.ofMinutes(1));
        assertThat(worker.backoff(0)).isEqualTo(Duration.ofSeconds(1));
    }
}
