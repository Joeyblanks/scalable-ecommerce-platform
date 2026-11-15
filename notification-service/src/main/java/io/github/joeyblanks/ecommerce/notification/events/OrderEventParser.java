package io.github.joeyblanks.ecommerce.notification.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the event envelope of docs/events. Unknown fields are ignored (additive changes are allowed). */
@Component
public class OrderEventParser {

    private final JsonMapper json;

    public OrderEventParser(JsonMapper json) {
        this.json = json;
    }

    /** A malformed message cannot be fixed by retrying: it is dead-lettered at once. */
    public OrderEvent parse(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode data = root.path("data");
            UUID eventId = UUID.fromString(root.path("eventId").asString());
            UUID orderId = UUID.fromString(data.path("orderId").asString());
            UUID userId = UUID.fromString(data.path("userId").asString());
            String type = root.path("eventType").asString();
            return switch (type) {
                case EventTypes.ORDER_PLACED -> new OrderEvent.Placed(eventId, orderId, userId, items(data.path("items")),
                        money(data.path("totalAmount").asString()), Instant.parse(data.path("placedAt").asString()));
                case EventTypes.ORDER_PAID -> new OrderEvent.Paid(eventId, orderId, userId,
                        Instant.parse(data.path("paidAt").asString()));
                case EventTypes.ORDER_CANCELLED -> new OrderEvent.Cancelled(eventId, orderId, userId,
                        required(data.path("reason").asString()), Instant.parse(data.path("cancelledAt").asString()));
                default -> throw new IllegalArgumentException("Unexpected event type: " + type);
            };
        } catch (JacksonException | IllegalArgumentException | NullPointerException | java.time.DateTimeException e) {
            throw new AmqpRejectAndDontRequeueException("Malformed order event: " + e.getMessage(), e);
        }
    }

    private static List<OrderEvent.Item> items(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("items missing");
        }
        List<OrderEvent.Item> items = new ArrayList<>();
        for (JsonNode n : node) {
            int quantity = n.path("quantity").asInt(0);
            if (quantity <= 0) {
                throw new IllegalArgumentException("invalid quantity");
            }
            items.add(new OrderEvent.Item(required(n.path("name").asString()), quantity,
                    money(n.path("unitPrice").asString()), money(n.path("lineTotal").asString())));
        }
        return items;
    }

    private static BigDecimal money(String value) {
        BigDecimal amount = new BigDecimal(value);
        if (amount.signum() < 0 || amount.scale() > 2 || amount.precision() - amount.scale() > 10) {
            throw new IllegalArgumentException("invalid amount " + value);
        }
        return amount;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing field");
        }
        return value;
    }
}
