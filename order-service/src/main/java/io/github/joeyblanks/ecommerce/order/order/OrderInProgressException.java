package io.github.joeyblanks.ecommerce.order.order;

public class OrderInProgressException extends RuntimeException {
    public OrderInProgressException() {
        super("An order with this Idempotency-Key is still being placed; retry shortly");
    }
}
