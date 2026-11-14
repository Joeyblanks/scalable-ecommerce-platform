package io.github.joeyblanks.ecommerce.order.jobs;

import io.github.joeyblanks.ecommerce.order.config.AppProperties;
import io.github.joeyblanks.ecommerce.order.order.OrderRepository;
import io.github.joeyblanks.ecommerce.order.order.OrderStatus;
import io.github.joeyblanks.ecommerce.order.order.OrderTransactions;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Self-healing jobs. They use the same conditional transitions as the request path, so they can run
 * on several instances at once. Racing a slow request is safe because exactly one side's conditional
 * update succeeds AND side effects (outbox event, stock release) are written only by that side, in
 * the same transaction. Example: if the reconciler sees an attempt as RESERVING but TX2 commits first,
 * the reconciler's update matches 0 rows and it queues nothing; the order stays PENDING with its stock.
 */
@Component
public class OrderMaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(OrderMaintenanceJobs.class);

    private final OrderRepository orders;
    private final OrderTransactions tx;
    private final AppProperties.Orders props;
    private final Clock clock;

    public OrderMaintenanceJobs(OrderRepository orders, OrderTransactions tx, AppProperties.Orders props, Clock clock) {
        this.orders = orders;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    /** Attempts stuck in RESERVING (process crashed, database failed mid-way): fail them and release stock. */
    @Scheduled(fixedDelayString = "${app.jobs.reconcile-interval}")
    public void reconcileStuckReservations() {
        safely("reconcile", () -> {
            List<UUID> stuck = orders.findIdsByStatusCreatedBefore(OrderStatus.RESERVING,
                    clock.instant().minus(props.stuckReservationAfter()), 100);
            for (UUID id : stuck) {
                if (tx.failAttempt(id, "STUCK", true)) {
                    log.warn("Order attempt {} was stuck in RESERVING; marked FAILED and its stock release queued", id);
                } else {
                    log.info("Order attempt {} completed (or was failed) while being reconciled; left unchanged", id);
                }
            }
        });
    }

    /** PENDING orders never paid within the timeout: cancel them so their stock is not held forever. */
    @Scheduled(fixedDelayString = "${app.jobs.payment-timeout-interval}")
    public void cancelUnpaidOrders() {
        safely("payment timeout", () -> {
            List<UUID> unpaid = orders.findIdsByStatusCreatedBefore(OrderStatus.PENDING,
                    clock.instant().minus(props.paymentTimeout()), 100);
            for (UUID id : unpaid) {
                tx.cancel(id, null, "PAYMENT_TIMEOUT")
                        .ifPresent(o -> log.info("Order {} cancelled: not paid within {}", id, props.paymentTimeout()));
            }
        });
    }

    private static void safely(String job, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("{} job failed (will retry): {}", job, e.toString());
        }
    }
}
