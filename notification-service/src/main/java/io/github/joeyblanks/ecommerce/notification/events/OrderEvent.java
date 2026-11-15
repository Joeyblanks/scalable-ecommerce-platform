package io.github.joeyblanks.ecommerce.notification.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The three order events that produce an email, reduced to what the emails need. */
public sealed interface OrderEvent {

    UUID eventId();

    UUID orderId();

    UUID userId();

    record Item(String name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    record Placed(UUID eventId, UUID orderId, UUID userId, List<Item> items, BigDecimal totalAmount, Instant placedAt)
            implements OrderEvent {
    }

    record Paid(UUID eventId, UUID orderId, UUID userId, Instant paidAt) implements OrderEvent {
    }

    /** {@code reason} is the contract's code (CUSTOMER_REQUEST, PAYMENT_FAILED, PAYMENT_TIMEOUT, ...). */
    record Cancelled(UUID eventId, UUID orderId, UUID userId, String reason, Instant cancelledAt) implements OrderEvent {
    }
}
