package io.typeforge.notebook;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.compiler.CompileService;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.exercise.LearningService;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Interactions dispatch to server-owned learning services; the browser never assigns a score. */
@Service
public class NotebookActions {
    public record EvidenceRef(UUID id,int revision) {}
    public record Result(String status,UUID attemptId,UUID compileJobId,UUID tutorRequestId,String message,List<EvidenceRef> evidenceRefs,UUID eventId,int artifactRevision,String blockId) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final ArtifactService artifacts;
    private final LearningService learning;
    private final EventJournal events;
    private final SchemaCatalog schemas;
    private final JsonCodec json;
    private final CompileService compiler;
    public NotebookActions(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,ArtifactService artifacts,LearningService learning,EventJournal events,SchemaCatalog schemas,JsonCodec json,CompileService compiler) {this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.artifacts=artifacts;this.learning=learning;this.events=events;this.schemas=schemas;this.json=json;this.compiler=compiler;}
    public Result dispatch(UUID owner,UUID artifactId,JsonNode input,String key) {
        schemas.validate("NotebookAction",input);WorkspaceService.requireKey(key);
        return tx.execute(status->{
            var artifact=artifacts.get(owner,artifactId);var workspace=workspaces.lockOwned(owner,artifact.workspaceId());
            if(!Set.of("NOTEBOOK","INTERACTION").contains(artifact.kind())) throw DomainException.invalid("Artifact is not an interaction notebook");
            String hash=json.hash(Map.of("artifactId",artifactId,"action",input));
            UUID previous=workspaces.replay(owner,"dispatchNotebookAction",key,hash);
            if(previous!=null) return jdbc.queryForObject("SELECT result_json FROM notebook_action_receipt WHERE id=?",(rs,n)->json.read(rs.getString(1),Result.class),previous.toString());
            int revision=input.path("artifactRevision").asInt();
            if(artifacts.get(owner,artifactId).currentRevision()!=revision) throw DomainException.conflict("Notebook changed; refresh and keep the unfinished answer");
            var version=artifacts.validateCurrentInputs(owner,artifactId,revision);
            JsonNode block=null;
            for(JsonNode candidate:version.content().path("blocks")) if(candidate.path("id").asText().equals(input.path("blockId").asText())) block=candidate;
            if(block==null) throw DomainException.missing();
            if(block.path("revision").asInt()!=input.path("expectedBlockRevision").asInt()) throw DomainException.conflict("Notebook block changed");
            String action=input.path("action").asText();boolean admitted=false;
            for(JsonNode allowed:block.path("allowedActions")) if(allowed.asText().equals(action)) admitted=true;
            if(!admitted) throw new DomainException("ACTION_NOT_ALLOWED",422,"This block does not permit the requested action");
            if(!Set.of("predict","openEvidence","submitAnswer","requestCompile","requestHint").contains(action)) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"This action requires an adapter not available in the current runtime");
            UUID attemptId=null,compileJobId=null;String completion="COMPLETED",hintText=null;
            if(action.equals("submitAnswer")) {
                if(!input.hasNonNull("answer") || !block.hasNonNull("exerciseRef")) throw DomainException.invalid("A real exercise and answer are required");
                JsonNode exercise=block.path("exerciseRef");
                var attempt=learning.submit(owner,workspace.id(),workspace.releaseId(),UUID.fromString(exercise.path("id").asText()),exercise.path("revision").asInt(),input.path("answer"),json.hash(Map.of("operation","notebook-submit","owner",owner,"key",key)));
                attemptId=attempt.id();compileJobId=attempt.jobId();if(!attempt.status().equals("GRADED"))completion="ACCEPTED";
            } else if(action.equals("requestCompile")) {
                if(!input.path("answer").path("kind").asText().equals("CODE"))throw DomainException.invalid("Compilation requires the learner's current CODE source");
                compileJobId=compiler.create(owner,workspace.id(),workspace.releaseId(),input.path("answer").path("source").asText(),"COMPILE_ONLY",json.hash(Map.of("operation","notebook-compile","owner",owner,"key",key))).id();completion="ACCEPTED";
            } else if(input.hasNonNull("answer")) throw DomainException.invalid("Only submission and compilation accept an answer");
            if(action.equals("requestHint")) {
                if(!block.hasNonNull("exerciseRef"))throw DomainException.invalid("This block has no reviewed exercise hint source");
                JsonNode exercise=block.path("exerciseRef");
                hintText=learning.hint(owner,workspace.id(),workspace.releaseId(),UUID.fromString(exercise.path("id").asText()),exercise.path("revision").asInt(),input.path("hintLevel").asInt(1),json.hash(Map.of("operation","notebook-hint","owner",owner,"key",key))).text();
            }
            if(action.equals("predict") && input.path("selection").asText().isBlank()) throw DomainException.invalid("A prediction must contain the learner's own selection or explanation");
            List<EvidenceRef> citations=action.equals("openEvidence")?List.of(json.read(block.path("evidenceRefs").toString(),EvidenceRef[].class)):List.of();
            UUID id=UUID.randomUUID();
            UUID eventId=events.append(workspace.id(),"NOTEBOOK_ACTION_RECORDED","CLIENT",Map.of("actionId",id,"artifactId",artifactId,"artifactRevision",revision,"blockId",block.path("id").asText(),"action",action,"attemptId",attemptId==null?"":attemptId,"selection",input.path("selection").asText(),"evidenceClass",action.equals("submitAnswer") && completion.equals("COMPLETED")?"SERVER_GRADED_ATTEMPT":"EXPLORATION_NOT_MASTERY"));
            String message=switch(action) {case "submitAnswer"->completion.equals("COMPLETED")?"已由练习服务完成真实判分；重复题目家族不会增加独立掌握样本。":"作答已入队；检查完成后会更新真实成绩，编译成功本身不代表答对。";case "requestCompile"->"已提交独立编译任务；请查看任务结果，探索不会计入掌握成绩。";case "requestHint"->hintText;case "openEvidence"->"仅返回当前工作空间课程版本中的已授权知识引用。";default->"已记录你的预测；预测不会被当作独立掌握证据。";};
            var result=new Result(completion,attemptId,compileJobId,null,message,citations,eventId,revision,block.path("id").asText());
            jdbc.update("INSERT INTO notebook_action_receipt(id,artifact_id,artifact_revision,block_key,action_key,result_json) VALUES(?,?,?,?,?,?)",id.toString(),artifactId.toString(),revision,result.blockId(),action,json.write(result));
            workspaces.remember(owner,"dispatchNotebookAction",key,hash,id);
            return result;
        });
    }
}
