package io.github.joeyblanks.ecommerce.notification.events;

/** Event names and routing keys, as defined in docs/events (the contracts). */
public final class EventTypes {

    public static final String ORDER_PLACED = "OrderPlaced";
    public static final String ORDER_PAID = "OrderPaid";
    public static final String ORDER_CANCELLED = "OrderCancelled";

    public static final String RK_ORDER_PLACED = "order.placed";
    public static final String RK_ORDER_PAID = "order.paid";
    public static final String RK_ORDER_CANCELLED = "order.cancelled";

    private EventTypes() {
    }
}
