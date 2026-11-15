package io.github.joeyblanks.ecommerce.payment.provider;

import java.math.BigDecimal;

/**
 * The payment provider behind payment-service. Both operations MUST be idempotent by the key (or, for a
 * refund, by the charge): repeating a call after a timeout or crash returns the original outcome and never
 * moves money twice. Implementations may throw on network trouble; callers treat any exception as
 * {@link ChargeResult.Transient}: the outcome is unknown, never a decline.
 */
public interface PaymentProvider {

    /** Short name for logs, e.g. "simulated". */
    String name();

    ChargeResult charge(BigDecimal amount, String currency, String idempotencyKey);

    RefundResult refund(String providerRef, String idempotencyKey);
}
