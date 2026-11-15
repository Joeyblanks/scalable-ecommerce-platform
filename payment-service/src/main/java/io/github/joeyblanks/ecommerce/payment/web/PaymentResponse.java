package io.github.joeyblanks.ecommerce.payment.web;

import io.github.joeyblanks.ecommerce.payment.payment.Payment;
import io.github.joeyblanks.ecommerce.payment.payment.PaymentEvents;
import io.github.joeyblanks.ecommerce.payment.payment.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/** What a customer may see. Provider references, attempts and internal states are deliberately absent. */
public record PaymentResponse(UUID orderId, PaymentStatus.CustomerStatus status, String amount, String currency,
                              String failureReason, Instant updatedAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.orderId(), p.status().forCustomer(), PaymentEvents.money(p.amount()), p.currency(),
                p.status() == PaymentStatus.FAILED ? p.failureReason() : null, p.updatedAt());
    }
}
