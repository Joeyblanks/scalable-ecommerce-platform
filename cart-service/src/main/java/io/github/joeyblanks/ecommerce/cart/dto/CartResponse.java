package io.github.joeyblanks.ecommerce.cart.dto;

import io.github.joeyblanks.ecommerce.cart.model.CartItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The cart as shown to the user. Prices are the snapshot taken when each line was last added or
 * changed ("as of" {@code updatedAt}); they are not a commitment, and checkout re-prices them.
 */
public record CartResponse(List<Item> items, int itemCount, int totalQuantity, BigDecimal total) {

    public record Item(UUID productId, String sku, String name, BigDecimal unitPrice, int quantity,
                       BigDecimal lineTotal, Instant addedAt, Instant updatedAt) {
    }

    public static CartResponse of(List<CartItem> cartItems) {
        List<Item> items = cartItems.stream()
                .sorted(Comparator.comparing(CartItem::addedAt).thenComparing(CartItem::productId))
                .map(i -> new Item(i.productId(), i.sku(), i.name(), i.unitPrice(), i.quantity(),
                        i.lineTotal(), i.addedAt(), i.updatedAt()))
                .toList();
        int quantity = items.stream().mapToInt(Item::quantity).sum();
        BigDecimal total = items.stream().map(Item::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return new CartResponse(items, items.size(), quantity, total);
    }
}
