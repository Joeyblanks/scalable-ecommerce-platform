package io.github.joeyblanks.ecommerce.payment.outbox;

/** Event names and routing keys, as defined in docs/events (the contracts). */
public final class EventTypes {

    public static final String ORDER_PLACED = "OrderPlaced";
    public static final String ORDER_CANCELLED = "OrderCancelled";
    public static final String PAYMENT_SUCCEEDED = "PaymentSucceeded";
    public static final String PAYMENT_FAILED = "PaymentFailed";

    public static final String RK_ORDER_PLACED = "order.placed";
    public static final String RK_ORDER_CANCELLED = "order.cancelled";
    public static final String RK_PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String RK_PAYMENT_FAILED = "payment.failed";

    public static final String PRODUCER = "payment-service";
    public static final int VERSION = 1;

    private EventTypes() {
    }
}
