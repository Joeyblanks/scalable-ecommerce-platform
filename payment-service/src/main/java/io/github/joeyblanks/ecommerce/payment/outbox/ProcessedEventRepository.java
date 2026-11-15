package io.github.joeyblanks.ecommerce.payment.outbox;

import static io.github.joeyblanks.ecommerce.payment.db.Sql.ts;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ids of consumed events: the deduplication half of "at-least-once delivery, exactly-once effect". */
@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbc;

    public ProcessedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true the first time this event id is seen. Runs in the caller's transaction, together with
     * the event's effect: if the effect rolls back, so does the "seen" mark, and the redelivery is processed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String eventType, Instant now) {
        return jdbc.update("INSERT INTO processed_events (event_id, event_type, processed_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (event_id) DO NOTHING", eventId, eventType, ts(now)) == 1;
    }

    public int deleteProcessedBefore(Instant before, int limit) {
        return jdbc.update("DELETE FROM processed_events WHERE event_id IN (SELECT event_id FROM processed_events "
                + "WHERE processed_at < ? LIMIT ?)", ts(before), limit);
    }
}
