package io.github.joeyblanks.ecommerce.order.outbox;

import io.github.joeyblanks.ecommerce.order.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves committed events from the outbox to RabbitMQ. At-least-once: if the process dies after the
 * broker confirmed but before the row is marked, the event is published again with the SAME event id,
 * and consumers drop the duplicate. Rows are locked with SKIP LOCKED, so several instances can run.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final TransactionTemplate transaction;
    private final AppProperties.Jobs jobs;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, TransactionTemplate transaction,
                       AppProperties.Jobs jobs, Clock clock) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.transaction = transaction;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.outbox-relay-interval}")
    public void run() {
        try {
            publishDueBatch();
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed (will retry): {}", e.toString()); // e.g. database unavailable
        }
    }

    /** @return how many events were published in this batch */
    public int publishDueBatch() {
        Integer published = transaction.execute(status -> {
            List<OutboxRepository.Row> due = outbox.lockDueBatch(clock.instant(), jobs.outboxBatchSize());
            int count = 0;
            for (OutboxRepository.Row row : due) {
                try {
                    publisher.publish(row);
                    outbox.markPublished(row.id(), clock.instant());
                    count++;
                } catch (Exception e) {
                    Instant next = clock.instant().plus(backoff(row.attempts()));
                    outbox.markFailed(row.id(), e.toString(), next);
                    log.warn("Publishing {} {} failed (attempt {}), retrying at {}: {}",
                            row.eventType(), row.id(), row.attempts() + 1, next, e.toString());
                    break; // keep the order of events: do not publish later ones past a failed one
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    static Duration backoff(int attempts) {
        long seconds = 1L << Math.min(attempts, 5); // 1, 2, 4, 8, 16, 32 -> capped below
        Duration d = Duration.ofSeconds(seconds);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}
