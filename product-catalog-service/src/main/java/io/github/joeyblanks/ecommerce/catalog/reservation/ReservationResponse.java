package io.github.joeyblanks.ecommerce.catalog.reservation;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The reservation as stored. For RESERVED, {@code items} carry the price, sku and name read in the
 * same transaction as the stock decrement: this is the authoritative price for the order.
 */
public record ReservationResponse(UUID reservationId, String status, List<Item> items, BigDecimal total) {

    public record Item(UUID productId, String sku, String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
    }

    public static ReservationResponse of(UUID id, String status, List<Item> items) {
        BigDecimal total = items.stream().map(Item::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return new ReservationResponse(id, status, items, total);
    }
}
