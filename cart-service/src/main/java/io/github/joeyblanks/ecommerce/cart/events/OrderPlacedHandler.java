package io.github.joeyblanks.ecommerce.cart.events;

import io.github.joeyblanks.ecommerce.cart.model.CartItem;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository.LineChange;
import io.github.joeyblanks.ecommerce.cart.repository.CartRepository.OrderApplyResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Removes what was ordered from the customer's cart when an order is placed (docs/events, ADR 007).
 * It SUBTRACTS the ordered quantities instead of clearing the cart, so anything the customer added
 * after placing the order stays. Applying an event twice has no extra effect (event id dedupe,
 * recorded atomically with the change).
 */
@Service
public class OrderPlacedHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedHandler.class);
    private static final int MAX_ATTEMPTS = 10;

    public record OrderPlaced(UUID eventId, UUID orderId, UUID userId, Map<UUID, Integer> orderedQuantities) {
    }

    private final JsonMapper json;
    private final CartRepository carts;

    public OrderPlacedHandler(JsonMapper json, CartRepository carts) {
        this.json = json;
        this.carts = carts;
    }

    public OrderPlaced parse(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            if (!"OrderPlaced".equals(root.path("eventType").asString())) {
                throw new AmqpRejectAndDontRequeueException("Unexpected event type: " + root.path("eventType").asString());
            }
            JsonNode data = root.path("data");
            Map<UUID, Integer> quantities = new LinkedHashMap<>();
            for (JsonNode item : data.path("items")) {
                int quantity = item.path("quantity").asInt();
                if (quantity <= 0) {
                    throw new IllegalArgumentException("quantity must be positive");
                }
                quantities.merge(UUID.fromString(item.path("productId").asString()), quantity, Integer::sum);
            }
            if (quantities.isEmpty()) {
                throw new IllegalArgumentException("an order has at least one item");
            }
            return new OrderPlaced(UUID.fromString(root.path("eventId").asString()),
                    UUID.fromString(data.path("orderId").asString()), UUID.fromString(data.path("userId").asString()),
                    quantities);
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            // Retrying cannot fix a malformed message: dead-letter it immediately.
            throw new AmqpRejectAndDontRequeueException("Malformed OrderPlaced event: " + e.getMessage(), e);
        }
    }

    public void handle(OrderPlaced event) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            List<LineChange> changes = new ArrayList<>();
            event.orderedQuantities().forEach((productId, ordered) ->
                    carts.peek(event.userId(), productId).ifPresent(current -> {
                        CartItem line = current.item();
                        int remaining = line.quantity() - ordered;
                        CartItem replacement = remaining <= 0 ? null
                                : new CartItem(line.productId(), line.sku(), line.name(), line.unitPrice(), remaining,
                                        line.addedAt(), line.updatedAt());
                        changes.add(new LineChange(productId, current, replacement));
                    }));
            // Lines no longer in the cart (removed by the customer) need nothing; the event id is still
            // recorded, so a redelivery stays a no-op.
            OrderApplyResult result = carts.applyOrderedLines(event.userId(), event.eventId(), changes);
            switch (result) {
                case APPLIED -> {
                    log.info("Order {} applied to the cart of {}: {} line(s) reduced or removed",
                            event.orderId(), event.userId(), changes.size());
                    return;
                }
                case ALREADY_APPLIED -> {
                    log.info("Duplicate OrderPlaced {} for order {} ignored", event.eventId(), event.orderId());
                    return;
                }
                case CHANGED_CONCURRENTLY -> { /* the customer changed the cart meanwhile: read again */ }
            }
        }
        // Retried by the listener (with backoff), then dead-lettered.
        throw new IllegalStateException("Cart of " + event.userId() + " kept changing; could not apply order " + event.orderId());
    }
}
