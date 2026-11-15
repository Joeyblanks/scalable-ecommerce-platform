package io.github.joeyblanks.ecommerce.payment.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A payment row as read from the database. Provider references are internal and never exposed. */
public record Payment(UUID id, UUID orderId, UUID userId, BigDecimal amount, String currency, PaymentStatus status,
                      boolean cancelRequested, String failureReason, String providerRef, String refundRef,
                      int chargeAttempts, int refundAttempts, Instant createdAt, Instant updatedAt) {

    /** A tombstone records a cancellation that arrived before the order; it was never a payment. */
    public boolean isTombstone() {
        return userId == null;
    }
}
