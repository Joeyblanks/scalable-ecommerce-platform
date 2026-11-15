package io.github.joeyblanks.ecommerce.payment.payment;

import io.github.joeyblanks.ecommerce.payment.outbox.EventTypes;
import io.github.joeyblanks.ecommerce.payment.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each public method is one short database transaction: a conditional state change plus, when (and only
 * when) this call made that change, the outbox event announcing it. No provider call ever happens inside.
 */
@Service
public class PaymentTransactions {

    private final PaymentRepository payments;
    private final OutboxRepository outbox;
    private final Clock clock;

    public PaymentTransactions(PaymentRepository payments, OutboxRepository outbox, Clock clock) {
        this.payments = payments;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** The provider confirmed the charge. Announces PaymentSucceeded unless the order was cancelled meanwhile. */
    @Transactional
    public Optional<PaymentStatus> chargeSucceeded(UUID paymentId, String providerRef) {
        Instant now = clock.instant();
        Optional<PaymentRepository.Transition> done = payments.completeCharge(paymentId, providerRef, now);
        done.filter(t -> t.status() == PaymentStatus.SUCCEEDED).ifPresent(t -> outbox.append(t.orderId(),
                EventTypes.PAYMENT_SUCCEEDED, EventTypes.RK_PAYMENT_SUCCEEDED,
                PaymentEvents.succeeded(t.paymentId(), t.orderId(), t.amount(), now), now));
        return done.map(PaymentRepository.Transition::status);
    }

    /** The provider declined. Announces PaymentFailed unless the order was cancelled meanwhile (then nothing to say). */
    @Transactional
    public Optional<PaymentStatus> chargeDeclined(UUID paymentId, String reason) {
        Instant now = clock.instant();
        Optional<PaymentRepository.Transition> done = payments.completeDeclined(paymentId, reason, now);
        done.filter(t -> t.status() == PaymentStatus.FAILED).ifPresent(t -> outbox.append(t.orderId(),
                EventTypes.PAYMENT_FAILED, EventTypes.RK_PAYMENT_FAILED,
                PaymentEvents.failed(t.paymentId(), t.orderId(), reason, now), now));
        return done.map(PaymentRepository.Transition::status);
    }

    @Transactional
    public void chargeUnknown(UUID paymentId, String error, Instant nextAttemptAt) {
        payments.chargeTransient(paymentId, error, nextAttemptAt, clock.instant());
    }

    @Transactional
    public void needsReview(UUID paymentId, String error) {
        payments.markNeedsReview(paymentId, error, clock.instant());
    }

    @Transactional
    public void refunded(UUID paymentId, String refundRef) {
        payments.completeRefund(paymentId, refundRef, clock.instant());
    }

    @Transactional
    public void refundUnknown(UUID paymentId, String error, Instant nextAttemptAt) {
        payments.refundTransient(paymentId, error, nextAttemptAt, clock.instant());
    }
}
