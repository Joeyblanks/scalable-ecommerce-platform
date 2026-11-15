package io.github.joeyblanks.ecommerce.payment.payment;

import static io.github.joeyblanks.ecommerce.payment.db.Sql.instant;
import static io.github.joeyblanks.ecommerce.payment.db.Sql.ts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Every state change is ONE conditional UPDATE ({@code ... WHERE status = '...'}) whose row count (or
 * RETURNING row) says whether this caller made the change. Callers write side effects (outbox events) only
 * when it did. That is what makes handlers and workers safe to race, repeat and run on several instances.
 */
@Repository
public class PaymentRepository {

    /** What a worker needs to make one provider call. */
    public record Claimed(UUID id, UUID orderId, BigDecimal amount, String currency, String providerRef,
                          int attempt, Instant createdAt) {
    }

    /** A finished transition and the data its outbox event needs. */
    public record Transition(PaymentStatus status, UUID paymentId, UUID orderId, BigDecimal amount) {
    }

    private static final RowMapper<Payment> PAYMENT = (rs, n) -> new Payment(
            rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getBigDecimal("amount"), rs.getString("currency"), PaymentStatus.valueOf(rs.getString("status")),
            rs.getBoolean("cancel_requested"), rs.getString("failure_reason"), rs.getString("provider_ref"),
            rs.getString("refund_ref"), rs.getInt("charge_attempts"), rs.getInt("refund_attempts"),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final RowMapper<Transition> TRANSITION = (rs, n) -> new Transition(
            PaymentStatus.valueOf(rs.getString("status")), rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class), rs.getBigDecimal("amount"));

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- creating rows (event handlers)

    /** @return true if this call created the payment. The UNIQUE order_id makes a second one impossible. */
    public boolean insertCharging(UUID id, UUID orderId, UUID userId, BigDecimal amount, String currency, Instant now) {
        return jdbc.update("INSERT INTO payments (id, order_id, user_id, amount, currency, status, next_attempt_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 'CHARGING', ?, ?, ?) ON CONFLICT (order_id) DO NOTHING",
                id, orderId, userId, amount, currency, ts(now), ts(now), ts(now)) == 1;
    }

    /** OrderPlaced arriving after its cancellation: attach the order's details to the tombstone (it stays CANCELLED). */
    public boolean attachToTombstone(UUID orderId, UUID userId, BigDecimal amount, Instant now) {
        return jdbc.update("UPDATE payments SET user_id = ?, amount = ?, updated_at = ? WHERE order_id = ? AND user_id IS NULL "
                + "AND status = 'CANCELLED'", userId, amount, ts(now), orderId) == 1;
    }

    /** OrderCancelled arriving first: remember it so OrderPlaced will not charge. @return true if created. */
    public boolean insertTombstone(UUID id, UUID orderId, String currency, Instant now) {
        return jdbc.update("INSERT INTO payments (id, order_id, currency, status, next_attempt_at, created_at, updated_at, cancelled_at) "
                + "VALUES (?, ?, ?, 'CANCELLED', ?, ?, ?, ?) ON CONFLICT (order_id) DO NOTHING",
                id, orderId, currency, ts(now), ts(now), ts(now), ts(now)) == 1;
    }

    // ---------------------------------------------------------------- OrderCancelled transitions

    /** Never claimed by a worker (no provider call was ever made): void it. Nothing was charged. */
    public boolean voidUnstarted(UUID orderId, Instant now) {
        return jdbc.update("UPDATE payments SET status = 'CANCELLED', cancelled_at = ?, updated_at = ? "
                + "WHERE order_id = ? AND status = 'CHARGING' AND charge_attempts = 0", ts(now), ts(now), orderId) == 1;
    }

    /** A provider call is (or may be) in flight: remember the cancellation; completion will honour it. */
    public boolean requestCancelWhileCharging(UUID orderId, Instant now) {
        return jdbc.update("UPDATE payments SET cancel_requested = TRUE, updated_at = ? "
                + "WHERE order_id = ? AND status = 'CHARGING' AND charge_attempts > 0", ts(now), orderId) == 1;
    }

    /** The charge already succeeded: the refund worker takes over. */
    public boolean startRefund(UUID orderId, Instant now) {
        return jdbc.update("UPDATE payments SET status = 'REFUND_PENDING', refund_next_attempt_at = ?, updated_at = ? "
                + "WHERE order_id = ? AND status = 'SUCCEEDED'", ts(now), ts(now), orderId) == 1;
    }

    // ---------------------------------------------------------------- charging (worker)

    /**
     * Claims due payments for this worker: sets a lease so no other worker takes them until it expires
     * (crash recovery) and counts the attempt. SKIP LOCKED lets several instances claim different rows.
     */
    public List<Claimed> claimDueCharges(Instant now, Instant leaseUntil, int limit) {
        return jdbc.query("UPDATE payments SET lease_until = ?, charge_attempts = charge_attempts + 1, updated_at = ? "
                        + "WHERE id IN (SELECT id FROM payments WHERE status = 'CHARGING' AND next_attempt_at <= ? "
                        + "AND (lease_until IS NULL OR lease_until < ?) ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED) "
                        + "RETURNING id, order_id, amount, currency, provider_ref, charge_attempts, created_at",
                (rs, n) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("provider_ref"),
                        rs.getInt("charge_attempts"), instant(rs, "created_at")),
                ts(leaseUntil), ts(now), ts(now), ts(now), limit);
    }

    /**
     * The provider charged. If the order was cancelled meanwhile, go straight to REFUND_PENDING (and the
     * caller must NOT announce success), otherwise SUCCEEDED. Empty = someone else already finished it.
     */
    public Optional<Transition> completeCharge(UUID id, String providerRef, Instant now) {
        return jdbc.query("UPDATE payments SET status = CASE WHEN cancel_requested THEN 'REFUND_PENDING' ELSE 'SUCCEEDED' END, "
                        + "provider_ref = ?, lease_until = NULL, succeeded_at = ?, refund_next_attempt_at = ?, last_error = NULL, updated_at = ? "
                        + "WHERE id = ? AND status = 'CHARGING' RETURNING id, order_id, amount, status",
                TRANSITION, providerRef, ts(now), ts(now), ts(now), id).stream().findFirst();
    }

    /** The provider definitively declined: FAILED, or CANCELLED (nothing to announce) if the order was cancelled meanwhile. */
    public Optional<Transition> completeDeclined(UUID id, String reason, Instant now) {
        return jdbc.query("UPDATE payments SET status = CASE WHEN cancel_requested THEN 'CANCELLED' ELSE 'FAILED' END, "
                        + "failure_reason = ?, lease_until = NULL, failed_at = ?, cancelled_at = CASE WHEN cancel_requested THEN ? END, "
                        + "last_error = NULL, updated_at = ? WHERE id = ? AND status = 'CHARGING' RETURNING id, order_id, amount, status",
                TRANSITION, reason, ts(now), ts(now), ts(now), id).stream().findFirst();
    }

    /** Unknown outcome: release the lease and try again later with the SAME idempotency key. Never a decline. */
    public boolean chargeTransient(UUID id, String error, Instant nextAttemptAt, Instant now) {
        return jdbc.update("UPDATE payments SET lease_until = NULL, next_attempt_at = ?, last_error = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'CHARGING'", ts(nextAttemptAt), truncate(error), ts(now), id) == 1;
    }

    public boolean markNeedsReview(UUID id, String error, Instant now) {
        return jdbc.update("UPDATE payments SET status = 'NEEDS_REVIEW', lease_until = NULL, last_error = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'CHARGING'", truncate(error), ts(now), id) == 1;
    }

    // ---------------------------------------------------------------- refunding (worker)

    public List<Claimed> claimDueRefunds(Instant now, Instant leaseUntil, int limit) {
        return jdbc.query("UPDATE payments SET refund_lease_until = ?, refund_attempts = refund_attempts + 1, updated_at = ? "
                        + "WHERE id IN (SELECT id FROM payments WHERE status = 'REFUND_PENDING' AND refund_next_attempt_at <= ? "
                        + "AND (refund_lease_until IS NULL OR refund_lease_until < ?) ORDER BY refund_next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED) "
                        + "RETURNING id, order_id, amount, currency, provider_ref, refund_attempts, created_at",
                (rs, n) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("provider_ref"),
                        rs.getInt("refund_attempts"), instant(rs, "created_at")),
                ts(leaseUntil), ts(now), ts(now), ts(now), limit);
    }

    public boolean completeRefund(UUID id, String refundRef, Instant now) {
        return jdbc.update("UPDATE payments SET status = 'REFUNDED', refund_ref = ?, refund_lease_until = NULL, refunded_at = ?, "
                + "last_error = NULL, updated_at = ? WHERE id = ? AND status = 'REFUND_PENDING'", refundRef, ts(now), ts(now), id) == 1;
    }

    public boolean refundTransient(UUID id, String error, Instant nextAttemptAt, Instant now) {
        return jdbc.update("UPDATE payments SET refund_lease_until = NULL, refund_next_attempt_at = ?, last_error = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'REFUND_PENDING'", ts(nextAttemptAt), truncate(error), ts(now), id) == 1;
    }

    // ---------------------------------------------------------------- reads

    /** The caller's own payment only: someone else's order is indistinguishable from an unknown one. Tombstones are hidden. */
    public Optional<Payment> findForUser(UUID orderId, UUID userId) {
        return jdbc.query("SELECT * FROM payments WHERE order_id = ? AND user_id = ?", PAYMENT, orderId, userId).stream().findFirst();
    }

    public Optional<Payment> findByOrder(UUID orderId) {
        return jdbc.query("SELECT * FROM payments WHERE order_id = ?", PAYMENT, orderId).stream().findFirst();
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 1000));
    }
}
