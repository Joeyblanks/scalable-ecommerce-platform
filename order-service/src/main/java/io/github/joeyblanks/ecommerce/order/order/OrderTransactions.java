package io.github.joeyblanks.ecommerce.order.order;

import io.github.joeyblanks.ecommerce.order.client.CartClient;
import io.github.joeyblanks.ecommerce.order.client.CatalogClient;
import io.github.joeyblanks.ecommerce.order.outbox.EventTypes;
import io.github.joeyblanks.ecommerce.order.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database transaction of the saga, one method each. A state change, its outbox event and any
 * stock release it requires are always written in ONE transaction: together or not at all.
 */
@Service
public class OrderTransactions {

    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final StockReleaseRepository releases;
    private final Clock clock;

    public OrderTransactions(OrderRepository orders, OutboxRepository outbox, StockReleaseRepository releases, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.releases = releases;
        this.clock = clock;
    }

    /** TX1: record the attempt before touching stock, so a crash at any later point leaves a trace. */
    @Transactional
    public void createAttempt(UUID orderId, UUID userId, String idempotencyKey) {
        orders.insertReserving(orderId, userId, idempotencyKey, clock.instant());
    }

    /**
     * TX2: RESERVING -> PENDING with the catalog's prices, plus OrderPlaced in the outbox.
     *
     * @return the order, or empty if the attempt is no longer RESERVING (the reconciler gave up on it
     *         and has already queued its stock release)
     */
    @Transactional
    public Optional<Order> completePlacement(UUID orderId, CatalogClient.Reservation reservation, List<CartClient.Item> cart) {
        Instant now = clock.instant();
        Map<UUID, BigDecimal> cartPrices = new HashMap<>();
        cart.forEach(i -> cartPrices.put(i.productId(), i.unitPrice()));
        List<Order.Item> items = reservation.items().stream().map(r -> {
            BigDecimal price = r.unitPrice().setScale(2, RoundingMode.HALF_UP);
            BigDecimal shown = cartPrices.get(r.productId());
            boolean changed = shown != null && shown.compareTo(price) != 0;
            return new Order.Item(r.productId(), r.sku(), r.name(), price, r.quantity(),
                    price.multiply(BigDecimal.valueOf(r.quantity())).setScale(2, RoundingMode.HALF_UP), changed);
        }).toList();
        BigDecimal total = items.stream().map(Order.Item::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (orders.markPending(orderId, total, items, now) == 0) {
            return Optional.empty();
        }
        Order order = orders.findById(orderId).orElseThrow();
        outbox.append(orderId, EventTypes.ORDER_PLACED, EventTypes.RK_ORDER_PLACED, OrderEvents.placed(order, now), now);
        return Optional.of(order);
    }

    /** RESERVING -> FAILED (frees the idempotency key); optionally queue a stock release in the same TX. */
    @Transactional
    public void failAttempt(UUID orderId, String reason, boolean releaseStock) {
        Instant now = clock.instant();
        orders.markFailed(orderId, reason, now);
        if (releaseStock) {
            releases.enqueue(orderId, now); // idempotent; harmless if the reconciler already queued it
        }
    }

    /**
     * PENDING -> CANCELLED, plus OrderCancelled and a stock release. Shared by customer cancel, payment
     * failure and payment timeout. {@code ownerOrNull} restricts it to the owner's order.
     *
     * @return the cancelled order, or empty if nothing was cancelled (not PENDING, not found, not theirs)
     */
    @Transactional
    public Optional<Order> cancel(UUID orderId, UUID ownerOrNull, String reason) {
        Instant now = clock.instant();
        if (orders.markCancelled(orderId, ownerOrNull, reason, now) == 0) {
            return Optional.empty();
        }
        Order order = orders.findById(orderId).orElseThrow();
        outbox.append(orderId, EventTypes.ORDER_CANCELLED, EventTypes.RK_ORDER_CANCELLED, OrderEvents.cancelled(order, reason, now), now);
        releases.enqueue(orderId, now);
        return Optional.of(order);
    }

    /** PENDING -> PAID plus OrderPaid. Must be called inside the payment handler's transaction. */
    @Transactional
    public Optional<Order> markPaid(UUID orderId, String paymentId) {
        Instant now = clock.instant();
        if (orders.markPaid(orderId, paymentId, now) == 0) {
            return Optional.empty();
        }
        Order order = orders.findById(orderId).orElseThrow();
        outbox.append(orderId, EventTypes.ORDER_PAID, EventTypes.RK_ORDER_PAID, OrderEvents.paid(order, paymentId, now), now);
        return Optional.of(order);
    }
}
