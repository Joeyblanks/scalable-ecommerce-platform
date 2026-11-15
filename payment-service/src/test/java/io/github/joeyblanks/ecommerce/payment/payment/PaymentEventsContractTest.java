package io.github.joeyblanks.ecommerce.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The produced event data must match docs/events exactly (field names, money as decimal strings). */
class PaymentEventsContractTest {

    private final UUID payment = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    @Test
    void paymentSucceededV1() {
        var data = PaymentEvents.succeeded(payment, order, new BigDecimal("64.3"), Instant.parse("2026-11-14T10:00:00Z"));
        assertThat(data).containsOnlyKeys("paymentId", "orderId", "amount", "succeededAt")
                .containsEntry("paymentId", payment.toString()).containsEntry("orderId", order.toString())
                .containsEntry("amount", "64.30").containsEntry("succeededAt", "2026-11-14T10:00:00Z");
    }

    @Test
    void paymentFailedV1() {
        var data = PaymentEvents.failed(payment, order, "CARD_DECLINED", Instant.EPOCH);
        assertThat(data).containsOnlyKeys("paymentId", "orderId", "reason", "failedAt").containsEntry("reason", "CARD_DECLINED");
    }

    @Test
    void moneyIsAlwaysTwoDecimalsAndNeverScientific() {
        assertThat(PaymentEvents.money(new BigDecimal("1E+3"))).isEqualTo("1000.00");
        assertThat(PaymentEvents.money(BigDecimal.ZERO)).isEqualTo("0.00");
    }

    @Test
    void customersSeeSimplifiedStatuses() {
        assertThat(PaymentStatus.CHARGING.forCustomer()).isEqualTo(PaymentStatus.CustomerStatus.PROCESSING);
        assertThat(PaymentStatus.NEEDS_REVIEW.forCustomer()).isEqualTo(PaymentStatus.CustomerStatus.PROCESSING);
        assertThat(PaymentStatus.SUCCEEDED.forCustomer()).isEqualTo(PaymentStatus.CustomerStatus.PAID);
        assertThat(PaymentStatus.REFUND_PENDING.forCustomer()).isEqualTo(PaymentStatus.CustomerStatus.REFUNDING);
    }
}
