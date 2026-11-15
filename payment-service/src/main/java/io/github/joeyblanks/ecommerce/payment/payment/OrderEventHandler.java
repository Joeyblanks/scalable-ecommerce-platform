package io.github.joeyblanks.ecommerce.payment.payment;

import io.github.joeyblanks.ecommerce.payment.config.AppProperties;
import io.github.joeyblanks.ecommerce.payment.outbox.EventTypes;
import io.github.joeyblanks.ecommerce.payment.outbox.ProcessedEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies OrderPlaced and OrderCancelled. Both only ever touch the one payments row of their order, with
 * conditional updates, so any order, repetition or concurrency of the two events ends in one correct state:
 * <ul>
 *   <li>OrderPlaced records the payment as CHARGING (a worker charges it later, outside any transaction);
 *       the UNIQUE order_id makes a second payment for the same order impossible;</li>
 *   <li>OrderCancelled voids, flags or refunds depending on how far the payment got, and leaves a
 *       tombstone when it arrives before OrderPlaced.</li>
 * </ul>
 * The event id is recorded in the same transaction as the effect (deduplication).
 */
@Service
public class OrderEventHandler {

    public enum Outcome { APPLIED, DUPLICATE }

    private static final Logger log = LoggerFactory.getLogger(OrderEventHandler.class);

    private final JsonMapper json;
    private final ProcessedEventRepository processed;
    private final PaymentRepository payments;
    private final AppProperties.Payments config;
    private final Clock clock;

    public OrderEventHandler(JsonMapper json, ProcessedEventRepository processed, PaymentRepository payments,
                             AppProperties.Payments config, Clock clock) {
        this.json = json;
        this.processed = processed;
        this.payments = payments;
        this.config = config;
        this.clock = clock;
    }

    public sealed interface OrderEvent {
        UUID eventId();

        UUID orderId();
    }

    public record Placed(UUID eventId, UUID orderId, UUID userId, BigDecimal totalAmount) implements OrderEvent {
    }

    public record Cancelled(UUID eventId, UUID orderId) implements OrderEvent {
    }

    /** A malformed message cannot be fixed by retrying: it is dead-lettered at once. */
    public OrderEvent parse(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode data = root.path("data");
            UUID eventId = UUID.fromString(root.path("eventId").asString());
            UUID orderId = UUID.fromString(data.path("orderId").asString());
            String type = root.path("eventType").asString();
            return switch (type) {
                case EventTypes.ORDER_PLACED -> {
                    BigDecimal total = new BigDecimal(data.path("totalAmount").asString());
                    if (total.signum() < 0 || total.scale() > 2 || total.precision() - total.scale() > 10) {
                        throw new IllegalArgumentException("invalid totalAmount " + total);
                    }
                    yield new Placed(eventId, orderId, UUID.fromString(data.path("userId").asString()), total);
                }
                case EventTypes.ORDER_CANCELLED -> new Cancelled(eventId, orderId);
                default -> throw new IllegalArgumentException("Unexpected event type: " + type);
            };
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            throw new AmqpRejectAndDontRequeueException("Malformed order event: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Outcome handle(OrderEvent event) {
        Instant now = clock.instant();
        String type = event instanceof Placed ? EventTypes.ORDER_PLACED : EventTypes.ORDER_CANCELLED;
        if (!processed.markProcessed(event.eventId(), type, now)) {
            log.info("Duplicate {} {} ignored", type, event.eventId());
            return Outcome.DUPLICATE;
        }
        switch (event) {
            case Placed placed -> placed(placed, now);
            case Cancelled cancelled -> cancelled(cancelled, now);
        }
        return Outcome.APPLIED;
    }

    private void placed(Placed event, Instant now) {
        if (payments.insertCharging(UUID.randomUUID(), event.orderId(), event.userId(), event.totalAmount(),
                config.currency(), now)) {
            log.info("Payment recorded for order {} ({})", event.orderId(), event.totalAmount());
        } else if (payments.attachToTombstone(event.orderId(), event.userId(), event.totalAmount(), now)) {
            log.info("OrderPlaced for order {} arrived after its cancellation: not charging", event.orderId());
        } else {
            log.info("OrderPlaced for order {}: payment already exists, nothing to do", event.orderId());
        }
    }

    private void cancelled(Cancelled event, Instant now) {
        UUID orderId = event.orderId();
        // Nothing has been recorded for this order yet: leave a tombstone so OrderPlaced will not charge.
        // (If a concurrent OrderPlaced inserted the row first, this returns false and we handle the row below.)
        if (payments.insertTombstone(UUID.randomUUID(), orderId, config.currency(), now)) {
            log.info("OrderCancelled for order {} arrived before OrderPlaced: remembered", orderId);
            return;
        }
        // The row exists. Try, in order, from "no provider call yet" to "already charged". Each statement is
        // conditional on the state it expects and the state only moves forward, so whichever applies wins
        // and, if a worker moves the row between two statements, the next statement catches the new state.
        if (payments.voidUnstarted(orderId, now)) {
            log.info("Payment for order {} voided before any charge", orderId);
        } else if (payments.requestCancelWhileCharging(orderId, now)) {
            log.info("Payment for order {} is being charged: refund or void when it finishes", orderId);
        } else if (payments.startRefund(orderId, now)) {
            log.info("Payment for order {} already charged: refund started", orderId);
        } else {
            log.info("OrderCancelled for order {}: nothing to undo", orderId);
        }
    }
}
