package io.github.joeyblanks.ecommerce.catalog.reservation;

import io.github.joeyblanks.ecommerce.catalog.reservation.InsufficientStockException.Reason;
import io.github.joeyblanks.ecommerce.catalog.reservation.InsufficientStockException.Shortage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic, idempotent stock reservations. Written as explicit SQL so the guarantees are visible:
 *
 * <ul>
 *   <li><b>All or nothing:</b> one transaction; each item is a conditional decrement
 *       ({@code ... WHERE stock_quantity >= ?}); if any item fails, everything rolls back.</li>
 *   <li><b>No overselling under concurrency:</b> the conditional decrement is atomic per row, and the
 *       table's CHECK constraint (stock >= 0) is a second safety net.</li>
 *   <li><b>No deadlocks:</b> every transaction locks the reservation row first, then products in
 *       ascending id order, so two orders can never wait on each other in a cycle.</li>
 *   <li><b>Idempotent:</b> the caller's id is the primary key; repeating a reserve returns the
 *       original result, repeating a release does nothing.</li>
 *   <li><b>Late reserves are refused:</b> releasing an id that was never reserved leaves a RELEASED
 *       row (a tombstone), so a reserve that arrives after its own release cannot take stock.</li>
 * </ul>
 */
@Service
public class StockReservationService {

    public static final String RESERVED = "RESERVED";
    public static final String RELEASED = "RELEASED";
    private static final Logger log = LoggerFactory.getLogger(StockReservationService.class);

    /** {@code created} is false when an existing reservation was returned (an idempotent repeat). */
    public record Result(boolean created, ReservationResponse reservation) {
    }

    private final JdbcTemplate jdbc;

    public StockReservationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Result reserve(UUID reservationId, List<ReserveRequest.Item> items, String caller) {
        requireDistinctProducts(items);

        // Claim the id. If another transaction holds it, this waits for it to commit or roll back.
        int inserted = jdbc.update("INSERT INTO stock_reservations (id, status, created_by) VALUES (?, ?, ?) "
                + "ON CONFLICT (id) DO NOTHING", reservationId, RESERVED, caller);
        if (inserted == 0) {
            return new Result(false, existing(reservationId, items));
        }

        List<ReserveRequest.Item> ordered = items.stream()
                .sorted(Comparator.comparing(ReserveRequest.Item::productId)).toList();
        Map<UUID, Integer> takenHere = new HashMap<>();
        List<ReservationResponse.Item> reserved = new ArrayList<>();
        for (ReserveRequest.Item item : ordered) {
            List<ReservationResponse.Item> row = jdbc.query(
                    "UPDATE products SET stock_quantity = stock_quantity - ?, version = version + 1, updated_at = now() "
                            + "WHERE id = ? AND stock_quantity >= ? RETURNING sku, name, price",
                    (rs, n) -> {
                        BigDecimal price = rs.getBigDecimal("price").setScale(2, RoundingMode.HALF_UP);
                        return new ReservationResponse.Item(item.productId(), rs.getString("sku"), rs.getString("name"),
                                price, item.quantity(), price.multiply(BigDecimal.valueOf(item.quantity())));
                    },
                    item.quantity(), item.productId(), item.quantity());
            if (row.isEmpty()) {
                throw new InsufficientStockException(shortages(items, takenHere)); // rolls everything back
            }
            takenHere.put(item.productId(), item.quantity());
            ReservationResponse.Item r = row.get(0);
            jdbc.update("INSERT INTO stock_reservation_items (reservation_id, product_id, sku, name, unit_price, quantity) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", reservationId, r.productId(), r.sku(), r.name(), r.unitPrice(), r.quantity());
            reserved.add(r);
        }
        log.info("Reserved stock for {} ({} items) by {}", reservationId, reserved.size(), caller);
        return new Result(true, ReservationResponse.of(reservationId, RESERVED, reserved));
    }

    /** Returns reserved stock exactly once. Unknown ids become a tombstone. Always safe to repeat. */
    @Transactional
    public ReservationResponse release(UUID reservationId, String caller) {
        int tombstone = jdbc.update("INSERT INTO stock_reservations (id, status, created_by) VALUES (?, ?, ?) "
                + "ON CONFLICT (id) DO NOTHING", reservationId, RELEASED, caller);
        if (tombstone == 1) {
            log.info("Release of unknown reservation {} by {}: recorded so a late reserve is refused", reservationId, caller);
            return ReservationResponse.of(reservationId, RELEASED, List.of());
        }
        String status = jdbc.queryForObject("SELECT status FROM stock_reservations WHERE id = ? FOR UPDATE",
                String.class, reservationId);
        List<ReservationResponse.Item> items = items(reservationId);
        if (RELEASED.equals(status)) {
            return ReservationResponse.of(reservationId, RELEASED, items); // already done: no effect
        }
        for (ReservationResponse.Item item : items) { // already in ascending product id order
            int updated = jdbc.update("UPDATE products SET stock_quantity = stock_quantity + ?, version = version + 1, "
                    + "updated_at = now() WHERE id = ?", item.quantity(), item.productId());
            if (updated == 0) {
                log.warn("Releasing {}: product {} no longer exists; its {} units are not returned",
                        reservationId, item.productId(), item.quantity());
            }
        }
        jdbc.update("UPDATE stock_reservations SET status = ?, updated_at = now() WHERE id = ?", RELEASED, reservationId);
        log.info("Released stock for {} by {}", reservationId, caller);
        return ReservationResponse.of(reservationId, RELEASED, items);
    }

    private ReservationResponse existing(UUID reservationId, List<ReserveRequest.Item> requested) {
        String status = jdbc.queryForObject("SELECT status FROM stock_reservations WHERE id = ?", String.class, reservationId);
        if (RELEASED.equals(status)) {
            throw new ReservationConflictException("Reservation " + reservationId + " was already released; use a new id");
        }
        List<ReservationResponse.Item> stored = items(reservationId);
        Map<UUID, Integer> storedQuantities = new HashMap<>();
        stored.forEach(i -> storedQuantities.put(i.productId(), i.quantity()));
        Map<UUID, Integer> requestedQuantities = new HashMap<>();
        requested.forEach(i -> requestedQuantities.put(i.productId(), i.quantity()));
        if (!storedQuantities.equals(requestedQuantities)) {
            throw new ReservationConflictException("Reservation " + reservationId + " already exists with different items");
        }
        return ReservationResponse.of(reservationId, RESERVED, stored);
    }

    private List<ReservationResponse.Item> items(UUID reservationId) {
        return jdbc.query("SELECT product_id, sku, name, unit_price, quantity FROM stock_reservation_items "
                        + "WHERE reservation_id = ? ORDER BY product_id",
                (rs, n) -> {
                    BigDecimal price = rs.getBigDecimal("unit_price").setScale(2, RoundingMode.HALF_UP);
                    int quantity = rs.getInt("quantity");
                    return new ReservationResponse.Item(rs.getObject("product_id", UUID.class), rs.getString("sku"),
                            rs.getString("name"), price, quantity, price.multiply(BigDecimal.valueOf(quantity)));
                }, reservationId);
    }

    /** Every requested item that cannot be satisfied, counting what this transaction already took. */
    private List<Shortage> shortages(List<ReserveRequest.Item> items, Map<UUID, Integer> takenHere) {
        Map<UUID, Integer> stock = new HashMap<>();
        jdbc.query("SELECT id, stock_quantity FROM products WHERE id = ANY (?)",
                rs -> {
                    stock.put(rs.getObject("id", UUID.class), rs.getInt("stock_quantity"));
                },
                (Object) items.stream().map(ReserveRequest.Item::productId).toArray(UUID[]::new));
        List<Shortage> result = new ArrayList<>();
        for (ReserveRequest.Item item : items) {
            Integer current = stock.get(item.productId());
            if (current == null) {
                result.add(new Shortage(item.productId(), item.quantity(), 0, Reason.PRODUCT_NOT_FOUND));
                continue;
            }
            int available = current + takenHere.getOrDefault(item.productId(), 0);
            if (available < item.quantity()) {
                result.add(new Shortage(item.productId(), item.quantity(), available, Reason.INSUFFICIENT_STOCK));
            }
        }
        return result;
    }

    private static void requireDistinctProducts(List<ReserveRequest.Item> items) {
        if (new HashSet<>(items.stream().map(ReserveRequest.Item::productId).toList()).size() != items.size()) {
            throw new IllegalArgumentException("Each product may appear only once per reservation");
        }
    }
}
