package io.typeforge.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.compiler.CodeRubric;
import io.typeforge.compiler.CompileService;
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

/** Choice grading and asynchronous compile-contract grading share immutable attempts and learning evidence. */
@Service
public class LearningService {
    public record Attempt(UUID id,UUID workspaceId,UUID releaseId,UUID exerciseId,int exerciseRevision,String status,
                          int hintLevel,BigDecimal score,BigDecimal maximum,UUID jobId,String feedback,JsonNode answer,
                          Instant createdAt,String graderVersion,List<String> misconceptionCodes,Reference evidenceRef) {}
    public record ProgressItem(UUID conceptId,int independentSamples,BigDecimal weightedCorrect,BigDecimal weightedTotal,String evidenceStatus,String algorithmVersion) {}
    public record Progress(List<ProgressItem> items,Instant updatedAt,boolean rebuilding) {}
    public record Review(UUID id,UUID conceptId,UUID originAttemptId,Instant dueAt,String reason,String status) {}
    public record Hint(UUID id,UUID workspaceId,UUID exerciseId,int exerciseRevision,int level,String text,String evidenceClass) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final EventJournal events;
    private final JsonCodec json;
    private final SchemaCatalog schemas;
    private final Clock clock;
    private final CompileService compiler;
    public LearningService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,EventJournal events,JsonCodec json,SchemaCatalog schemas,Clock clock,CompileService compiler) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.events=events;this.json=json;this.schemas=schemas;this.clock=clock;this.compiler=compiler;
    }
    public Attempt submit(UUID owner,UUID workspace,UUID release,UUID exercise,int revision,JsonNode answer,String key) {
        WorkspaceService.requireKey(key); schemas.validate("Answer",answer);
        if(revision<1) throw DomainException.invalid("Invalid exercise revision");
        if(answer.path("kind").asText().equals("TEXT")) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"Free text cannot receive an objective score without a reviewed rubric");
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
            JsonNode spec=(JsonNode)rows.get(0).get("spec");
            if(answer.path("kind").asText().equals("CODE")) {
                if(!Set.of("SIGNATURE","REFACTOR","FILL").contains(kind)) throw DomainException.invalid("This exercise accepts an objective selection, not source code");
                var checks=CodeRubric.parse(spec);
                UUID id=UUID.randomUUID();int hintLevel=assistanceLevel(owner,exercise,revision);
                jdbc.update("INSERT INTO exercise_attempt(id,user_id,release_id,exercise_id,exercise_revision,answer,status,hint_level,grader_version,feedback,created_at) VALUES(?,?,?,?,?,?,'PENDING',?,?,?,?)",id.toString(),owner.toString(),release.toString(),exercise.toString(),revision,json.write(answer),hintLevel,CodeRubric.VERSION,json.write(Map.of("explanation","正在等待独立编译检查；编译通过本身不会判为答对。","misconceptions",List.of())),Timestamp.from(clock.instant()));
                jdbc.update("INSERT INTO workspace_attempt(workspace_id,attempt_id) VALUES(?,?)",workspace.toString(),id.toString());
                compiler.createForAttempt(owner,workspace,release,answer.path("source").asText(),id,checks,json.hash(Map.of("owner",owner,"operation","attemptCompile","key",key)));
                workspaces.remember(owner,"submitAttempt",key,hash,id);return get(owner,id);
            }
            if(!Set.of("SINGLE","MULTIPLE","COMPILE_PREDICTION","READING","DIAGNOSIS").contains(kind)) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"The grader for this exercise is not configured");
            var expected=new HashSet<String>();spec.path("optionIds").forEach(x->expected.add(x.asText()));
            var offered=new HashSet<String>();spec.path("options").forEach(x->offered.add(x.path("id").asText()));
            var chosen=new HashSet<String>();answer.path("optionIds").forEach(x->chosen.add(x.asText()));
            if(expected.isEmpty() || !offered.containsAll(expected)) throw new DomainException("INVALID_PUBLISHED_EXERCISE",503,"Exercise requires editorial repair; no learning result was recorded");
            if(!offered.containsAll(chosen) || kind.equals("SINGLE") && chosen.size()!=1) throw DomainException.invalid("Answer contains invalid choices");
            boolean correct=expected.equals(chosen);
            // The first attempt in a family is independent. Later attempts follow exposed feedback.
            int hintLevel=assistanceLevel(owner,exercise,revision);
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
        var rows=jdbc.query("SELECT a.*,wa.workspace_id,c.id AS compile_job_id FROM exercise_attempt a JOIN workspace_attempt wa ON wa.attempt_id=a.id JOIN learning_workspace w ON w.id=wa.workspace_id LEFT JOIN compile_job c ON c.attempt_id=a.id WHERE a.id=? AND a.user_id=? AND w.owner_id=?",this::attempt,id.toString(),owner.toString(),owner.toString());
        if(rows.isEmpty()) throw DomainException.missing();
        workspaces.requireOwned(owner,rows.get(0).workspaceId());
        return rows.get(0);
    }
    public List<Attempt> list(UUID owner,UUID workspace) {
        workspaces.requireOwned(owner,workspace);
        return jdbc.query("SELECT a.*,wa.workspace_id,c.id AS compile_job_id FROM exercise_attempt a JOIN workspace_attempt wa ON wa.attempt_id=a.id LEFT JOIN compile_job c ON c.attempt_id=a.id WHERE a.user_id=? AND wa.workspace_id=? ORDER BY a.created_at DESC,a.id LIMIT 100",this::attempt,owner.toString(),workspace.toString());
    }
    private Attempt attempt(ResultSet rs,int row) throws SQLException {
        UUID id=UUID.fromString(rs.getString("id"));
        JsonNode feedback=rs.getString("feedback")==null?json.tree("{}"):json.tree(rs.getString("feedback")); JsonNode answer=json.tree(rs.getString("answer"));
        BigDecimal score=rs.getBigDecimal("score");
        Reference reference=score!=null && rs.getString("status").equals("GRADED")?new Reference("ATTEMPT",id,1,json.hash(Map.of("answer",answer,"feedback",feedback,"score",score))):null;
        List<String> misconceptions=feedback.path("misconceptions").isArray()?List.of(json.read(feedback.path("misconceptions").toString(),String[].class)):List.of();
        String job=rs.getString("compile_job_id");
        return new Attempt(id,UUID.fromString(rs.getString("workspace_id")),UUID.fromString(rs.getString("release_id")),UUID.fromString(rs.getString("exercise_id")),rs.getInt("exercise_revision"),rs.getString("status"),rs.getInt("hint_level"),score,rs.getBigDecimal("maximum"),job==null?null:UUID.fromString(job),feedback.path("explanation").asText(),answer,rs.getTimestamp("created_at").toInstant(),rs.getString("grader_version"),misconceptions,reference);
    }
    private int assistanceLevel(UUID owner,UUID exercise,int revision) {
        Integer seen=jdbc.queryForObject("SELECT COUNT(*) FROM exercise_attempt a JOIN exercise old ON old.id=a.exercise_id AND old.revision=a.exercise_revision JOIN exercise current ON current.family_id=old.family_id WHERE a.user_id=? AND current.id=? AND current.revision=? AND a.status IN ('PENDING','GRADING','GRADED')",Integer.class,owner.toString(),exercise.toString(),revision);
        if(seen!=null && seen>0)return 4;
        Integer hinted=jdbc.queryForObject("SELECT MAX(h.hint_level) FROM exercise_hint_receipt h JOIN exercise old ON old.id=h.exercise_id AND old.revision=h.exercise_revision JOIN exercise current ON current.family_id=old.family_id WHERE h.user_id=? AND current.id=? AND current.revision=?",Integer.class,owner.toString(),exercise.toString(),revision);
        return hinted==null?0:hinted;
    }
    public Hint hint(UUID owner,UUID workspace,UUID release,UUID exercise,int revision,int level,String key) {
        WorkspaceService.requireKey(key);
        if(level<1 || level>3 || revision<1)throw DomainException.invalid("Hint level must be 1..3 and exercise revision must be positive");
        return tx.execute(status->{
            var scope=workspaces.lockOwned(owner,workspace);if(!scope.releaseId().equals(release))throw DomainException.invalid("Hint must use its workspace release");
            String hash=json.hash(Map.of("workspace",workspace,"release",release,"exercise",exercise,"revision",revision,"level",level));
            UUID previous=workspaces.replay(owner,"requestExerciseHint",key,hash);
            if(previous!=null)return hintReceipt(owner,previous);
            var rows=jdbc.queryForList("SELECT e.answer_spec FROM exercise e JOIN release_exercise re ON re.exercise_id=e.id AND re.exercise_revision=e.revision JOIN content_release r ON r.id=re.release_id WHERE re.release_id=? AND e.id=? AND e.revision=? AND e.status='REVIEWED' AND r.status='PUBLISHED'",String.class,release.toString(),exercise.toString(),revision);
            if(rows.isEmpty())throw DomainException.missing();JsonNode spec=json.tree(rows.get(0));
            String text;
            if(spec.path("hints").isArray() && spec.path("hints").size()>=level)text=spec.path("hints").get(level-1).asText();
            else text=switch(level){case 1->"先把题目中的源类型、目标类型和操作方向分别写下来；不要把元素类型的继承直接当成容器类型的继承。";case 2->"逐项说明哪些类型信息是确定的，哪些只是上界或下界。分别检查读取与写入约束，再回到题目判断。";default->"为你的判断各构造一个应当成立的正例和一个不应成立的反例。可在类型实验室检验受支持的关系，或使用已配置的独立编译器核对源码。";};
            if(text.isBlank() || text.length()>4000)throw new DomainException("INVALID_PUBLISHED_EXERCISE",503,"The authored hint requires editorial repair");
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO exercise_hint_receipt(id,user_id,workspace_id,exercise_id,exercise_revision,hint_level,hint_text,created_at) VALUES(?,?,?,?,?,?,?,?)",id.toString(),owner.toString(),workspace.toString(),exercise.toString(),revision,level,text,Timestamp.from(clock.instant()));
            events.append(workspace,"EXERCISE_HINT_REVEALED","SERVER",Map.of("hintId",id,"exerciseId",exercise,"exerciseRevision",revision,"hintLevel",level,"evidenceClass","ASSISTED_PRACTICE"));
            workspaces.remember(owner,"requestExerciseHint",key,hash,id);return hintReceipt(owner,id);
        });
    }
    private Hint hintReceipt(UUID owner,UUID id) {
        return jdbc.queryForObject("SELECT * FROM exercise_hint_receipt WHERE id=? AND user_id=?",(rs,n)->new Hint(id,UUID.fromString(rs.getString("workspace_id")),UUID.fromString(rs.getString("exercise_id")),rs.getInt("exercise_revision"),rs.getInt("hint_level"),rs.getString("hint_text"),"ASSISTED_PRACTICE"),id.toString(),owner.toString());
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
