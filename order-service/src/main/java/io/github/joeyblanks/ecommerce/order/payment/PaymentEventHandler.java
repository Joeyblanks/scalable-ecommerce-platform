package io.github.joeyblanks.ecommerce.order.payment;

import io.github.joeyblanks.ecommerce.order.order.OrderRepository;
import io.github.joeyblanks.ecommerce.order.order.OrderTransactions;
import io.github.joeyblanks.ecommerce.order.outbox.EventTypes;
import io.github.joeyblanks.ecommerce.order.outbox.ProcessedEventRepository;
import java.time.Clock;
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
 * Applies PaymentSucceeded / PaymentFailed. Idempotent twice over:
 * <ol>
 *   <li>the event id is recorded in processed_events in the SAME transaction as the effect, so a
 *       redelivered event is skipped (and an effect that rolls back is retried);</li>
 *   <li>every change is conditional on the order still being PENDING, so even a different event with
 *       the same meaning cannot apply twice.</li>
 * </ol>
 * A malformed message is dead-lettered at once; a message for an unknown order is retried a few times
 * (in case of a transient problem) and then dead-lettered.
 */
@Service
public class PaymentEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    private final JsonMapper json;
    private final ProcessedEventRepository processed;
    private final OrderRepository orders;
    private final OrderTransactions tx;
    private final Clock clock;

    public PaymentEventHandler(JsonMapper json, ProcessedEventRepository processed, OrderRepository orders,
                               OrderTransactions tx, Clock clock) {
        this.json = json;
        this.processed = processed;
        this.orders = orders;
        this.tx = tx;
        this.clock = clock;
    }

    public record PaymentEvent(UUID eventId, String eventType, UUID orderId, String paymentId, String reason) {
    }

    public PaymentEvent parse(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode data = root.path("data");
            UUID eventId = UUID.fromString(root.path("eventId").asString());
            String type = root.path("eventType").asString();
            UUID orderId = UUID.fromString(data.path("orderId").asString());
            if (!EventTypes.PAYMENT_SUCCEEDED.equals(type) && !EventTypes.PAYMENT_FAILED.equals(type)) {
                throw new AmqpRejectAndDontRequeueException("Unexpected event type: " + type);
            }
            return new PaymentEvent(eventId, type, orderId, data.path("paymentId").asString(null),
                    data.path("reason").asString(null));
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            // Retrying cannot fix a malformed message: dead-letter it immediately.
            throw new AmqpRejectAndDontRequeueException("Malformed payment event: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void handle(PaymentEvent event) {
        if (!processed.markProcessed(event.eventId(), event.eventType(), clock.instant())) {
            log.info("Duplicate {} {} ignored", event.eventType(), event.eventId());
            return;
        }
        boolean applied = switch (event.eventType()) {
            case EventTypes.PAYMENT_SUCCEEDED -> tx.markPaid(event.orderId(), event.paymentId()).isPresent();
            case EventTypes.PAYMENT_FAILED -> tx.cancel(event.orderId(), null, "PAYMENT_FAILED").isPresent();
            default -> throw new AmqpRejectAndDontRequeueException("Unexpected event type: " + event.eventType());
        };
        if (applied) {
            log.info("{} applied to order {}", event.eventType(), event.orderId());
            return;
        }
        var order = orders.findById(event.orderId());
        if (order.isEmpty()) {
            // rolls back the processed_events row, so retries see it again; then dead-lettered
            throw new IllegalStateException("Payment event " + event.eventId() + " for unknown order " + event.orderId());
        }
        // Already PAID / CANCELLED: nothing to do. A payment that succeeded for an order that was cancelled
        // meanwhile is refunded by payment-service when it sees OrderCancelled (event contract).
        log.warn("{} for order {} ignored: order is {}", event.eventType(), event.orderId(), order.get().status());
    }
}
