package io.github.joeyblanks.ecommerce.payment.support;

import io.github.joeyblanks.ecommerce.payment.provider.ChargeResult;
import io.github.joeyblanks.ecommerce.payment.provider.PaymentProvider;
import io.github.joeyblanks.ecommerce.payment.provider.RefundResult;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps the real simulated provider so tests can count how often the provider is CALLED (the ledger only
 * shows how often money moved), lose a response after the provider acted ("charged but we never heard"),
 * fail every call, or hold a call open to test cancellation during a charge.
 */
public class ControlledProvider implements PaymentProvider {

    /** Held open: the provider call has started ({@link #entered}) but does not return until {@link #release}. */
    public static final class Gate {
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);
    }

    private final PaymentProvider delegate;
    public final AtomicInteger chargeCalls = new AtomicInteger();
    public final AtomicInteger refundCalls = new AtomicInteger();
    public volatile boolean loseNextChargeResponse;
    public volatile boolean loseNextRefundResponse;
    public volatile boolean alwaysFail;
    public volatile Gate chargeGate;
    public volatile Gate refundGate;

    public ControlledProvider(PaymentProvider delegate) {
        this.delegate = delegate;
    }

    public void reset() {
        chargeCalls.set(0);
        refundCalls.set(0);
        loseNextChargeResponse = false;
        loseNextRefundResponse = false;
        alwaysFail = false;
        chargeGate = null;
        refundGate = null;
    }

    @Override
    public String name() {
        return "controlled-simulated";
    }

    @Override
    public ChargeResult charge(BigDecimal amount, String currency, String idempotencyKey) {
        chargeCalls.incrementAndGet();
        Gate gate = chargeGate;
        if (gate != null) {
            gate.entered.countDown();
            await(gate.release);
        }
        if (alwaysFail) {
            throw new IllegalStateException("provider unreachable");
        }
        ChargeResult result = delegate.charge(amount, currency, idempotencyKey);
        if (loseNextChargeResponse) {
            loseNextChargeResponse = false;
            throw new IllegalStateException("response lost after the provider acted");
        }
        return result;
    }

    @Override
    public RefundResult refund(String providerRef, String idempotencyKey) {
        refundCalls.incrementAndGet();
        Gate gate = refundGate;
        if (gate != null) {
            gate.entered.countDown();
            await(gate.release);
        }
        RefundResult result = delegate.refund(providerRef, idempotencyKey);
        if (loseNextRefundResponse) {
            loseNextRefundResponse = false;
            throw new IllegalStateException("response lost after the provider acted");
        }
        return result;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
