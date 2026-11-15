package io.typeforge.lab;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.compiler.CompileService;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LabService {
    public record Session(UUID id,UUID workspaceId,UUID releaseId,String verdict,String ruleCode,String explanation,String rulesVersion,UUID jobId,Boolean predictionCorrect,JsonNode input,Instant createdAt) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final FiniteTypeEvaluator evaluator;
    private final EventJournal events;
    private final SchemaCatalog schemas;
    private final JsonCodec json;
    private final Clock clock;
    private final CompileService compiler;
    public LabService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,FiniteTypeEvaluator evaluator,EventJournal events,SchemaCatalog schemas,JsonCodec json,Clock clock,CompileService compiler) {this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.evaluator=evaluator;this.events=events;this.schemas=schemas;this.json=json;this.clock=clock;this.compiler=compiler;}
    public Session create(UUID owner,JsonNode input,String key) {
        schemas.validate("LabInput",input);WorkspaceService.requireKey(key);
        UUID workspace=UUID.fromString(input.path("workspaceId").asText());
        UUID release=UUID.fromString(input.path("releaseId").asText());
        String type=input.path("labType").asText();
        var evaluation=switch(type) {
            case "ASSIGNMENT"->evaluator.assignment(input.path("sourceType").asText(),input.path("targetType").asText());
            case "READ_WRITE"->evaluator.readWrite(input.path("sourceType").asText(),input.path("targetType").asText(),input.path("operation").asText());
            default->FiniteTypeEvaluator.unsupported();
        };
        return tx.execute(status->{
            var scope=workspaces.lockOwned(owner,workspace);
            if(!scope.releaseId().equals(release)) throw DomainException.invalid("Lab must use the workspace's fixed release");
            String hash=json.hash(input);UUID previous=workspaces.replay(owner,"createLab",key,hash);
            if(previous!=null) return get(owner,previous);
            boolean code=input.has("source");
            UUID job=code?compiler.create(owner,workspace,release,input.path("source").asText(),"COMPILE_ONLY",json.hash(Map.of("owner",owner,"operation","labCompile","key",key))).id():null;
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO lab_session(id,user_id,release_id,lab_type,input,rules_version,evaluation,job_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",id.toString(),owner.toString(),release.toString(),type,json.write(input),code?"javac17-strict-v1":FiniteTypeEvaluator.VERSION,json.write(evaluation),job==null?null:job.toString(),Timestamp.from(clock.instant()));
            jdbc.update("INSERT INTO workspace_lab(workspace_id,lab_id) VALUES(?,?)",workspace.toString(),id.toString());
            events.append(workspace,code?"LAB_COMPILE_QUEUED":"TYPE_RELATION_EVALUATED","SERVER",Map.of("labId",id,"labType",type,"verdict",code?"QUEUED":evaluation.verdict().name(),"rulesVersion",code?"javac17-strict-v1":evaluation.rulesVersion(),"evidenceClass","EXPLORATION_NOT_MASTERY"));
            workspaces.remember(owner,"createLab",key,hash,id);
            return get(owner,id);
        });
    }
    public Session get(UUID owner,UUID id) {
        var rows=jdbc.query("SELECT l.*,wl.workspace_id FROM lab_session l JOIN workspace_lab wl ON wl.lab_id=l.id JOIN learning_workspace w ON w.id=wl.workspace_id WHERE l.id=? AND l.user_id=? AND w.owner_id=?",(rs,n)->{
            JsonNode input=json.tree(rs.getString("input")),value=json.tree(rs.getString("evaluation"));
            String verdict=value.path("verdict").asText();
            UUID jobId=rs.getString("job_id")==null?null:UUID.fromString(rs.getString("job_id"));
            String rule=value.path("ruleCode").asText(),explanation=value.path("explanation").asText();
            if(jobId!=null) {
                var job=compiler.get(owner,jobId);
                verdict=switch(job.status()){case "SUCCEEDED"->"ALLOWED";case "COMPILE_ERROR"->"DENIED";case "QUEUED","RUNNING","CANCELLED"->job.status();default->"UNAVAILABLE";};
                rule="JAVAC17_COMPILE_ONLY";
                explanation="结果来自独立编译任务，采用 Java 17、禁用注解处理、全部lint与警告即失败策略；只核验本份源码的编译结果，不执行代码，也不把探索记作掌握成绩。";
            }
            Boolean correct=!java.util.Set.of("ALLOWED","DENIED").contains(verdict) || input.path("prediction").asText().equals("UNKNOWN")?null:verdict.equals(input.path("prediction").asText());
            return new Session(id,UUID.fromString(rs.getString("workspace_id")),UUID.fromString(rs.getString("release_id")),verdict,rule,explanation,rs.getString("rules_version"),jobId,correct,input,rs.getTimestamp("created_at").toInstant());
        },id.toString(),owner.toString(),owner.toString());
        if(rows.isEmpty()) throw DomainException.missing();
        workspaces.requireOwned(owner,rows.get(0).workspaceId());return rows.get(0);
    }
    public List<Session> list(UUID owner,UUID workspace) {
        workspaces.requireOwned(owner,workspace);
        return jdbc.queryForList("SELECT l.id FROM lab_session l JOIN workspace_lab wl ON wl.lab_id=l.id WHERE wl.workspace_id=? AND l.user_id=? ORDER BY l.created_at DESC,l.id LIMIT 50",String.class,workspace.toString(),owner.toString()).stream().map(id->get(owner,UUID.fromString(id))).toList();
    }
}
