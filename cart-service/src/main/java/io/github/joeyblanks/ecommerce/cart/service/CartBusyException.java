package io.github.joeyblanks.ecommerce.cart.service;

/** The same cart line kept changing under us (many simultaneous requests); the client may retry. */
public class CartBusyException extends RuntimeException {
    public CartBusyException() {
        super("Your cart is being changed by other requests right now; please try again");
    }
}
