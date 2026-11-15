package io.github.joeyblanks.ecommerce.notification.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import tools.jackson.databind.json.JsonMapper;

class OrderEventParserTest {

    private final OrderEventParser parser = new OrderEventParser(JsonMapper.builder().build());
    private final UUID eventId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private byte[] message(String type, String data) {
        return ("{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-11-14T10:00:00Z\",\"producer\":\"order-service\",\"data\":" + data + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private String ids() {
        return "\"orderId\":\"" + orderId + "\",\"userId\":\"" + userId + "\"";
    }

    @Test
    void parsesOrderPlacedIgnoringUnknownFields() {
        var event = parser.parse(message("OrderPlaced", "{" + ids() + ",\"items\":[{\"name\":\"Book\",\"quantity\":2,"
                + "\"unitPrice\":\"5.00\",\"lineTotal\":\"10.00\",\"future\":1}],\"totalAmount\":\"10.00\","
                + "\"placedAt\":\"2026-11-14T10:00:00Z\",\"futureField\":true}"));

        assertThat(event).isEqualTo(new OrderEvent.Placed(eventId, orderId, userId,
                java.util.List.of(new OrderEvent.Item("Book", 2, new BigDecimal("5.00"), new BigDecimal("10.00"))),
                new BigDecimal("10.00"), Instant.parse("2026-11-14T10:00:00Z")));
    }

    @Test
    void parsesOrderPaidAndCancelled() {
        assertThat(parser.parse(message("OrderPaid", "{" + ids() + ",\"paidAt\":\"2026-11-14T10:01:00Z\"}")))
                .isEqualTo(new OrderEvent.Paid(eventId, orderId, userId, Instant.parse("2026-11-14T10:01:00Z")));
        assertThat(parser.parse(message("OrderCancelled", "{" + ids() + ",\"reason\":\"PAYMENT_FAILED\",\"cancelledAt\":\"2026-11-14T10:02:00Z\"}")))
                .isEqualTo(new OrderEvent.Cancelled(eventId, orderId, userId, "PAYMENT_FAILED", Instant.parse("2026-11-14T10:02:00Z")));
    }

    @Test
    void malformedMessagesAreRejectedWithoutRequeue() {
        String placedOk = "\"items\":[{\"name\":\"B\",\"quantity\":1,\"unitPrice\":\"1.00\",\"lineTotal\":\"1.00\"}],\"totalAmount\":\"1.00\",\"placedAt\":\"2026-11-14T10:00:00Z\"";
        for (byte[] bad : new byte[][]{
                "not json".getBytes(StandardCharsets.UTF_8),
                message("OrderPaid", "{\"orderId\":\"" + orderId + "\",\"paidAt\":\"2026-11-14T10:01:00Z\"}"),  // no userId
                message("OrderPaid", "{" + ids() + "}"),                                                           // no paidAt
                message("OrderPaid", "{" + ids() + ",\"paidAt\":\"yesterday\"}"),
                message("OrderCancelled", "{" + ids() + ",\"cancelledAt\":\"2026-11-14T10:02:00Z\"}"),            // no reason
                message("OrderPlaced", "{" + ids() + ",\"items\":[],\"totalAmount\":\"1.00\",\"placedAt\":\"2026-11-14T10:00:00Z\"}"),
                message("OrderPlaced", "{" + ids() + ",\"items\":[{\"name\":\"B\",\"quantity\":0,\"unitPrice\":\"1.00\",\"lineTotal\":\"1.00\"}],\"totalAmount\":\"1.00\",\"placedAt\":\"2026-11-14T10:00:00Z\"}"),
                message("OrderPlaced", "{" + ids() + "," + placedOk.replace("\"totalAmount\":\"1.00\"", "\"totalAmount\":\"-1.00\"") + "}"),
                message("PaymentSucceeded", "{" + ids() + "}"),                                                    // not ours
                "{\"eventId\":\"nope\",\"eventType\":\"OrderPaid\",\"data\":{}}".getBytes(StandardCharsets.UTF_8)}) {
            assertThatThrownBy(() -> parser.parse(bad)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
    }
}
