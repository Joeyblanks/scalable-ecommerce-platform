package io.github.joeyblanks.ecommerce.order.order;

import static io.github.joeyblanks.ecommerce.order.order.Sql.ts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stock to give back to the catalog. Written with the change that requires it; retried until done. */
@Repository
public class StockReleaseRepository {

    public record Due(UUID orderId, int attempts) {
    }

    private final JdbcTemplate jdbc;

    public StockReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID orderId, Instant now) {
        jdbc.update("INSERT INTO stock_releases (order_id, created_at, next_attempt_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (order_id) DO NOTHING", orderId, ts(now), ts(now));
    }

    public List<Due> lockDue(Instant now, int limit) {
        return jdbc.query("SELECT order_id, attempts FROM stock_releases WHERE completed_at IS NULL AND next_attempt_at <= ? "
                        + "ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new Due(rs.getObject("order_id", UUID.class), rs.getInt("attempts")), ts(now), limit);
    }

    public void complete(UUID orderId, Instant now) {
        jdbc.update("UPDATE stock_releases SET completed_at = ?, last_error = NULL WHERE order_id = ?", ts(now), orderId);
    }

    public void retryLater(UUID orderId, String error, Instant next) {
        jdbc.update("UPDATE stock_releases SET attempts = attempts + 1, last_error = ?, next_attempt_at = ? WHERE order_id = ?",
                error == null ? null : error.substring(0, Math.min(error.length(), 1000)), ts(next), orderId);
    }

    public int deleteCompletedBefore(Instant before, int limit) {
        return jdbc.update("DELETE FROM stock_releases WHERE order_id IN (SELECT order_id FROM stock_releases "
                + "WHERE completed_at IS NOT NULL AND completed_at < ? LIMIT ?)", ts(before), limit);
    }
}
