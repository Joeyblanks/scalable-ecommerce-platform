package io.github.joeyblanks.ecommerce.cart.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a cart, exactly as stored in Redis. {@code unitPrice}, {@code name} and {@code sku}
 * are a snapshot taken from the catalog when the line was last added to or changed: they are for
 * display only. The authoritative price is read from the catalog at checkout.
 */
public record CartItem(UUID productId, String sku, String name, BigDecimal unitPrice, int quantity,
                       Instant addedAt, Instant updatedAt) {

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
