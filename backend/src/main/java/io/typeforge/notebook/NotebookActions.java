package io.typeforge.notebook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.typeforge.compiler.CompileService;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.exercise.LearningService;
import io.typeforge.lab.LabService;
import io.typeforge.notebook.NotebookInteractionService.ContentRef;
import io.typeforge.notebook.NotebookInteractionService.Navigation;
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

/** Every notebook interaction reaches an existing domain service; no block grades itself. */
@Service
public class NotebookActions {
    public record EvidenceRef(UUID id,int revision) {}
    public record Result(String status,UUID attemptId,UUID compileJobId,UUID tutorRequestId,String message,List<EvidenceRef> evidenceRefs,
                         UUID eventId,int artifactRevision,String blockId,int stateRevision,LabService.Session labSession,
                         CurriculumService.ExerciseView selectedExercise,Navigation navigation,UUID baselineCompileJobId) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final LearningService learning;
    private final EventJournal events;
    private final SchemaCatalog schemas;
    private final JsonCodec json;
    private final CompileService compiler;
    private final LabService labs;
    private final CurriculumService curriculum;
    private final NotebookInteractionService interactions;
    public NotebookActions(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,LearningService learning,EventJournal events,SchemaCatalog schemas,JsonCodec json,CompileService compiler,LabService labs,CurriculumService curriculum,NotebookInteractionService interactions) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.learning=learning;this.events=events;this.schemas=schemas;this.json=json;this.compiler=compiler;this.labs=labs;this.curriculum=curriculum;this.interactions=interactions;
    }
    public Result dispatch(UUID owner,UUID artifactId,JsonNode input,String key) {
        schemas.validate("NotebookAction",input);WorkspaceService.requireKey(key);
        return tx.execute(status->{
            var context=interactions.lockCurrent(owner,artifactId,input.path("artifactRevision").asInt());
            UUID workspace=context.artifact().workspaceId(),release=context.releaseId();String blockId=input.path("blockId").asText();
            String hash=json.hash(Map.of("artifactId",artifactId,"action",input));
            UUID previous=workspaces.replay(owner,"dispatchNotebookAction",key,hash);
            if(previous!=null)return jdbc.queryForObject("SELECT result_json FROM notebook_action_receipt WHERE id=?",(rs,n)->json.read(rs.getString(1),Result.class),previous.toString());
            JsonNode block=NotebookInteractionService.block(context,blockId);String kind=block.path("kind").asText(),action=input.path("action").asText();
            if(block.path("revision").asInt()!=input.path("expectedBlockRevision").asInt())throw DomainException.conflict("Notebook block changed");
            if(!has(block.path("allowedActions"),action))throw new DomainException("ACTION_NOT_ALLOWED",422,"This block does not permit the requested action");
            var state=interactions.load(context,blockId);
            boolean needsState=Set.of("selectType","setParameter").contains(action) || kind.equals("COUNTERFACTUAL") && Set.of("submitAnswer","requestHint").contains(action);
            if(needsState && !input.has("expectedStateRevision"))throw DomainException.invalid("This activity requires its current interaction state revision");
            if(input.has("expectedStateRevision") && input.path("expectedStateRevision").asInt()!=state.stateRevision())throw DomainException.conflict("The selected activity state changed; keep your input and reload the current selection");
            if(state.stateRevision()==Integer.MAX_VALUE)throw DomainException.conflict("Interaction state revision exhausted");
            interactions.admitNewAction(context);
            if(input.hasNonNull("answer") && !Set.of("submitAnswer","requestCompile").contains(action))throw DomainException.invalid("Only submission and compilation accept an answer");
            rejectExtra(input,"typeInput",action.equals("selectType") && kind.equals("TYPE_FLOW"));
            rejectExtra(input,"parameterInput",action.equals("setParameter") && kind.equals("TYPE_FLOW"));
            rejectExtra(input,"exerciseRef",action.equals("setParameter") && kind.equals("COUNTERFACTUAL"));
            rejectExtra(input,"artifactRef",action.equals("navigateArtifact") && kind.equals("REVIEW_LINKS"));
            UUID attemptId=null,jobId=null,baselineJob=null;String completion="COMPLETED",message;
            LabService.Session lab=null;CurriculumService.ExerciseView selected=null;Navigation navigation=null;
            JsonNode typeInput=state.typeInput();ContentRef selection=state.selectedExerciseRef();int hintLevel=state.hintLevel();
            List<EvidenceRef> citations=List.of();
            switch(action) {
                case "predict"->{
                    if(input.path("selection").asText().isBlank())throw DomainException.invalid("A prediction must contain the learner's own selection or explanation");
                    message="已记录你的预测；预测不会被当作独立掌握证据。";
                }
                case "openEvidence"->{citations=List.of(json.read(block.path("evidenceRefs").toString(),EvidenceRef[].class));message="返回当前工作空间课程版本与已准入来源中的知识引用。";}
                case "requestHint"->{
                    ContentRef exercise=exercise(block,selection);int level=input.path("hintLevel").asInt(1);
                    var hint=learning.hint(owner,workspace,release,exercise.id(),exercise.revision(),level,derived("notebook-hint",owner,key));
                    message=hint.text();hintLevel=Math.max(hintLevel,level);
                }
                case "submitAnswer"->{
                    if(!input.hasNonNull("answer"))throw DomainException.invalid("A current learner answer is required");
                    ContentRef exercise=exercise(block,selection);
                    var attempt=learning.submitPractice(owner,workspace,release,exercise.id(),exercise.revision(),input.path("answer"),derived("notebook-submit",owner,key));
                    attemptId=attempt.id();jobId=attempt.jobId();if(!attempt.status().equals("GRADED"))completion="ACCEPTED";
                    message=completion.equals("COMPLETED")?"已由练习服务完成真实判分；错题上下文中的作答保留为辅助复练，不增加独立掌握样本。":"复练已入队；以题目专属检查完成后的真实成绩为准，编译成功本身不代表答对。";
                }
                case "requestCompile"->{
                    if(!input.path("answer").path("kind").asText().equals("CODE"))throw DomainException.invalid("Compilation requires the learner's current CODE source");
                    if(kind.equals("REPAIR_DIFF"))baselineJob=compiler.create(owner,workspace,release,block.path("config").path("originalSource").asText(),"COMPILE_ONLY",derived("notebook-baseline-compile",owner,key)).id();
                    jobId=compiler.create(owner,workspace,release,input.path("answer").path("source").asText(),"COMPILE_ONLY",derived("notebook-compile",owner,key)).id();completion="ACCEPTED";
                    message=baselineJob==null?"已提交独立编译任务；请查看真实任务结果，探索不会计入掌握成绩。":"原代码与当前代码已分别提交独立编译任务；两份结果分别核对，不会把编译通过直接当成题目答对。";
                }
                case "selectType"->{
                    if(!kind.equals("TYPE_FLOW") || !input.hasNonNull("typeInput"))throw DomainException.invalid("Select a complete allowed type-flow input");
                    typeInput=input.path("typeInput");validateTypeInput(block.path("config"),typeInput);
                    lab=typeLab(owner,workspace,release,typeInput,key);message=lab.explanation();
                }
                case "setParameter"->{
                    if(kind.equals("TYPE_FLOW")) {
                        JsonNode parameter=input.path("parameterInput");String name=parameter.path("name").asText();
                        if(!parameter.isObject() || !Set.of("sourceType","targetType").contains(name) || !candidate(block.path("config"),name,parameter.path("value").asText(),true))throw DomainException.invalid("Parameter is not an allowed candidate in this source block");
                        ObjectNode next=(ObjectNode)(typeInput==null?json.tree(json.write(Map.of("sourceType",block.path("config").path("sourceType").asText(),"targetType",block.path("config").path("targetType").asText()))):typeInput.deepCopy());
                        next.put(name,parameter.path("value").asText());next.set("operation",parameter.path("operation"));next.set("prediction",parameter.path("prediction"));
                        validateTypeInput(block.path("config"),next);typeInput=next;lab=typeLab(owner,workspace,release,next,key);message=lab.explanation();
                    } else if(kind.equals("COUNTERFACTUAL")) {
                        if(!input.hasNonNull("exerciseRef"))throw DomainException.invalid("Select an admitted reviewed variant");
                        JsonNode chosen=input.path("exerciseRef");boolean allowed=false;for(JsonNode ref:block.path("config").path("candidateExerciseRefs"))if(ref.equals(chosen))allowed=true;
                        if(!allowed)throw new DomainException("ACTION_NOT_ALLOWED",422,"Variant is outside this notebook's fixed candidate list");
                        selection=NotebookInteractionService.contentRef(chosen);selected=curriculum.exercise(release,selection.id(),selection.revision());
                        hintLevel=learning.revealedHintLevel(owner,workspace,selection.id(),selection.revision());
                        message="已载入该发布版本中经过审核的同题族对照题；作答仍由原题目判分器处理，并记录为辅助复练。";
                    } else throw new DomainException("ACTION_NOT_ALLOWED",422,"This block has no admitted parameter selection");
                }
                case "navigateArtifact"->{
                    if(!kind.equals("REVIEW_LINKS") || !input.hasNonNull("artifactRef"))throw DomainException.invalid("Select an explicitly linked artifact revision");
                    JsonNode chosen=input.path("artifactRef");boolean allowed=false;for(JsonNode ref:block.path("config").path("artifactRefs"))if(ref.equals(chosen))allowed=true;
                    if(!allowed)throw new DomainException("ACTION_NOT_ALLOWED",422,"Artifact is outside this block's admitted links");
                    ContentRef ref=NotebookInteractionService.contentRef(chosen);
                    var rows=jdbc.query("SELECT kind,title,current_revision FROM learning_artifact WHERE id=? AND workspace_id=?",(rs,n)->new Navigation(ref.id(),rs.getInt(3),rs.getString(1),rs.getString(2)),ref.id().toString(),workspace.toString());
                    if(rows.size()!=1 || rows.get(0).revision()!=ref.revision())throw DomainException.conflict("Linked source artifact changed; rebuild the notebook link");
                    navigation=rows.get(0);message="已核对本人作品、固定修订和当前来源；可以打开所选作品。";
                }
                default->throw new DomainException("ACTION_NOT_ALLOWED",422,"Unsupported notebook action");
            }
            UUID id=UUID.randomUUID();UUID eventId=events.append(workspace,"NOTEBOOK_ACTION_RECORDED","CLIENT",Map.of("actionId",id,"artifactId",artifactId,"artifactRevision",context.version().revision(),"blockId",blockId,"action",action,"attemptId",attemptId==null?"":attemptId,"selection",input.path("selection").asText(),"evidenceClass",action.equals("submitAnswer") && completion.equals("COMPLETED")?"SERVER_GRADED_ATTEMPT":"EXPLORATION_NOT_MASTERY"));
            var result=new Result(completion,attemptId,jobId,null,message,citations,eventId,context.version().revision(),blockId,state.stateRevision()+1,lab,selected,navigation,baselineJob);
            interactions.store(context,state,typeInput,selection,hintLevel,result);
            jdbc.update("INSERT INTO notebook_action_receipt(id,artifact_id,artifact_revision,block_key,action_key,result_json) VALUES(?,?,?,?,?,?)",id.toString(),artifactId.toString(),context.version().revision(),blockId,action,json.write(result));
            workspaces.remember(owner,"dispatchNotebookAction",key,hash,id);return result;
        });
    }
    private String derived(String operation,UUID owner,String key){return json.hash(Map.of("operation",operation,"owner",owner,"key",key));}
    private static boolean has(JsonNode items,String value){for(JsonNode item:items)if(item.asText().equals(value))return true;return false;}
    private static void rejectExtra(JsonNode input,String field,boolean allowed){if(input.has(field) && !allowed)throw DomainException.invalid("Action does not accept "+field);}
    private static ContentRef exercise(JsonNode block,ContentRef selected) {
        if(block.path("kind").asText().equals("COUNTERFACTUAL")){if(selected==null)throw DomainException.conflict("Choose a reviewed variant before requesting help or answering");return selected;}
        if(!block.hasNonNull("exerciseRef"))throw DomainException.invalid("This block has no reviewed exercise source");return NotebookInteractionService.contentRef(block.path("exerciseRef"));
    }
    private static boolean candidate(JsonNode config,String name,String value,boolean declared) {
        if(!declared && config.path(name).asText().equals(value))return true;
        for(JsonNode parameter:config.path("parameters"))if(parameter.path("name").asText().equals(name) && has(parameter.path("candidates"),value))return true;return false;
    }
    private static void validateTypeInput(JsonNode config,JsonNode input) {
        if(!candidate(config,"sourceType",input.path("sourceType").asText(),false) || !candidate(config,"targetType",input.path("targetType").asText(),false) || !has(config.path("operations"),input.path("operation").asText()))
            throw new DomainException("ACTION_NOT_ALLOWED",422,"The selected types or operation are outside this source block's finite candidates");
    }
    private LabService.Session typeLab(UUID owner,UUID workspace,UUID release,JsonNode value,String key) {
        ObjectNode input=(ObjectNode)json.tree(json.write(Map.of("workspaceId",workspace,"releaseId",release,"labType",value.path("operation").asText().equals("ASSIGN")?"ASSIGNMENT":"READ_WRITE","sourceType",value.path("sourceType").asText(),"targetType",value.path("targetType").asText(),"prediction",value.path("prediction").asText())));
        if(!value.path("operation").asText().equals("ASSIGN"))input.set("operation",value.path("operation"));
        return labs.create(owner,input,derived("notebook-type-lab",owner,key));
    }
}
