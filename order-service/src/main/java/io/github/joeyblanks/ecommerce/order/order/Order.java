package io.github.joeyblanks.ecommerce.order.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Order(UUID id, UUID userId, OrderStatus status, String cancelReason, String failureReason,
                    BigDecimal totalAmount, String paymentId, Instant createdAt, Instant updatedAt,
                    Instant paidAt, Instant cancelledAt, List<Item> items) {

    public record Item(UUID productId, String sku, String name, BigDecimal unitPrice, int quantity,
                       BigDecimal lineTotal, boolean priceChanged) {
    }

    public Order withItems(List<Item> items) {
        return new Order(id, userId, status, cancelReason, failureReason, totalAmount, paymentId, createdAt,
                updatedAt, paidAt, cancelledAt, items);
    }
}
