package io.github.joeyblanks.ecommerce.order.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import tools.jackson.databind.json.JsonMapper;

class PaymentEventParsingTest {

    private final PaymentEventHandler handler = new PaymentEventHandler(JsonMapper.builder().build(), null, null, null, null);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesAValidPaymentFailed() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        var event = handler.parse(bytes("{\"eventId\":\"" + eventId + "\",\"eventType\":\"PaymentFailed\",\"eventVersion\":1,"
                + "\"data\":{\"orderId\":\"" + orderId + "\",\"paymentId\":\"p\",\"reason\":\"CARD_DECLINED\"}}"));

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.reason()).isEqualTo("CARD_DECLINED");
    }

    @Test
    void malformedOrUnexpectedMessagesAreRejectedWithoutRequeue() {
        String[] bad = {
                "not json",
                "{}",
                "{\"eventId\":\"nope\",\"eventType\":\"PaymentFailed\",\"data\":{\"orderId\":\"" + UUID.randomUUID() + "\"}}",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"PaymentFailed\",\"data\":{}}",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"OrderPlaced\",\"data\":{\"orderId\":\"" + UUID.randomUUID() + "\"}}"};
        for (String body : bad) {
            assertThatThrownBy(() -> handler.parse(bytes(body))).as(body).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
    }
}
