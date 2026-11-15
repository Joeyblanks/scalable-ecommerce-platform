package io.github.joeyblanks.ecommerce.notification.notification;

import static io.github.joeyblanks.ecommerce.notification.db.Sql.ts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All SQL for notifications. Every state change is a single conditional UPDATE. */
@Repository
public class NotificationRepository {

    /** A notification a worker has claimed (and counted an attempt for). */
    public record Claimed(UUID id, UUID eventId, NotificationKind kind, UUID userId, UUID orderId, String payload,
                          int attempt, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that this event must produce an email. The UNIQUE event_id makes a second recording of the same
     * event (redelivery, duplicate, concurrent consumers) impossible.
     *
     * @return true only for the call that created the row
     */
    public boolean insertIfNew(UUID id, UUID eventId, NotificationKind kind, UUID userId, UUID orderId, String payload, Instant now) {
        return jdbc.update("INSERT INTO notifications (id, event_id, kind, user_id, order_id, payload, status, attempts, "
                        + "next_attempt_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PENDING', 0, ?, ?, ?) "
                        + "ON CONFLICT (event_id) DO NOTHING",
                id, eventId, kind.name(), userId, orderId, payload, ts(now), ts(now), ts(now)) == 1;
    }

    /**
     * Claims due notifications for one worker: sets a lease and counts the attempt in the same statement.
     * SKIP LOCKED and the lease check mean two workers never get the same notification while the lease is valid;
     * if a worker dies, the lease expires and another one takes over.
     */
    public List<Claimed> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.query("UPDATE notifications SET lease_until = ?, attempts = attempts + 1, updated_at = ? WHERE id IN ("
                        + "SELECT id FROM notifications WHERE status = 'PENDING' AND next_attempt_at <= ? "
                        + "AND (lease_until IS NULL OR lease_until <= ?) ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED) "
                        + "RETURNING id, event_id, kind, user_id, order_id, payload::text, attempts, created_at",
                (rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        NotificationKind.valueOf(rs.getString(3)), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                        rs.getString(6), rs.getInt(7), rs.getObject(8, java.time.OffsetDateTime.class).toInstant()),
                ts(leaseUntil), ts(now), ts(now), ts(now), limit);
    }

    /** @return true if this call made the transition (a second completion changes nothing) */
    public boolean markSent(UUID id, Instant now) {
        return jdbc.update("UPDATE notifications SET status = 'SENT', sent_at = ?, lease_until = NULL, last_error = NULL, updated_at = ? "
                + "WHERE id = ? AND status = 'PENDING'", ts(now), ts(now), id) == 1;
    }

    public boolean scheduleRetry(UUID id, String error, Instant nextAttemptAt, Instant now) {
        return jdbc.update("UPDATE notifications SET next_attempt_at = ?, lease_until = NULL, last_error = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'PENDING'", ts(nextAttemptAt), truncate(error), ts(now), id) == 1;
    }

    public boolean markFailed(UUID id, String error, Instant now) {
        return jdbc.update("UPDATE notifications SET status = 'FAILED', lease_until = NULL, last_error = ?, updated_at = ? "
                + "WHERE id = ? AND status = 'PENDING'", truncate(error), ts(now), id) == 1;
    }

    /** Old SENT rows only. PENDING and FAILED rows are never deleted. */
    public int deleteSentBefore(Instant before, int limit) {
        return jdbc.update("DELETE FROM notifications WHERE id IN (SELECT id FROM notifications WHERE status = 'SENT' "
                + "AND sent_at < ? LIMIT ?)", ts(before), limit);
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
