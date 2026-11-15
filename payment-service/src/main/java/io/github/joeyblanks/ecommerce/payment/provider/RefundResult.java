package io.github.joeyblanks.ecommerce.payment.provider;

public sealed interface RefundResult {

    record Refunded(String refundRef) implements RefundResult {
    }

    /** Unknown outcome: retry (refunds are idempotent). */
    record Transient(String error) implements RefundResult {
    }
}
