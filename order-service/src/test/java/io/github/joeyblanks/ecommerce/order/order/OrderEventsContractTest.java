package io.github.joeyblanks.ecommerce.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The produced event data must match docs/events exactly (field names, money as decimal strings). */
class OrderEventsContractTest {

    private final Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), OrderStatus.PENDING, null, null,
            new BigDecimal("64.3"), null, Instant.EPOCH, Instant.EPOCH, null, null,
            List.of(new Order.Item(UUID.randomUUID(), "BOOK", "Book", new BigDecimal("29.9"), 2, new BigDecimal("59.8"), false)));

    @Test
    void orderPlacedV1() {
        Map<String, Object> data = OrderEvents.placed(order, Instant.parse("2026-11-14T10:00:00Z"));

        assertThat(data).containsOnlyKeys("orderId", "userId", "items", "totalAmount", "placedAt");
        assertThat(data.get("totalAmount")).isEqualTo("64.30");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) data.get("items")).get(0);
        assertThat(item).containsOnlyKeys("productId", "sku", "name", "unitPrice", "quantity", "lineTotal");
        assertThat(item.get("unitPrice")).isEqualTo("29.90");
        assertThat(item.get("lineTotal")).isEqualTo("59.80");
        assertThat(item.get("quantity")).isEqualTo(2);
        assertThat(data.get("placedAt")).isEqualTo("2026-11-14T10:00:00Z");
    }

    @Test
    void orderPaidV1() {
        assertThat(OrderEvents.paid(order, "pay-1", Instant.EPOCH)).containsOnlyKeys("orderId", "userId", "paymentId", "paidAt");
    }

    @Test
    void orderCancelledV1() {
        assertThat(OrderEvents.cancelled(order, "PAYMENT_FAILED", Instant.EPOCH))
                .containsOnlyKeys("orderId", "userId", "reason", "cancelledAt")
                .containsEntry("reason", "PAYMENT_FAILED");
    }

    @Test
    void moneyIsAlwaysTwoDecimalsAndNeverScientific() {
        assertThat(OrderEvents.money(new BigDecimal("1E+3"))).isEqualTo("1000.00");
        assertThat(OrderEvents.money(new BigDecimal("0.005"))).isEqualTo("0.01");
        assertThat(OrderEvents.money(BigDecimal.ZERO)).isEqualTo("0.00");
    }
}
