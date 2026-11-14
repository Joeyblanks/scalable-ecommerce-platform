package io.github.joeyblanks.ecommerce.order.outbox;

import static io.github.joeyblanks.ecommerce.order.order.Sql.instant;
import static io.github.joeyblanks.ecommerce.order.order.Sql.ts;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** The transactional outbox (ADR 007). */
@Repository
public class OutboxRepository {

    public record Row(UUID id, String eventType, String routingKey, String payload, int attempts, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutboxRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Adds an event to the outbox. MUST run inside the transaction that makes the change the event
     * describes, so the change and its event are committed together or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(UUID aggregateId, String eventType, String routingKey, Map<String, Object> data, Instant now) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", EventTypes.VERSION);
        envelope.put("occurredAt", now.toString());
        envelope.put("producer", EventTypes.PRODUCER);
        envelope.put("data", data);
        jdbc.update("INSERT INTO outbox_events (id, aggregate_id, event_type, routing_key, payload, created_at, next_attempt_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
                eventId, aggregateId, eventType, routingKey, json.writeValueAsString(envelope), ts(now), ts(now));
        return eventId;
    }

    /** Oldest due, unpublished events, locked so that several instances never publish the same row at once. */
    public List<Row> lockDueBatch(Instant now, int limit) {
        return jdbc.query("SELECT id, event_type, routing_key, payload::text AS payload, attempts, created_at FROM outbox_events "
                        + "WHERE published_at IS NULL AND next_attempt_at <= ? ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("routing_key"),
                        rs.getString("payload"), rs.getInt("attempts"), instant(rs, "created_at")),
                ts(now), limit);
    }

    public void markPublished(UUID id, Instant now) {
        jdbc.update("UPDATE outbox_events SET published_at = ?, last_error = NULL WHERE id = ?", ts(now), id);
    }

    public void markFailed(UUID id, String error, Instant nextAttemptAt) {
        jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ?, next_attempt_at = ? WHERE id = ?",
                error == null ? null : error.substring(0, Math.min(error.length(), 1000)), ts(nextAttemptAt), id);
    }

    /** Deletes up to {@code limit} events published before {@code before}. Unpublished events are never deleted. */
    public int deletePublishedBefore(Instant before, int limit) {
        return jdbc.update("DELETE FROM outbox_events WHERE id IN (SELECT id FROM outbox_events "
                + "WHERE published_at IS NOT NULL AND published_at < ? LIMIT ?)", ts(before), limit);
    }
}
