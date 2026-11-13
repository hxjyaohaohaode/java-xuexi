package io.typeforge.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Objective choice grading. Code grading deliberately remains unavailable without an isolated worker. */
@Service
public class LearningService {
    public record Attempt(UUID id,UUID workspaceId,UUID releaseId,UUID exerciseId,int exerciseRevision,String status,
                          int hintLevel,BigDecimal score,BigDecimal maximum,UUID jobId,String feedback,JsonNode answer,
                          Instant createdAt,String graderVersion,List<String> misconceptionCodes,Reference evidenceRef) {}
    public record ProgressItem(UUID conceptId,int independentSamples,BigDecimal weightedCorrect,BigDecimal weightedTotal,String evidenceStatus,String algorithmVersion) {}
    public record Progress(List<ProgressItem> items,Instant updatedAt,boolean rebuilding) {}
    public record Review(UUID id,UUID conceptId,UUID originAttemptId,Instant dueAt,String reason,String status) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final EventJournal events;
    private final JsonCodec json;
    private final SchemaCatalog schemas;
    private final Clock clock;
    public LearningService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,EventJournal events,JsonCodec json,SchemaCatalog schemas,Clock clock) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.events=events;this.json=json;this.schemas=schemas;this.clock=clock;
    }
    public Attempt submit(UUID owner,UUID workspace,UUID release,UUID exercise,int revision,JsonNode answer,String key) {
        WorkspaceService.requireKey(key); schemas.validate("Answer",answer);
        if(revision<1) throw DomainException.invalid("Invalid exercise revision");
        if(!answer.path("kind").asText().equals("CHOICE")) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"Only objective choice grading is configured; user code requires an isolated compiler worker");
        return tx.execute(status->{
            if(jdbc.queryForList("SELECT id FROM app_user WHERE id=? AND status='ACTIVE' FOR UPDATE",owner.toString()).isEmpty()) throw DomainException.missing();
            var scope=workspaces.lockOwned(owner,workspace);
            if(!scope.releaseId().equals(release)) throw DomainException.invalid("Attempt must use the workspace's fixed release");
            String hash=json.hash(Map.of("workspaceId",workspace,"releaseId",release,"exerciseId",exercise,"revision",revision,"answer",answer));
            UUID previous=workspaces.replay(owner,"submitAttempt",key,hash);
            if(previous!=null) return get(owner,previous);
            var rows=jdbc.query("SELECT e.answer_spec,e.kind FROM exercise e JOIN release_exercise re ON re.exercise_id=e.id AND re.exercise_revision=e.revision JOIN content_release r ON r.id=re.release_id WHERE e.id=? AND e.revision=? AND re.release_id=? AND e.status='REVIEWED' AND r.status='PUBLISHED'",(rs,n)->Map.of("spec",json.tree(rs.getString(1)),"kind",rs.getString(2)),exercise.toString(),revision,release.toString());
            if(rows.isEmpty()) throw DomainException.missing();
            String kind=rows.get(0).get("kind").toString();
            if(!Set.of("SINGLE","MULTIPLE","COMPILE_PREDICTION","READING","DIAGNOSIS").contains(kind)) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"The grader for this exercise is not configured");
            JsonNode spec=(JsonNode)rows.get(0).get("spec");
            var expected=new HashSet<String>();spec.path("optionIds").forEach(x->expected.add(x.asText()));
            var offered=new HashSet<String>();spec.path("options").forEach(x->offered.add(x.path("id").asText()));
            var chosen=new HashSet<String>();answer.path("optionIds").forEach(x->chosen.add(x.asText()));
            if(expected.isEmpty() || !offered.containsAll(expected)) throw new DomainException("INVALID_PUBLISHED_EXERCISE",503,"Exercise requires editorial repair; no learning result was recorded");
            if(!offered.containsAll(chosen) || kind.equals("SINGLE") && chosen.size()!=1) throw DomainException.invalid("Answer contains invalid choices");
            boolean correct=expected.equals(chosen);
            // The first attempt in a family is independent. Later attempts follow exposed feedback.
            Integer seen=jdbc.queryForObject("SELECT COUNT(*) FROM exercise_attempt a JOIN exercise old ON old.id=a.exercise_id AND old.revision=a.exercise_revision JOIN exercise current ON current.family_id=old.family_id WHERE a.user_id=? AND current.id=? AND current.revision=? AND a.status='GRADED'",Integer.class,owner.toString(),exercise.toString(),revision);
            int hintLevel=seen!=null && seen>0?4:0;
            BigDecimal score=correct?new BigDecimal("100.00"):new BigDecimal("0.00");
            var feedback=Map.of("explanation",spec.path("explanation").asText(),"misconceptions",correct?json.tree("[]"):spec.path("misconceptions"),"correct",correct,"evidenceClass",hintLevel==0?"INDEPENDENT_FIRST_ATTEMPT":"PRACTICE_AFTER_FEEDBACK");
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO exercise_attempt(id,user_id,release_id,exercise_id,exercise_revision,answer,status,hint_level,score,maximum,grader_version,feedback,created_at) VALUES(?,?,?,?,?,?,'GRADED',?,?,100,'choice-exact-v1',?,?)",id.toString(),owner.toString(),release.toString(),exercise.toString(),revision,json.write(answer),hintLevel,score,json.write(feedback),Timestamp.from(clock.instant()));
            jdbc.update("INSERT INTO workspace_attempt(workspace_id,attempt_id) VALUES(?,?)",workspace.toString(),id.toString());
            events.append(workspace,"ATTEMPT_GRADED","SERVER",Map.of("attemptId",id,"releaseId",release,"exerciseId",exercise,"exerciseRevision",revision));
            workspaces.remember(owner,"submitAttempt",key,hash,id);
            return get(owner,id);
        });
    }
    public Attempt get(UUID owner,UUID id) {
        var rows=jdbc.query("SELECT a.*,wa.workspace_id FROM exercise_attempt a JOIN workspace_attempt wa ON wa.attempt_id=a.id JOIN learning_workspace w ON w.id=wa.workspace_id WHERE a.id=? AND a.user_id=? AND w.owner_id=?",this::attempt,id.toString(),owner.toString(),owner.toString());
        if(rows.isEmpty()) throw DomainException.missing();
        workspaces.requireOwned(owner,rows.get(0).workspaceId());
        return rows.get(0);
    }
    private Attempt attempt(ResultSet rs,int row) throws SQLException {
        UUID id=UUID.fromString(rs.getString("id"));
        JsonNode feedback=json.tree(rs.getString("feedback")); JsonNode answer=json.tree(rs.getString("answer"));
        BigDecimal score=rs.getBigDecimal("score");
        String hash=json.hash(Map.of("answer",answer,"feedback",feedback,"score",score));
        List<String> misconceptions=List.of(json.read(feedback.path("misconceptions").toString(),String[].class));
        return new Attempt(id,UUID.fromString(rs.getString("workspace_id")),UUID.fromString(rs.getString("release_id")),UUID.fromString(rs.getString("exercise_id")),rs.getInt("exercise_revision"),rs.getString("status"),rs.getInt("hint_level"),score,rs.getBigDecimal("maximum"),null,feedback.path("explanation").asText(),answer,rs.getTimestamp("created_at").toInstant(),rs.getString("grader_version"),misconceptions,new Reference("ATTEMPT",id,1,hash));
    }
    public Progress progress(UUID owner) {
        List<ProgressItem> items=jdbc.query("SELECT * FROM learner_concept WHERE user_id=? ORDER BY concept_id LIMIT 100",(rs,n)->new ProgressItem(UUID.fromString(rs.getString("concept_id")),rs.getInt("independent_samples"),rs.getBigDecimal("weighted_correct"),rs.getBigDecimal("weighted_total"),rs.getInt("independent_samples")<3?"INSUFFICIENT":"AVAILABLE",rs.getString("algorithm_version")),owner.toString());
        Timestamp updated=jdbc.queryForObject("SELECT MAX(updated_at) FROM learner_concept WHERE user_id=?",Timestamp.class,owner.toString());
        Long pending=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event o JOIN learning_workspace w ON w.id=o.aggregate_id WHERE w.owner_id=? AND o.event_type='ATTEMPT_GRADED' AND o.published_at IS NULL",Long.class,owner.toString());
        return new Progress(items,updated==null?null:updated.toInstant(),pending!=null && pending>0);
    }
    public List<Review> reviews(UUID owner) {
        return jdbc.query("SELECT * FROM review_item WHERE user_id=? ORDER BY due_at,id LIMIT 100",(rs,n)->new Review(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("concept_id")),UUID.fromString(rs.getString("origin_attempt_id")),rs.getTimestamp("due_at").toInstant(),rs.getString("reason"),rs.getString("status")),owner.toString());
    }
    public void dismissReview(UUID owner,UUID id) {
        // Completion is derived from later independent evidence, never a client success flag.
        tx.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT wa.workspace_id FROM review_item r JOIN workspace_attempt wa ON wa.attempt_id=r.origin_attempt_id WHERE r.id=? AND r.user_id=?",String.class,id.toString(),owner.toString());
            if(rows.isEmpty()) throw DomainException.missing();
            UUID workspace=UUID.fromString(rows.get(0)); workspaces.lockOwned(owner,workspace);
            int changed=jdbc.update("UPDATE review_item SET status='DISMISSED' WHERE id=? AND user_id=? AND status='PENDING'",id.toString(),owner.toString());
            if(changed>0) events.append(workspace,"REVIEW_DISMISSED","CLIENT",Map.of("reviewId",id));
        });
    }
}
