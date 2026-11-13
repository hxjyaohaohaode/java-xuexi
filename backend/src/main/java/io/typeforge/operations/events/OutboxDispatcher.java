package io.typeforge.operations.events;

import io.typeforge.shared.JsonCodec;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once transport, transactional idempotent consumers. Bounded retry, observable dead letters. */
@Service
public class OutboxDispatcher {
    private record Delivery(String type,String payload,Timestamp publishedAt,int attempts,Timestamp nextAttemptAt) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final Clock clock;
    private final List<DomainEventConsumer> consumers;
    private final boolean enabled;
    public OutboxDispatcher(JdbcTemplate jdbc, TransactionTemplate tx, JsonCodec json, Clock clock,
                            List<DomainEventConsumer> consumers,
                            @Value("${typeforge.events.dispatch-enabled:true}") boolean enabled) {
        this.jdbc=jdbc; this.tx=tx; this.json=json; this.clock=clock;
        this.consumers=List.copyOf(consumers); this.enabled=enabled;
        if (consumers.stream().map(DomainEventConsumer::name).distinct().count() != consumers.size()) {
            throw new IllegalArgumentException("Duplicate event consumer name");
        }
    }
    @Scheduled(fixedDelayString="${typeforge.events.poll-millis:1500}")
    public void scheduled() { if (enabled) dispatchBatch(32); }

    public int dispatchBatch(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Bounded batch required");
        int completed=0;
        for (int iteration=0;iteration<limit;iteration++) {
            // Select only each workspace's deliverable head. UUID ordering cannot starve an earlier
            // sequence with the same timestamp, and a poisoned workspace cannot monopolize the batch.
            // Requery after each transaction so a bounded batch can consume successive events too.
            // Compute actual pending heads once, before retry eligibility. Filtering inside the
            // grouping would incorrectly let successors overtake a delayed or dead-letter head.
            List<String> ids=jdbc.queryForList("SELECT o.id FROM (SELECT w.workspace_id,MIN(w.sequence_no) AS sequence_no FROM workspace_event w JOIN outbox_event pending ON pending.id=w.id WHERE pending.published_at IS NULL GROUP BY w.workspace_id) head JOIN workspace_event current_event ON current_event.workspace_id=head.workspace_id AND current_event.sequence_no=head.sequence_no JOIN outbox_event o ON o.id=current_event.id WHERE o.published_at IS NULL AND o.attempts<5 AND (o.next_attempt_at IS NULL OR o.next_attempt_at<=?) ORDER BY o.created_at,o.id LIMIT 1",
                String.class,Timestamp.from(clock.instant()));
            if(ids.isEmpty()) break;
            String id=ids.get(0);
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> deliver(UUID.fromString(id))))) completed++;
            } catch (RuntimeException failure) {
                // Failure details can contain private code/SQL. Persist only a safe category.
                tx.executeWithoutResult(status -> jdbc.update("UPDATE outbox_event SET attempts=attempts+1,next_attempt_at=?,last_error_code='CONSUMER_FAILED' WHERE id=? AND published_at IS NULL AND attempts<5",
                    Timestamp.from(clock.instant().plusSeconds(10)), id));
            }
        }
        return completed;
    }
    private boolean deliver(UUID eventId) {
        // User FK checks participate in locking too: match producers' user -> workspace order.
        // Ownership is immutable; this first lookup is deliberately non-locking.
        List<Map<String,Object>> preview=jdbc.queryForList("SELECT o.aggregate_id,w.owner_id FROM outbox_event o JOIN learning_workspace w ON w.id=o.aggregate_id WHERE o.id=?",eventId.toString());
        if (preview.isEmpty()) return false;
        String workspace=preview.get(0).get("aggregate_id").toString();
        String owner=preview.get(0).get("owner_id").toString();
        jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",owner);
        jdbc.queryForList("SELECT id FROM learning_workspace WHERE id=? FOR UPDATE",workspace);
        var rows=jdbc.query("SELECT event_type,payload,published_at,attempts,next_attempt_at FROM outbox_event WHERE id=? FOR UPDATE",
            (rs,n)->new Delivery(rs.getString(1),rs.getString(2),rs.getTimestamp(3),rs.getInt(4),rs.getTimestamp(5)),eventId.toString());
        if (rows.isEmpty() || rows.get(0).publishedAt() != null) return false;
        var row=rows.get(0);
        Timestamp nextAttempt=row.nextAttemptAt();
        if(row.attempts()>=5 || nextAttempt!=null && nextAttempt.toInstant().isAfter(clock.instant())) return false;
        var envelope=json.tree(row.payload());
        // Process workspace streams in order, even when timestamps tie or a predecessor is retrying.
        long sequence=envelope.path("sequence").asLong();
        Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event w JOIN outbox_event o ON o.id=w.id WHERE w.workspace_id=? AND w.sequence_no<? AND o.published_at IS NULL",Integer.class,workspace,sequence);
        if (pending != null && pending>0) return false;
        for (var consumer:consumers) {
            if (!consumer.accepts(row.type())) continue;
            Integer seen=jdbc.queryForObject("SELECT COUNT(*) FROM event_receipt WHERE consumer=? AND event_id=?",Integer.class,consumer.name(),eventId.toString());
            if (seen != null && seen>0) continue;
            consumer.accept(envelope);
            jdbc.update("INSERT INTO event_receipt(consumer,event_id,handled_at) VALUES(?,?,?)",consumer.name(),eventId.toString(),Timestamp.from(clock.instant()));
        }
        jdbc.update("UPDATE outbox_event SET published_at=?,last_error_code=NULL WHERE id=?",Timestamp.from(clock.instant()),eventId.toString());
        return true;
    }
    /** Maintenance entry point; deliberately not exposed as a learner HTTP endpoint. */
    public void retryDeadLetter(UUID eventId) {
        jdbc.update("UPDATE outbox_event SET attempts=0,next_attempt_at=NULL,last_error_code=NULL WHERE id=? AND published_at IS NULL AND attempts>=5",eventId.toString());
    }
}
