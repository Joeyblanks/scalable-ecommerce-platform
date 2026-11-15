package io.github.joeyblanks.ecommerce.payment.provider;

import static io.github.joeyblanks.ecommerce.payment.db.Sql.ts;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Built-in provider: no external account, no real money, deterministic (see {@link SimulatedRules}).
 * It keeps a ledger in its own table, keyed by idempotency key, exactly as a real provider would on its
 * side. Repeating a charge with the same key therefore returns the original outcome, also across restarts.
 */
@Component
// Exactly one provider is active: this one unless Stripe test mode is selected (see StripePaymentProvider).
@ConditionalOnExpression("!('${app.payments.provider:auto}' == 'stripe' or "
        + "('${app.payments.provider:auto}' == 'auto' and !'${app.stripe.api-key:}'.isBlank()))")
public class SimulatedPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public SimulatedPaymentProvider(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
        log.warn("Using the SIMULATED payment provider: no real money moves. "
                + "(Set a Stripe TEST key in STRIPE_API_KEY to use Stripe test mode; see ADR 009.)");
    }

    @Override
    public String name() {
        return "simulated";
    }

    @Override
    public ChargeResult charge(BigDecimal amount, String currency, String idempotencyKey) {
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO simulated_charges (idempotency_key, amount, outcome, created_at) VALUES (?, ?, 'PENDING', ?) "
                    + "ON CONFLICT (idempotency_key) DO NOTHING", idempotencyKey, amount, ts(clock.instant()));
            // Row lock: two concurrent calls with the same key are serialised and see the same state.
            Ledger entry = jdbc.query("SELECT outcome, failed_attempts, provider_ref, decline_reason FROM simulated_charges "
                            + "WHERE idempotency_key = ? FOR UPDATE",
                    (rs, n) -> new Ledger(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4)), idempotencyKey).get(0);
            switch (entry.outcome()) {
                case "SUCCEEDED":
                    return new ChargeResult.Succeeded(entry.providerRef());
                case "DECLINED":
                    return new ChargeResult.Declined(entry.declineReason());
                default:
                    break;
            }
            SimulatedRules.Behaviour behaviour = SimulatedRules.behaviourFor(amount);
            switch (behaviour) {
                case DECLINE_CARD:
                    return decline(idempotencyKey, "CARD_DECLINED");
                case DECLINE_FUNDS:
                    return decline(idempotencyKey, "INSUFFICIENT_FUNDS");
                case FLAKY_THEN_SUCCEED:
                    if (entry.failedAttempts() < SimulatedRules.TRANSIENT_FAILURES_FOR_RETRY_DEMO) {
                        jdbc.update("UPDATE simulated_charges SET failed_attempts = failed_attempts + 1 WHERE idempotency_key = ?",
                                idempotencyKey);
                        return new ChargeResult.Transient("simulated provider error (attempt " + (entry.failedAttempts() + 1) + ")");
                    }
                    return succeed(idempotencyKey);
                default:
                    return succeed(idempotencyKey);
            }
        });
    }

    @Override
    public RefundResult refund(String providerRef, String idempotencyKey) {
        return transaction.execute(status -> {
            List<String> existing = jdbc.query("SELECT refund_ref FROM simulated_charges WHERE provider_ref = ? FOR UPDATE",
                    (rs, n) -> rs.getString(1), providerRef);
            if (existing.isEmpty()) {
                return new RefundResult.Transient("unknown charge " + providerRef);
            }
            // Idempotent: a charge is refunded at most once; asking again returns the same refund.
            if (existing.get(0) != null) {
                return new RefundResult.Refunded(existing.get(0));
            }
            String refundRef = "sim_re_" + UUID.randomUUID();
            jdbc.update("UPDATE simulated_charges SET refund_ref = ? WHERE provider_ref = ?", refundRef, providerRef);
            return new RefundResult.Refunded(refundRef);
        });
    }

    private ChargeResult succeed(String key) {
        String ref = "sim_ch_" + UUID.randomUUID();
        jdbc.update("UPDATE simulated_charges SET outcome = 'SUCCEEDED', provider_ref = ? WHERE idempotency_key = ?", ref, key);
        return new ChargeResult.Succeeded(ref);
    }

    private ChargeResult decline(String key, String reason) {
        jdbc.update("UPDATE simulated_charges SET outcome = 'DECLINED', decline_reason = ? WHERE idempotency_key = ?", reason, key);
        return new ChargeResult.Declined(reason);
    }

    private record Ledger(String outcome, int failedAttempts, String providerRef, String declineReason) {
    }
}
