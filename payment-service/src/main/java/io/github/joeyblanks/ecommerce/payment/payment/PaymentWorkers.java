package io.github.joeyblanks.ecommerce.payment.payment;

import io.github.joeyblanks.ecommerce.payment.config.AppProperties;
import io.github.joeyblanks.ecommerce.payment.provider.ChargeResult;
import io.github.joeyblanks.ecommerce.payment.provider.PaymentProvider;
import io.github.joeyblanks.ecommerce.payment.provider.RefundResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Talks to the payment provider, OUTSIDE any database transaction.
 *
 * <p><b>Charging exactly once.</b> A payment is claimed with a lease (so two workers never both charge it),
 * the provider is called with the payment id as idempotency key (so a retry after a timeout or crash returns
 * the original result instead of charging again) and the result is recorded with a conditional update (so
 * a second completion changes nothing). An unknown outcome (timeout, error) is NEVER treated as a decline:
 * the same call is repeated until the provider gives a definitive answer.
 */
@Component
public class PaymentWorkers {

    private static final Logger log = LoggerFactory.getLogger(PaymentWorkers.class);

    private final PaymentRepository payments;
    private final PaymentTransactions tx;
    private final PaymentProvider provider;
    private final AppProperties.Payments config;
    private final Clock clock;

    public PaymentWorkers(PaymentRepository payments, PaymentTransactions tx, PaymentProvider provider,
                          AppProperties.Payments config, Clock clock) {
        this.payments = payments;
        this.tx = tx;
        this.provider = provider;
        this.config = config;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.charge-worker-interval}")
    public void chargeLoop() {
        try {
            chargeDue();
        } catch (RuntimeException e) {
            log.warn("Charge worker run failed (will retry): {}", e.toString()); // e.g. database unavailable
        }
    }

    @Scheduled(fixedDelayString = "${app.jobs.refund-worker-interval}")
    public void refundLoop() {
        try {
            refundDue();
        } catch (RuntimeException e) {
            log.warn("Refund worker run failed (will retry): {}", e.toString());
        }
    }

    /** @return how many payments were handled */
    public int chargeDue() {
        Instant now = clock.instant();
        List<PaymentRepository.Claimed> claimed = payments.claimDueCharges(now, now.plus(config.lease()), config.batchSize());
        claimed.forEach(this::charge);
        return claimed.size();
    }

    public int refundDue() {
        Instant now = clock.instant();
        List<PaymentRepository.Claimed> claimed = payments.claimDueRefunds(now, now.plus(config.lease()), config.batchSize());
        claimed.forEach(this::refund);
        return claimed.size();
    }

    private void charge(PaymentRepository.Claimed claim) {
        ChargeResult result;
        try {
            // Nothing is owed for a zero total, and providers reject zero amounts: succeed without a call.
            result = claim.amount().signum() == 0
                    ? new ChargeResult.Succeeded(null)
                    : provider.charge(claim.amount(), claim.currency(), idempotencyKey(claim));
        } catch (RuntimeException e) {
            result = new ChargeResult.Transient(e.toString()); // the outcome is unknown, not a decline
        }
        switch (result) {
            case ChargeResult.Succeeded ok -> {
                if (tx.chargeSucceeded(claim.id(), ok.providerRef()).isEmpty()) {
                    log.info("Charge for payment {} was already recorded by someone else", claim.id());
                }
            }
            case ChargeResult.Declined declined -> tx.chargeDeclined(claim.id(), declined.reason());
            case ChargeResult.Transient unknown -> {
                Instant now = clock.instant();
                if (Duration.between(claim.createdAt(), now).compareTo(config.needsReviewAfter()) > 0) {
                    log.error("Payment {} (order {}) has had no definitive provider answer since {}: flagged NEEDS_REVIEW. Last: {}",
                            claim.id(), claim.orderId(), claim.createdAt(), unknown.error());
                    tx.needsReview(claim.id(), unknown.error());
                } else {
                    log.warn("Charge for payment {} outcome unknown (attempt {}): {}", claim.id(), claim.attempt(), unknown.error());
                    tx.chargeUnknown(claim.id(), unknown.error(), now.plus(backoff(claim.attempt())));
                }
            }
        }
    }

    private void refund(PaymentRepository.Claimed claim) {
        if (claim.providerRef() == null) { // zero-amount payment: nothing was charged, nothing to refund
            tx.refunded(claim.id(), null);
            return;
        }
        RefundResult result;
        try {
            result = provider.refund(claim.providerRef(), "refund-" + claim.id());
        } catch (RuntimeException e) {
            result = new RefundResult.Transient(e.toString());
        }
        switch (result) {
            case RefundResult.Refunded done -> tx.refunded(claim.id(), done.refundRef());
            case RefundResult.Transient unknown -> {
                log.warn("Refund for payment {} outcome unknown (attempt {}): {}", claim.id(), claim.attempt(), unknown.error());
                tx.refundUnknown(claim.id(), unknown.error(), clock.instant().plus(backoff(claim.attempt())));
            }
        }
    }

    /** The idempotency key: the same for every attempt at this payment, and different for every payment. */
    static String idempotencyKey(PaymentRepository.Claimed claim) {
        return "charge-" + claim.id();
    }

    Duration backoff(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        Duration d = config.retryInitialDelay().multipliedBy(factor);
        return d.compareTo(config.retryMaxDelay()) > 0 ? config.retryMaxDelay() : d;
    }

}
