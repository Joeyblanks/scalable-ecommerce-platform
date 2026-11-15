package io.github.joeyblanks.ecommerce.notification.jobs;

import io.github.joeyblanks.ecommerce.notification.config.AppProperties;
import io.github.joeyblanks.ecommerce.notification.notification.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes SENT notifications after the retention period (default 30 days, longer than any redelivery or
 * dead-letter replay, because the row is also what prevents a second email for the same event). PENDING and
 * FAILED notifications are never deleted: they are work in progress or a record for a person to look at.
 */
@Component
public class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);
    private static final int BATCH = 1000;

    private final NotificationRepository notifications;
    private final AppProperties.Retention retention;
    private final Clock clock;

    public RetentionCleanupJob(NotificationRepository notifications, AppProperties.Retention retention, Clock clock) {
        this.notifications = notifications;
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
        Instant cutoff = clock.instant().minus(retention.sentNotifications());
        int total = 0;
        int deleted;
        IntSupplier batch = () -> notifications.deleteSentBefore(cutoff, BATCH);
        do {
            deleted = batch.getAsInt();
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("Retention cleanup removed {} sent notifications", total);
        }
    }
}
