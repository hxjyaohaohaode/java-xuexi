package io.typeforge.operations.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class ActivityProjection implements DomainEventConsumer {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public ActivityProjection(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    @Override public String name() { return "workspace-activity-v1"; }
    @Override public boolean accepts(String type) { return true; }
    @Override public void accept(JsonNode event) {
        String id=event.path("workspaceId").asText();
        long sequence=event.path("sequence").asLong();
        var rows=jdbc.queryForList("SELECT processed_sequence FROM workspace_projection WHERE workspace_id=? FOR UPDATE",id);
        if (rows.isEmpty()) {
            if (sequence != 1) throw new IllegalStateException("Projection requires ordered replay");
            jdbc.update("INSERT INTO workspace_projection(workspace_id,processed_sequence,activity_count,updated_at) VALUES(?,?,1,?)",id,sequence,Timestamp.from(clock.instant()));
        } else {
            long previous=((Number) rows.get(0).get("processed_sequence")).longValue();
            if (sequence<=previous) return;
            if (sequence!=previous+1) throw new IllegalStateException("Projection sequence gap");
            jdbc.update("UPDATE workspace_projection SET processed_sequence=?,activity_count=activity_count+1,updated_at=? WHERE workspace_id=?",sequence,Timestamp.from(clock.instant()),id);
        }
    }
}
