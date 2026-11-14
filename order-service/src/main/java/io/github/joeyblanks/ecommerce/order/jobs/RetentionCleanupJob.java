package io.github.joeyblanks.ecommerce.order.jobs;

import io.github.joeyblanks.ecommerce.order.config.AppProperties;
import io.github.joeyblanks.ecommerce.order.order.StockReleaseRepository;
import io.github.joeyblanks.ecommerce.order.outbox.OutboxRepository;
import io.github.joeyblanks.ecommerce.order.outbox.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the bookkeeping tables small (retention documented in ADR 007):
 * <ul>
 *   <li>published outbox events: kept 7 days, for troubleshooting. Unpublished events are NEVER deleted.</li>
 *   <li>processed event ids: kept 30 days. This must be longer than any redelivery or dead-letter replay;
 *       an event replayed after its id was deleted is still harmless, because every change is
 *       conditional on the order's current state.</li>
 *   <li>completed stock releases: kept 30 days. Pending ones are never deleted.</li>
 * </ul>
 * Deletes in small batches so it never holds long locks.
 */
@Component
public class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);
    private static final int BATCH = 1000;

    private final OutboxRepository outbox;
    private final ProcessedEventRepository processed;
    private final StockReleaseRepository releases;
    private final AppProperties.Retention retention;
    private final Clock clock;

    public RetentionCleanupJob(OutboxRepository outbox, ProcessedEventRepository processed, StockReleaseRepository releases,
                               AppProperties.Retention retention, Clock clock) {
        this.outbox = outbox;
        this.processed = processed;
        this.releases = releases;
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
        int releasesDeleted = deleteAll(() -> releases.deleteCompletedBefore(now.minus(retention.completedStockReleases()), BATCH));
        if (outboxDeleted + processedDeleted + releasesDeleted > 0) {
            log.info("Retention cleanup removed {} published outbox events, {} processed event ids, {} completed stock releases",
                    outboxDeleted, processedDeleted, releasesDeleted);
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
