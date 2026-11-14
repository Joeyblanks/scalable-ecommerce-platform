package io.github.joeyblanks.ecommerce.order.web;

import io.github.joeyblanks.ecommerce.order.order.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order as the customer sees it. Prices are those the catalog charged at reservation time;
 * {@code priceChanged} flags lines where that differs from what the cart showed.
 */
public record OrderResponse(UUID id, String status, String cancelReason, List<Item> items, BigDecimal totalAmount,
                            Instant createdAt, Instant updatedAt, Instant paidAt, Instant cancelledAt) {

    public record Item(UUID productId, String sku, String name, BigDecimal unitPrice, int quantity,
                       BigDecimal lineTotal, boolean priceChanged) {
    }

    public static OrderResponse from(Order o) {
        return new OrderResponse(o.id(), o.status().name(), o.cancelReason(),
                o.items().stream().map(i -> new Item(i.productId(), i.sku(), i.name(), i.unitPrice(), i.quantity(),
                        i.lineTotal(), i.priceChanged())).toList(),
                o.totalAmount(), o.createdAt(), o.updatedAt(), o.paidAt(), o.cancelledAt());
    }
}
