package io.typeforge.learning;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReadingService {
    public record Receipt(UUID id,UUID workspaceId,UUID conceptId,String eventType,Instant occurredAt,String evidenceClass) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final EventJournal events;
    private final SchemaCatalog schemas;
    private final JsonCodec json;
    private final Clock clock;
    public ReadingService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,EventJournal events,SchemaCatalog schemas,JsonCodec json,Clock clock) {this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.events=events;this.schemas=schemas;this.json=json;this.clock=clock;}
    public Receipt record(UUID owner,JsonNode input,String key) {
        schemas.validate("LearningEventInput",input);WorkspaceService.requireKey(key);
        UUID workspace=UUID.fromString(input.path("workspaceId").asText()),release=UUID.fromString(input.path("releaseId").asText()),concept=UUID.fromString(input.path("conceptId").asText());
        return tx.execute(status->{
            var scope=workspaces.lockOwned(owner,workspace);
            if(!scope.releaseId().equals(release)) throw DomainException.invalid("Reading uses the workspace's fixed release");
            UUID previous=workspaces.replay(owner,"recordReading",key,json.hash(input));
            if(previous!=null) return receipt(owner,previous);
            if(jdbc.queryForList("SELECT concept_id FROM release_concept WHERE release_id=? AND concept_id=?",release.toString(),concept.toString()).isEmpty()) throw DomainException.missing();
            UUID id=UUID.randomUUID();String event=input.path("eventType").asText();Instant now=clock.instant();
            jdbc.update("INSERT INTO learning_event(id,user_id,release_id,concept_id,event_type,payload,occurred_at) VALUES(?,?,?,?,?,?,?)",id.toString(),owner.toString(),release.toString(),concept.toString(),event,json.write(Map.of("workspaceId",workspace,"basis","USER_REPORTED","evidenceClass","ACTIVITY_NOT_MASTERY")),Timestamp.from(now));
            events.append(workspace,event,"CLIENT",Map.of("learningEventId",id,"conceptId",concept,"evidenceClass","ACTIVITY_NOT_MASTERY"));
            workspaces.remember(owner,"recordReading",key,json.hash(input),id);return receipt(owner,id);
        });
    }
    private Receipt receipt(UUID owner,UUID id) {return jdbc.queryForObject("SELECT * FROM learning_event WHERE id=? AND user_id=?",(rs,n)->new Receipt(id,UUID.fromString(json.tree(rs.getString("payload")).path("workspaceId").asText()),UUID.fromString(rs.getString("concept_id")),rs.getString("event_type"),rs.getTimestamp("occurred_at").toInstant(),"ACTIVITY_NOT_MASTERY"),id.toString(),owner.toString());}
}
