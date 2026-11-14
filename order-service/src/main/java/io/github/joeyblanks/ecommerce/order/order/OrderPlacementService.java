package io.github.joeyblanks.ecommerce.order.order;

import io.github.joeyblanks.ecommerce.order.client.CartClient;
import io.github.joeyblanks.ecommerce.order.client.CatalogClient;
import io.github.joeyblanks.ecommerce.order.client.DependencyUnavailableException;
import io.github.joeyblanks.ecommerce.order.client.OutOfStockException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Places an order from the caller's cart. Deliberately NOT one big transaction: remote calls must never
 * run inside a database transaction. Instead each step is durable before the next (see ADR 007):
 *
 * <ol>
 *   <li>read the cart (nothing written yet)</li>
 *   <li>TX1: insert the order as RESERVING (the order id is also the reservation id)</li>
 *   <li>reserve stock at the catalog, getting the authoritative prices</li>
 *   <li>TX2: RESERVING -> PENDING + OrderPlaced in the outbox</li>
 * </ol>
 * Any failure after TX1 ends in FAILED, with the stock release queued whenever the catalog MIGHT
 * have reserved, but only if the attempt is still RESERVING at that moment: an attempt that TX2
 * already made PENDING is a real order and is never failed or released (see
 * {@link OrderTransactions#failAttempt}). The cart is emptied later by cart-service when it receives
 * OrderPlaced.
 */
@Service
public class OrderPlacementService {

    /** {@code created} is false when an earlier order with the same Idempotency-Key is returned. */
    public record Placement(boolean created, Order order) {
    }

    private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);

    private final OrderRepository orders;
    private final OrderTransactions tx;
    private final CartClient cart;
    private final CatalogClient catalog;

    public OrderPlacementService(OrderRepository orders, OrderTransactions tx, CartClient cart, CatalogClient catalog) {
        this.orders = orders;
        this.tx = tx;
        this.cart = cart;
        this.catalog = catalog;
    }

    public Placement place(UUID userId, String userToken, String idempotencyKey) {
        Optional<Placement> repeat = existing(userId, idempotencyKey);
        if (repeat.isPresent()) {
            return repeat.get();
        }

        CartClient.Cart current = cart.getCart(userToken); // cart down -> 503, nothing written
        if (current.items().isEmpty()) {
            throw new EmptyCartException();
        }

        UUID orderId = UUID.randomUUID();
        try {
            tx.createAttempt(orderId, userId, idempotencyKey);
        } catch (DuplicateKeyException e) {
            // the same key arrived twice at the same moment: the other request owns it
            return existing(userId, idempotencyKey).orElseThrow(OrderInProgressException::new);
        }

        CatalogClient.Reservation reservation;
        try {
            reservation = catalog.reserve(orderId, current.items().stream()
                    .map(i -> new CatalogClient.Line(i.productId(), i.quantity())).toList());
        } catch (OutOfStockException e) {
            tx.failAttempt(orderId, "OUT_OF_STOCK", false); // definitive "no": nothing was reserved
            throw e;
        } catch (RuntimeException e) {
            // Timeout or error: the catalog MAY have reserved. Release that id (idempotent, and a late
            // reserve is refused by the catalog's tombstone), so stock can never leak.
            safeFail(orderId, "CATALOG_UNAVAILABLE");
            throw e instanceof DependencyUnavailableException d ? d
                    : new DependencyUnavailableException("product-catalog-service", "Product catalog is unavailable", e);
        }

        try {
            return tx.completePlacement(orderId, reservation, current.items())
                    .map(order -> new Placement(true, order))
                    .orElseThrow(() -> new OrderNotCompletedException(null)); // the reconciler took over
        } catch (DataAccessException e) {
            // We do NOT know whether TX2 committed: the error may have come after the commit (an
            // ambiguous commit). safeFail only acts if the attempt is still RESERVING, so:
            //  - TX2 really failed  -> FAILED, stock release queued;
            //  - TX2 did commit     -> the order is PENDING and is left alone (no release);
            //  - database is down   -> nothing happens now; the reconciler handles the RESERVING row.
            // Either way the client can retry with the same Idempotency-Key and gets the true outcome.
            safeFail(orderId, "SAVE_FAILED");
            throw new OrderNotCompletedException(e);
        }
    }

    private Optional<Placement> existing(UUID userId, String idempotencyKey) {
        return orders.findByUserAndKey(userId, idempotencyKey).map(order -> {
            if (order.status() == OrderStatus.RESERVING) {
                throw new OrderInProgressException();
            }
            return new Placement(false, order);
        });
    }

    private void safeFail(UUID orderId, String reason) {
        try {
            tx.failAttempt(orderId, reason, true);
        } catch (RuntimeException e) {
            log.warn("Could not mark order {} as FAILED ({}); the reconciler will clean it up", orderId, e.toString());
        }
    }

    // ---- reads and cancel (thin, kept here so the controller has one entry point) ----

    public Order get(UUID orderId, UUID userId) {
        return orders.findVisible(orderId, userId).orElseThrow(OrderNotFoundException::new);
    }

    public List<Order> list(UUID userId, int page, int size) {
        return orders.listVisible(userId, page, size);
    }

    public long count(UUID userId) {
        return orders.countVisible(userId);
    }

    public Order cancelByCustomer(UUID orderId, UUID userId) {
        return tx.cancel(orderId, userId, "CUSTOMER_REQUEST").orElseGet(() -> {
            Order order = get(orderId, userId); // 404 if missing, not theirs, or hidden
            throw new OrderNotCancellableException(order.status());
        });
    }
}
