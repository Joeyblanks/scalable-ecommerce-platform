package io.github.joeyblanks.ecommerce.payment.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The data part of the events this service publishes, exactly as defined in docs/events. */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    public static Map<String, Object> succeeded(UUID paymentId, UUID orderId, BigDecimal amount, Instant at) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", paymentId.toString());
        data.put("orderId", orderId.toString());
        data.put("amount", money(amount));
        data.put("succeededAt", at.toString());
        return data;
    }

    public static Map<String, Object> failed(UUID paymentId, UUID orderId, String reason, Instant at) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", paymentId.toString());
        data.put("orderId", orderId.toString());
        data.put("reason", reason);
        data.put("failedAt", at.toString());
        return data;
    }

    /** Money is always a decimal string with two places, never a number (docs/events). */
    public static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
