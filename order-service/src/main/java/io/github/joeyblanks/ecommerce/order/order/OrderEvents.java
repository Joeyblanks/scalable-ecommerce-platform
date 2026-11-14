package io.github.joeyblanks.ecommerce.order.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the {@code data} part of the events in docs/events. Money is always a decimal STRING. */
final class OrderEvents {

    private OrderEvents() {
    }

    static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static Map<String, Object> placed(Order order, Instant placedAt) {
        List<Map<String, Object>> items = order.items().stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", i.productId().toString());
            m.put("sku", i.sku());
            m.put("name", i.name());
            m.put("unitPrice", money(i.unitPrice()));
            m.put("quantity", i.quantity());
            m.put("lineTotal", money(i.lineTotal()));
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.id().toString());
        data.put("userId", order.userId().toString());
        data.put("items", items);
        data.put("totalAmount", money(order.totalAmount()));
        data.put("placedAt", placedAt.toString());
        return data;
    }

    static Map<String, Object> paid(Order order, String paymentId, Instant paidAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.id().toString());
        data.put("userId", order.userId().toString());
        data.put("paymentId", paymentId);
        data.put("paidAt", paidAt.toString());
        return data;
    }

    static Map<String, Object> cancelled(Order order, String reason, Instant cancelledAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.id().toString());
        data.put("userId", order.userId().toString());
        data.put("reason", reason);
        data.put("cancelledAt", cancelledAt.toString());
        return data;
    }
}
