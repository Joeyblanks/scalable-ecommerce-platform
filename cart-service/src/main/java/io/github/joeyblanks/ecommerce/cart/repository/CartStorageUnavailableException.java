package io.github.joeyblanks.ecommerce.cart.repository;

public class CartStorageUnavailableException extends RuntimeException {
    public CartStorageUnavailableException(Throwable cause) {
        super("Cart storage is temporarily unavailable", cause);
    }
}
