package io.github.joeyblanks.ecommerce.payment.provider;

/** Outcome of a charge. Only {@link Succeeded} and {@link Declined} are definitive. */
public sealed interface ChargeResult {

    /** Money was taken; {@code providerRef} identifies the charge for a later refund. */
    record Succeeded(String providerRef) implements ChargeResult {
    }

    /** The provider definitively refused (nothing was charged). {@code reason} is a stable code, e.g. CARD_DECLINED. */
    record Declined(String reason) implements ChargeResult {
    }

    /** Unknown outcome (timeout, 5xx, provider down): the charge may or may not have happened. Retry with the same key. */
    record Transient(String error) implements ChargeResult {
    }
}
