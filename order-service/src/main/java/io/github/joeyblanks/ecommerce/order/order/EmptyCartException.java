package io.github.joeyblanks.ecommerce.order.order;

public class EmptyCartException extends RuntimeException {
    public EmptyCartException() {
        super("The cart is empty; there is nothing to order");
    }
}
