package io.github.joeyblanks.ecommerce.order.order;

import java.util.Set;

public enum OrderStatus {
    /** Attempt in progress: order row written, stock not yet confirmed. Internal, never shown. */
    RESERVING,
    /** Stock reserved, OrderPlaced published (via outbox), waiting for payment. */
    PENDING,
    PAID,
    CANCELLED,
    /** The attempt never reserved stock (out of stock, catalog down, crash). Kept for audit, hidden. */
    FAILED;

    /** What a customer can see in "my orders". */
    public static final Set<OrderStatus> VISIBLE = Set.of(PENDING, PAID, CANCELLED);
}
