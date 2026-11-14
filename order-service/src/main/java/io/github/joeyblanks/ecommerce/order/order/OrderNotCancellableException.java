package io.github.joeyblanks.ecommerce.order.order;

public class OrderNotCancellableException extends RuntimeException {
    public OrderNotCancellableException(OrderStatus status) {
        super("Only PENDING orders can be cancelled; this order is " + status);
    }
}
