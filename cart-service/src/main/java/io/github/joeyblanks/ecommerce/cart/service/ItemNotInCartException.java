package io.github.joeyblanks.ecommerce.cart.service;

import java.util.UUID;

public class ItemNotInCartException extends RuntimeException {
    public ItemNotInCartException(UUID productId) {
        super("Product " + productId + " is not in your cart");
    }
}
