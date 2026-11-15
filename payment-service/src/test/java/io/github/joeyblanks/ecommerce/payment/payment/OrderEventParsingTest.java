package io.github.joeyblanks.ecommerce.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joeyblanks.ecommerce.payment.config.AppProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import tools.jackson.databind.json.JsonMapper;

class OrderEventParsingTest {

    private final OrderEventHandler handler = new OrderEventHandler(JsonMapper.builder().build(), null, null,
            new AppProperties.Payments("EUR", Duration.ofMinutes(2), Duration.ofSeconds(1), Duration.ofMinutes(1),
                    Duration.ofHours(2), 10), Clock.systemUTC());
    private final UUID eventId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private byte[] message(String type, String data) {
        return ("{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-11-14T10:00:00Z\",\"producer\":\"order-service\",\"data\":" + data + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesOrderPlacedIgnoringUnknownFields() {
        var event = handler.parse(message("OrderPlaced", "{\"orderId\":\"" + orderId + "\",\"userId\":\"" + userId
                + "\",\"totalAmount\":\"59.80\",\"items\":[],\"futureField\":1}"));
        assertThat(event).isEqualTo(new OrderEventHandler.Placed(eventId, orderId, userId, new BigDecimal("59.80")));
    }

    @Test
    void parsesOrderCancelled() {
        var event = handler.parse(message("OrderCancelled", "{\"orderId\":\"" + orderId + "\",\"reason\":\"CUSTOMER_REQUEST\"}"));
        assertThat(event).isEqualTo(new OrderEventHandler.Cancelled(eventId, orderId));
    }

    @Test
    void malformedMessagesAreRejectedWithoutRequeue() {
        String ok = "\"orderId\":\"" + orderId + "\",\"userId\":\"" + userId + "\"";
        for (byte[] bad : new byte[][]{
                "not json".getBytes(StandardCharsets.UTF_8),
                message("OrderPlaced", "{" + ok + "}"),                                  // no total
                message("OrderPlaced", "{" + ok + ",\"totalAmount\":\"-1.00\"}"),        // negative
                message("OrderPlaced", "{" + ok + ",\"totalAmount\":\"1.005\"}"),        // not money
                message("OrderPlaced", "{" + ok + ",\"totalAmount\":\"abc\"}"),
                message("OrderPlaced", "{\"orderId\":\"x\",\"userId\":\"" + userId + "\",\"totalAmount\":\"1.00\"}"),
                message("OrderPaid", "{\"orderId\":\"" + orderId + "\"}"),               // not ours
                message("OrderCancelled", "{}")}) {
            assertThatThrownBy(() -> handler.parse(bad)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
    }
}
