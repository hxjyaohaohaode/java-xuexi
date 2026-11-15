package io.typeforge.learning;

import io.typeforge.shared.JsonCodec;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Backfill only existing server reading facts. Each bounded batch resumes by immutable UUID, never manufactures progress. */
@Component
public class ReadingIndexInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public ReadingIndexInitializer(JdbcTemplate jdbc,JsonCodec json) {this.jdbc=jdbc;this.json=json;}
    @Override public void run(ApplicationArguments arguments) {
        String after="";
        while(true) {
            var rows=jdbc.queryForList("SELECT e.id,e.user_id,e.release_id,e.concept_id,e.event_type,e.payload,e.occurred_at FROM learning_event e WHERE e.event_type IN ('READ_STARTED','READ_COMPLETED') AND e.id>? AND NOT EXISTS (SELECT 1 FROM workspace_reading wr WHERE wr.learning_event_id=e.id) ORDER BY e.id LIMIT 200",after);
            if(rows.isEmpty()) return;
            for(var row:rows) {
                after=row.get("id").toString();String workspace;
                try { workspace=UUID.fromString(json.tree(row.get("payload")).path("workspaceId").asText()).toString(); }
                catch(IllegalArgumentException invalid) { continue; }
                try {
                    jdbc.update("INSERT INTO workspace_reading(workspace_id,learning_event_id,concept_id,event_type,occurred_at) SELECT ?,?,?,?,? FROM learning_workspace w WHERE w.id=? AND w.owner_id=? AND w.release_id=?",workspace,row.get("id"),row.get("concept_id"),row.get("event_type"),row.get("occurred_at"),workspace,row.get("user_id"),row.get("release_id"));
                } catch(DuplicateKeyException raced) { /* Another starting node indexed the same immutable fact. */ }
            }
        }
    }
}
