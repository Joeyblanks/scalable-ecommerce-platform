package io.github.joeyblanks.ecommerce.cart.service;

/** The cart would exceed a size limit (too many different products). */
public class CartLimitException extends RuntimeException {
    public CartLimitException(String message) {
        super(message);
    }
}
