package io.typeforge.operations.events;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes the public ordered journal and the durable outbox in the SAME business transaction. */
@Service
public class EventJournal {
    public record Event(UUID id, UUID workspaceId, long sequence, int revision, String type,
                        String origin, Instant occurredAt, JsonNode data) {}
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final Clock clock;
    public EventJournal(JdbcTemplate jdbc, JsonCodec json, Clock clock) {
        this.jdbc = jdbc; this.json = json; this.clock = clock;
    }
    public UUID append(UUID workspaceId, String type, String origin, Object payload) {
        return append(UUID.randomUUID(), workspaceId, type, origin, payload);
    }
    public UUID append(UUID id, UUID workspaceId, String type, String origin, Object payload) {
        requireTransaction();
        if (!type.matches("[A-Z][A-Z0-9_.]{0,63}") || !List.of("CLIENT", "SERVER", "AGENT").contains(origin)) {
            throw DomainException.invalid("Unsupported semantic event");
        }
        String data = json.write(payload);
        if (data.length() > 32768) throw DomainException.invalid("Event payload too large");
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT revision,event_sequence FROM learning_workspace WHERE id=? FOR UPDATE", workspaceId.toString());
        if (rows.isEmpty()) throw DomainException.missing();
        int revision = ((Number) rows.get(0).get("revision")).intValue() + 1;
        long sequence = ((Number) rows.get(0).get("event_sequence")).longValue() + 1;
        if (revision <= 0 || sequence > 9_007_199_254_740_991L) throw DomainException.conflict("Workspace sequence exhausted");
        Instant now = clock.instant();
        var envelope = Map.of("schemaVersion", 1, "eventId", id, "workspaceId", workspaceId,
            "sequence", sequence, "revision", revision, "type", type, "origin", origin,
            "occurredAt", now.toString(), "data", json.tree(data));
        String body = json.write(envelope);
        jdbc.update("INSERT INTO workspace_event(id,workspace_id,sequence_no,event_type,origin,payload,created_at) VALUES(?,?,?,?,?,?,?)",
            id.toString(), workspaceId.toString(), sequence, type, origin, body, Timestamp.from(now));
        jdbc.update("INSERT INTO outbox_event(id,aggregate_id,event_type,payload,created_at) VALUES(?,?,?,?,?)",
            id.toString(), workspaceId.toString(), type, body, Timestamp.from(now));
        jdbc.update("UPDATE learning_workspace SET revision=?,event_sequence=? WHERE id=?", revision, sequence, workspaceId.toString());
        return id;
    }
    public List<Event> read(UUID workspaceId, long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 100) throw DomainException.invalid("Invalid event cursor or page size");
        return jdbc.query("SELECT id,sequence_no,event_type,origin,payload,created_at FROM workspace_event WHERE workspace_id=? AND sequence_no>? ORDER BY sequence_no LIMIT ?",
            (rs,n) -> {
                JsonNode payload = json.tree(rs.getString("payload"));
                return new Event(UUID.fromString(rs.getString("id")), workspaceId, rs.getLong("sequence_no"),
                    payload.path("revision").asInt(), rs.getString("event_type"), rs.getString("origin"),
                    rs.getTimestamp("created_at").toInstant(), payload.path("data"));
            }, workspaceId.toString(), afterSequence, limit);
    }
    public static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Domain mutation requires an active database transaction");
        }
    }
}
