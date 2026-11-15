package io.github.joeyblanks.ecommerce.payment.jobs;

import io.github.joeyblanks.ecommerce.payment.config.AppProperties;
import io.github.joeyblanks.ecommerce.payment.outbox.OutboxRepository;
import io.github.joeyblanks.ecommerce.payment.outbox.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the bookkeeping tables small (retention documented in ADR 009):
 * <ul>
 *   <li>published outbox events: kept 7 days, for troubleshooting. Unpublished events are NEVER deleted.</li>
 *   <li>processed event ids: kept 30 days, longer than any redelivery or dead-letter replay. An event
 *       replayed after its id was deleted is still harmless: OrderPlaced hits the UNIQUE order_id and
 *       OrderCancelled is a conditional state change.</li>
 * </ul>
 * Payment rows themselves are the financial record and are not deleted here.
 */
@Component
public class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);
    private static final int BATCH = 1000;

    private final OutboxRepository outbox;
    private final ProcessedEventRepository processed;
    private final AppProperties.Retention retention;
    private final Clock clock;

    public RetentionCleanupJob(OutboxRepository outbox, ProcessedEventRepository processed,
                               AppProperties.Retention retention, Clock clock) {
        this.outbox = outbox;
        this.processed = processed;
        this.retention = retention;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.cleanup-interval}", initialDelayString = "${app.jobs.cleanup-interval}")
    public void run() {
        try {
            cleanUp();
        } catch (RuntimeException e) {
            log.warn("Retention cleanup failed (will retry): {}", e.toString());
        }
    }

    public void cleanUp() {
        Instant now = clock.instant();
        int outboxDeleted = deleteAll(() -> outbox.deletePublishedBefore(now.minus(retention.publishedOutboxEvents()), BATCH));
        int processedDeleted = deleteAll(() -> processed.deleteProcessedBefore(now.minus(retention.processedEvents()), BATCH));
        if (outboxDeleted + processedDeleted > 0) {
            log.info("Retention cleanup removed {} published outbox events and {} processed event ids", outboxDeleted, processedDeleted);
        }
    }

    private static int deleteAll(IntSupplier batch) {
        int total = 0;
        int deleted;
        do {
            deleted = batch.getAsInt();
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
