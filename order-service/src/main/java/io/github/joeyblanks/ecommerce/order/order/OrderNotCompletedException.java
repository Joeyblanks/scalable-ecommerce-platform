package io.github.joeyblanks.ecommerce.order.order;

/** The attempt was given up after stock was reserved (storage failure or the reconciler); stock is released. */
public class OrderNotCompletedException extends RuntimeException {
    public OrderNotCompletedException(Throwable cause) {
        super("The order could not be completed. Nothing was ordered and any reserved stock is being returned; please try again.", cause);
    }
}
