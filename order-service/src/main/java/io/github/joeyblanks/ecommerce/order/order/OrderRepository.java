package io.github.joeyblanks.ecommerce.order.order;

import static io.github.joeyblanks.ecommerce.order.order.Sql.instant;
import static io.github.joeyblanks.ecommerce.order.order.Sql.ts;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Orders, in plain SQL. Every state change is a conditional UPDATE ("... WHERE status = 'PENDING'"):
 * if two things race (a customer cancel and a payment, a slow request and the reconciler), exactly
 * one of them changes the row and the other sees 0 rows updated. That is what makes the saga safe.
 */
@Repository
public class OrderRepository {

    private static final String COLUMNS = "id, user_id, status, cancel_reason, failure_reason, total_amount, payment_id, "
            + "created_at, updated_at, paid_at, cancelled_at";

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws org.springframework.dao.DuplicateKeyException if this user already used the key */
    public void insertReserving(UUID id, UUID userId, String idempotencyKey, Instant now) {
        jdbc.update("INSERT INTO orders (id, user_id, status, idempotency_key, created_at, updated_at) VALUES (?, ?, 'RESERVING', ?, ?, ?)",
                id, userId, idempotencyKey, ts(now), ts(now));
    }

    public Optional<Order> findByUserAndKey(UUID userId, String idempotencyKey) {
        return one("SELECT " + COLUMNS + " FROM orders WHERE user_id = ? AND idempotency_key = ?", userId, idempotencyKey);
    }

    public Optional<Order> findById(UUID id) {
        return one("SELECT " + COLUMNS + " FROM orders WHERE id = ?", id);
    }

    /** Only the owner's orders in a customer-visible state. */
    public Optional<Order> findVisible(UUID id, UUID userId) {
        return one("SELECT " + COLUMNS + " FROM orders WHERE id = ? AND user_id = ? AND status IN ('PENDING', 'PAID', 'CANCELLED')",
                id, userId);
    }

    public List<Order> listVisible(UUID userId, int page, int size) {
        List<Order> orders = jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE user_id = ? AND status IN ('PENDING', 'PAID', 'CANCELLED') "
                + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", this::map, userId, size, (long) page * size);
        return orders.stream().map(o -> o.withItems(items(o.id()))).toList();
    }

    public long countVisible(UUID userId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ? AND status IN ('PENDING', 'PAID', 'CANCELLED')",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    /** RESERVING -> PENDING. 0 if the attempt is no longer RESERVING (e.g. the reconciler failed it). */
    public int markPending(UUID id, BigDecimal total, List<Order.Item> items, Instant now) {
        int updated = jdbc.update("UPDATE orders SET status = 'PENDING', total_amount = ?, updated_at = ? WHERE id = ? AND status = 'RESERVING'",
                total, ts(now), id);
        if (updated == 1) {
            for (Order.Item i : items) {
                jdbc.update("INSERT INTO order_items (order_id, product_id, sku, name, unit_price, quantity, line_total, price_changed) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, i.productId(), i.sku(), i.name(), i.unitPrice(), i.quantity(),
                        i.lineTotal(), i.priceChanged());
            }
        }
        return updated;
    }

    /** RESERVING -> FAILED, freeing the idempotency key so the client can retry with it. */
    public int markFailed(UUID id, String reason, Instant now) {
        return jdbc.update("UPDATE orders SET status = 'FAILED', failure_reason = ?, idempotency_key = NULL, updated_at = ? "
                + "WHERE id = ? AND status = 'RESERVING'", reason, ts(now), id);
    }

    /** PENDING -> PAID. */
    public int markPaid(UUID id, String paymentId, Instant now) {
        return jdbc.update("UPDATE orders SET status = 'PAID', payment_id = ?, paid_at = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                paymentId, ts(now), ts(now), id);
    }

    /** PENDING -> CANCELLED, optionally only if owned by {@code userId}. */
    public int markCancelled(UUID id, UUID userIdOrNull, String reason, Instant now) {
        if (userIdOrNull == null) {
            return jdbc.update("UPDATE orders SET status = 'CANCELLED', cancel_reason = ?, cancelled_at = ?, updated_at = ? "
                    + "WHERE id = ? AND status = 'PENDING'", reason, ts(now), ts(now), id);
        }
        return jdbc.update("UPDATE orders SET status = 'CANCELLED', cancel_reason = ?, cancelled_at = ?, updated_at = ? "
                + "WHERE id = ? AND user_id = ? AND status = 'PENDING'", reason, ts(now), ts(now), id, userIdOrNull);
    }

    public List<UUID> findIdsByStatusCreatedBefore(OrderStatus status, Instant before, int limit) {
        return jdbc.queryForList("SELECT id FROM orders WHERE status = ? AND created_at < ? ORDER BY created_at LIMIT ?",
                UUID.class, status.name(), ts(before), limit);
    }

    public List<Order.Item> items(UUID orderId) {
        return jdbc.query("SELECT product_id, sku, name, unit_price, quantity, line_total, price_changed FROM order_items "
                        + "WHERE order_id = ? ORDER BY sku, product_id",
                (rs, n) -> new Order.Item(rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("name"),
                        rs.getBigDecimal("unit_price"), rs.getInt("quantity"), rs.getBigDecimal("line_total"),
                        rs.getBoolean("price_changed")), orderId);
    }

    private Optional<Order> one(String sql, Object... args) {
        List<Order> found = jdbc.query(sql, this::map, args);
        return found.stream().findFirst().map(o -> o.withItems(items(o.id())));
    }

    private Order map(ResultSet rs, int n) throws SQLException {
        return new Order(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                OrderStatus.valueOf(rs.getString("status")), rs.getString("cancel_reason"), rs.getString("failure_reason"),
                rs.getBigDecimal("total_amount"), rs.getString("payment_id"), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "paid_at"), instant(rs, "cancelled_at"), List.of());
    }
}
