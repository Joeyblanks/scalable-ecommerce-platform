package io.github.joeyblanks.ecommerce.cart.service;

import io.github.joeyblanks.ecommerce.cart.catalog.CatalogClient;
import io.github.joeyblanks.ecommerce.cart.catalog.CatalogProduct;
import io.github.joeyblanks.ecommerce.cart.config.CartProperties;
import io.github.joeyblanks.ecommerce.cart.dto.CartResponse;
import io.github.joeyblanks.ecommerce.cart.model.CartItem;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository.Stored;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository.WriteResult;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CartService {

    private final CartRepository carts;
    private final CatalogClient catalog;
    private final CartProperties properties;
    private final Clock clock;

    public CartService(CartRepository carts, CatalogClient catalog, CartProperties properties, Clock clock) {
        this.carts = carts;
        this.catalog = catalog;
        this.properties = properties;
        this.clock = clock;
    }

    /** Reads Redis only: a cart can be viewed even while the catalog is down. */
    public CartResponse view(UUID userId) {
        return CartResponse.of(carts.findAll(userId));
    }

    /** Adds {@code quantity} on top of whatever is already in the cart for this product. */
    public CartResponse addItem(UUID userId, UUID productId, int quantity) {
        checkQuantity(quantity);
        // Ask the catalog once. The compare-and-set retries below only touch Redis.
        CatalogProduct product = catalog.getProduct(productId);
        return writeLine(userId, productId, product, existing -> (existing == null ? 0 : existing.quantity()) + quantity);
    }

    /** Sets the quantity of a line that must already be in the cart. */
    public CartResponse setQuantity(UUID userId, UUID productId, int quantity) {
        checkQuantity(quantity);
        if (carts.find(userId, productId).isEmpty()) {
            throw new ItemNotInCartException(productId); // no need to bother the catalog
        }
        CatalogProduct product = catalog.getProduct(productId);
        return writeLine(userId, productId, product, existing -> {
            if (existing == null) {
                throw new ItemNotInCartException(productId); // removed while we were asking the catalog
            }
            return quantity;
        });
    }

    public CartResponse removeItem(UUID userId, UUID productId) {
        if (!carts.remove(userId, productId)) {
            throw new ItemNotInCartException(productId);
        }
        return view(userId);
    }

    public void clear(UUID userId) {
        carts.clear(userId);
    }

    private interface NewQuantity {
        int from(CartItem existing);
    }

    /**
     * Optimistic read-modify-write: read the line, compute the new line, and write it only if nobody
     * changed it in between (compare-and-set); otherwise read again and redo. The new quantity and the
     * stock check are recomputed from the freshly read line on every attempt.
     */
    private CartResponse writeLine(UUID userId, UUID productId, CatalogProduct product, NewQuantity newQuantity) {
        for (int attempt = 1; attempt <= properties.casMaxAttempts(); attempt++) {
            Stored current = carts.find(userId, productId).orElse(null);
            CartItem existing = current == null ? null : current.item();
            int total = newQuantity.from(existing);
            if (total > properties.maxQuantity()) {
                throw new CartLimitException("At most " + properties.maxQuantity() + " of one product fit in a cart ("
                        + (existing == null ? 0 : existing.quantity()) + " already in your cart)");
            }
            if (total > product.stockQuantity()) {
                throw new InsufficientStockException(product.stockQuantity(), total);
            }
            Instant now = clock.instant();
            CartItem updated = new CartItem(productId, product.sku(), product.name(),
                    product.price().setScale(2, RoundingMode.HALF_UP), total,
                    existing == null ? now : existing.addedAt(), now);

            WriteResult result = carts.writeIfUnchanged(userId, current, updated);
            switch (result) {
                case WRITTEN -> {
                    return view(userId);
                }
                case CART_FULL -> throw new CartLimitException(
                        "A cart can hold at most " + properties.maxLines() + " different products");
                case CHANGED_CONCURRENTLY -> { /* someone else changed this line: read it again and retry */ }
            }
        }
        throw new CartBusyException();
    }

    private void checkQuantity(int quantity) {
        if (quantity < 1 || quantity > properties.maxQuantity()) {
            throw new InvalidRequestException("quantity must be between 1 and " + properties.maxQuantity());
        }
    }
}
