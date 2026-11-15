package io.github.joeyblanks.ecommerce.notification.notification;

import io.github.joeyblanks.ecommerce.notification.events.OrderEvent;

public enum NotificationKind {
    ORDER_PLACED, ORDER_PAID, ORDER_CANCELLED;

    public static NotificationKind of(OrderEvent event) {
        return switch (event) {
            case OrderEvent.Placed p -> ORDER_PLACED;
            case OrderEvent.Paid p -> ORDER_PAID;
            case OrderEvent.Cancelled c -> ORDER_CANCELLED;
        };
    }
}
