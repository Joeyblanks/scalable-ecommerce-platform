package io.github.joeyblanks.ecommerce.order.jobs;

import io.github.joeyblanks.ecommerce.order.client.CatalogClient;
import io.github.joeyblanks.ecommerce.order.order.StockReleaseRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Gives reserved stock back to the catalog, retrying with backoff until the catalog confirms. */
@Component
public class StockReleaseWorker {

    private static final Logger log = LoggerFactory.getLogger(StockReleaseWorker.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final StockReleaseRepository releases;
    private final CatalogClient catalog;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public StockReleaseWorker(StockReleaseRepository releases, CatalogClient catalog, TransactionTemplate transaction, Clock clock) {
        this.releases = releases;
        this.catalog = catalog;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.stock-release-interval}")
    public void run() {
        try {
            releaseDue();
        } catch (RuntimeException e) {
            log.warn("Stock release run failed (will retry): {}", e.toString());
        }
    }

    public void releaseDue() {
        transaction.executeWithoutResult(status -> {
            for (StockReleaseRepository.Due due : releases.lockDue(clock.instant(), 20)) {
                try {
                    catalog.release(due.orderId()); // idempotent at the catalog
                    releases.complete(due.orderId(), clock.instant());
                    log.info("Stock for order {} released", due.orderId());
                } catch (RuntimeException e) {
                    Duration wait = backoff(due.attempts());
                    releases.retryLater(due.orderId(), e.toString(), clock.instant().plus(wait));
                    log.warn("Releasing stock for order {} failed (attempt {}), retrying in {}: {}",
                            due.orderId(), due.attempts() + 1, wait, e.toString());
                }
            }
        });
    }

    static Duration backoff(int attempts) {
        Duration d = Duration.ofSeconds(1L << Math.min(attempts, 9));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}
