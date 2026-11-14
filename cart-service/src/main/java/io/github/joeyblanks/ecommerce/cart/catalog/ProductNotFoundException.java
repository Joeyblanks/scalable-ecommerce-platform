package io.github.joeyblanks.ecommerce.cart.catalog;

import java.util.UUID;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(UUID productId) {
        super("Product does not exist: " + productId);
    }
}
