package io.github.joeyblanks.ecommerce.notification.notification;

import io.github.joeyblanks.ecommerce.notification.events.OrderEvent;
import io.github.joeyblanks.ecommerce.notification.events.OrderEventParser;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns an order event into a pending notification. It sends nothing: a worker does that, outside the
 * message handler, so a slow mail server or user-service never blocks the queue. Recording is a single
 * INSERT with a UNIQUE event id, so redeliveries and duplicates of one event record exactly one notification.
 */
@Service
public class NotificationRecorder {

    public enum Outcome { RECORDED, DUPLICATE }

    private static final Logger log = LoggerFactory.getLogger(NotificationRecorder.class);

    private final OrderEventParser parser;
    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationRecorder(OrderEventParser parser, NotificationRepository notifications, Clock clock) {
        this.parser = parser;
        this.notifications = notifications;
        this.clock = clock;
    }

    public Outcome record(byte[] messageBody) {
        OrderEvent event = parser.parse(messageBody);
        NotificationKind kind = NotificationKind.of(event);
        // The original message is kept as the payload: it has the userId and order details, never an address.
        boolean created = notifications.insertIfNew(UUID.randomUUID(), event.eventId(), kind, event.userId(),
                event.orderId(), new String(messageBody, java.nio.charset.StandardCharsets.UTF_8), clock.instant());
        if (!created) {
            log.info("Duplicate {} event {} ignored", kind, event.eventId());
            return Outcome.DUPLICATE;
        }
        log.info("Notification {} recorded for order {}", kind, event.orderId());
        return Outcome.RECORDED;
    }
}
