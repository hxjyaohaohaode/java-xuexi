package io.typeforge.notebook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Version-scoped interaction state and explicit, source-backed notebook activity construction. */
@Service
public final class NotebookInteractionService implements io.typeforge.operations.RuntimeCapabilityProvider {
    public record ContentRef(UUID id,int revision) {}
    public record Navigation(UUID artifactId,int revision,String kind,String title) {}
    public record BlockState(String blockId,int stateRevision,JsonNode typeInput,ContentRef selectedExerciseRef,int hintLevel,NotebookActions.Result lastResult) {}
    public record State(UUID artifactId,int artifactRevision,List<BlockState> blocks) {}
    public record Options(UUID artifactId,int artifactRevision,String originBlockId,List<String> availableKinds,JsonNode typeFlowDefaults,
                          List<CurriculumService.ExerciseView> counterfactualCandidates,List<Navigation> artifactCandidates,List<UUID> reviewIds) {}
    public record Context(ArtifactService.Artifact artifact,ArtifactService.Version version,UUID releaseId) {}
    private static final Set<String> ACTIVITY_KINDS=Set.of("TYPE_FLOW","REPAIR_DIFF","COUNTERFACTUAL","EXPERT_HELP","REVIEW_LINKS");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final ArtifactService artifacts;
    private final CurriculumService curriculum;
    private final JsonCodec json;
    private final SchemaCatalog schemas;
    private final Clock clock;
    @Override public Set<String> enabledCapabilities() {return Set.of("NOTEBOOK_INTERACTIONS");}
    public NotebookInteractionService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,ArtifactService artifacts,CurriculumService curriculum,JsonCodec json,SchemaCatalog schemas,Clock clock) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.artifacts=artifacts;this.curriculum=curriculum;this.json=json;this.schemas=schemas;this.clock=clock;
    }
    public Context lockCurrent(UUID owner,UUID id,int revision) {
        var initial=artifacts.get(owner,id);var scope=workspaces.lockOwned(owner,initial.workspaceId());var artifact=artifacts.get(owner,id);
        if(!Set.of("NOTEBOOK","INTERACTION").contains(artifact.kind()))throw DomainException.invalid("A notebook or interaction artifact is required");
        if(revision<1 || artifact.currentRevision()!=revision)throw DomainException.conflict("Notebook changed; keep the current response and refresh the source version");
        return new Context(artifact,artifacts.validateCurrentInputs(owner,id,revision),scope.releaseId());
    }
    public static JsonNode block(Context context,String id) {
        for(JsonNode block:context.version().content().path("blocks"))if(block.path("id").asText().equals(id))return block;
        throw DomainException.missing();
    }
    public State state(UUID owner,UUID id,int revision) {
        return tx.execute(status->{
            Context context=lockCurrent(owner,id,revision);
            Map<String,BlockState> saved=new HashMap<>();
            jdbc.query("SELECT block_key,state_json FROM notebook_block_state WHERE artifact_id=? AND artifact_revision=?",rs->{saved.put(rs.getString(1),json.read(rs.getString(2),BlockState.class));},id.toString(),revision);
            var blocks=new ArrayList<BlockState>();for(JsonNode block:context.version().content().path("blocks")){String key=block.path("id").asText();blocks.add(saved.getOrDefault(key,emptyState(key)));}
            return new State(id,revision,List.copyOf(blocks));
        });
    }
    public BlockState load(Context context,String blockId) {
        var rows=jdbc.query("SELECT revision,state_json FROM notebook_block_state WHERE artifact_id=? AND artifact_revision=? AND block_key=?",(rs,n)->{
            BlockState state=json.read(rs.getString(2),BlockState.class);
            if(state.stateRevision()!=rs.getInt(1) || !state.blockId().equals(blockId))throw new DomainException("NOTEBOOK_STATE_INTEGRITY",503,"Notebook state needs repair");return state;
        },context.artifact().id().toString(),context.version().revision(),blockId);
        return rows.isEmpty()?emptyState(blockId):rows.get(0);
    }
    private static BlockState emptyState(String id){return new BlockState(id,0,null,null,0,null);}
    public void store(Context context,BlockState previous,JsonNode typeInput,ContentRef selected,int hintLevel,NotebookActions.Result result) {
        if(previous.stateRevision()==Integer.MAX_VALUE)throw DomainException.conflict("Interaction state revision exhausted; append a new notebook source revision");
        var next=new BlockState(previous.blockId(),previous.stateRevision()+1,typeInput,selected,hintLevel,result);
        String value=json.write(next);
        if(value.length()>65536)throw DomainException.invalid("Notebook state exceeds its bounded size");
        if(previous.stateRevision()==0)jdbc.update("INSERT INTO notebook_block_state(artifact_id,artifact_revision,block_key,revision,state_json,updated_at) VALUES(?,?,?,?,?,?)",context.artifact().id().toString(),context.version().revision(),previous.blockId(),1,value,Timestamp.from(clock.instant()));
        else if(jdbc.update("UPDATE notebook_block_state SET revision=?,state_json=?,updated_at=? WHERE artifact_id=? AND artifact_revision=? AND block_key=? AND revision=?",next.stateRevision(),value,Timestamp.from(clock.instant()),context.artifact().id().toString(),context.version().revision(),previous.blockId(),previous.stateRevision())!=1)throw DomainException.conflict("Notebook interaction changed");
    }
    public void admitNewAction(Context context) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM notebook_action_receipt WHERE artifact_id=? AND artifact_revision=?",Integer.class,context.artifact().id().toString(),context.version().revision());
        if(count!=null && count>=512)throw new DomainException("NOTEBOOK_ACTION_LIMIT",429,"This source revision has reached 512 saved actions; save a new revision to continue");
    }
    public Options options(UUID owner,UUID id,int revision,String originBlockId) {
        return tx.execute(status->options(owner,lockCurrent(owner,id,revision),originBlockId));
    }
    private Options options(UUID owner,Context context,String originBlockId) {
        JsonNode origin=block(context,originBlockId);
        if(!origin.hasNonNull("exerciseRef") || origin.path("kind").asText().equals("EXPERT_HELP"))throw DomainException.invalid("Select a source exercise block for an activity");
        var ref=contentRef(origin.path("exerciseRef"));
        var base=curriculum.exercise(context.releaseId(),ref.id(),ref.revision());
        var candidates=jdbc.query("SELECT e.id,e.revision FROM exercise e JOIN release_exercise re ON re.exercise_id=e.id AND re.exercise_revision=e.revision JOIN exercise base ON base.id=? AND base.revision=? WHERE re.release_id=? AND e.status='REVIEWED' AND e.family_id=base.family_id AND e.id<>base.id ORDER BY e.difficulty,e.id LIMIT 20",
            (rs,n)->curriculum.exercise(context.releaseId(),UUID.fromString(rs.getString(1)),rs.getInt(2)),ref.id().toString(),ref.revision(),context.releaseId().toString());
        var linked=new ArrayList<Navigation>();
        for(var artifact:artifacts.list(owner,context.artifact().workspaceId())) {
            if(linked.size()>=20)break;
            if(artifact.id().equals(context.artifact().id()) || artifact.currentRevision()<1)continue;
            try{artifacts.validateCurrentInputs(owner,artifact.id(),artifact.currentRevision());linked.add(new Navigation(artifact.id(),artifact.currentRevision(),artifact.kind(),artifact.title()));}
            catch(DomainException stale){if(stale.status()!=409 && stale.status()!=404)throw stale;}
        }
        List<UUID> reviews=origin.hasNonNull("attemptId")?jdbc.query("SELECT id FROM review_item WHERE user_id=? AND origin_attempt_id=? ORDER BY due_at,id LIMIT 20",(rs,n)->UUID.fromString(rs.getString(1)),owner.toString(),origin.path("attemptId").asText()):List.of();
        var kinds=new ArrayList<>(List.of("TYPE_FLOW","EXPERT_HELP"));
        if(base.answerKind().equals("CODE") && !origin.path("config").path("originalSource").asText().isBlank())kinds.add("REPAIR_DIFF");
        if(!candidates.isEmpty())kinds.add("COUNTERFACTUAL");
        if(!reviews.isEmpty() || !linked.isEmpty())kinds.add("REVIEW_LINKS");
        return new Options(context.artifact().id(),context.version().revision(),originBlockId,List.copyOf(kinds),typeDefaults(),List.copyOf(candidates),List.copyOf(linked),reviews);
    }
    private JsonNode typeDefaults() {
        return json.tree(json.write(Map.of("sourceType","List<Integer>","targetType","List<Number>",
            "parameters",List.of(Map.of("name","sourceType","candidates",List.of("List<Integer>","List<Number>","ArrayList<Integer>","List<? extends Number>","List<? super Integer>")),
                Map.of("name","targetType","candidates",List.of("List<Number>","List<? extends Number>","List<? super Integer>","Integer","Number","Object","null"))),
            "operations",List.of("ASSIGN","READ","WRITE"))));
    }
    public ArtifactService.Version append(UUID owner,UUID id,JsonNode input,String key) {
        schemas.validate("NotebookBlockInput",input);WorkspaceService.requireKey(key);
        return tx.execute(status->{
            var artifact=artifacts.get(owner,id);workspaces.lockOwned(owner,artifact.workspaceId());
            String hash=json.hash(Map.of("artifactId",id,"input",input));UUID previous=workspaces.replay(owner,"appendNotebookBlock",key,hash);
            if(previous!=null){Integer revision=jdbc.queryForObject("SELECT artifact_revision FROM notebook_action_receipt WHERE id=? AND artifact_id=? AND action_key='appendBlock'",Integer.class,previous.toString(),id.toString());return artifacts.version(owner,id,revision);}
            Context context=lockCurrent(owner,id,input.path("artifactRevision").asInt());
            if(context.version().content().path("blocks").size()>=100)throw DomainException.invalid("A notebook may contain at most 100 blocks");
            String kind=input.path("kind").asText();if(!ACTIVITY_KINDS.contains(kind))throw DomainException.invalid("Unsupported activity kind");
            JsonNode origin=block(context,input.path("originBlockId").asText());Options choices=options(owner,context,origin.path("id").asText());
            if(!choices.availableKinds().contains(kind))throw new DomainException("ACTIVITY_SOURCE_UNAVAILABLE",422,"This exercise has no admitted source for the requested activity");
            JsonNode config=input.path("config");if(config.isMissingNode() || config.isNull())config=null;
            switch(kind) {
                case "TYPE_FLOW"->{if(config==null)config=choices.typeFlowDefaults();}
                case "REPAIR_DIFF"->{if(config!=null)throw DomainException.invalid("Repair source is derived from the original attempt");String source=origin.path("config").path("originalSource").asText();ObjectNode value=(ObjectNode)json.tree(json.write(Map.of("originalSource",source,"studentDraft",source,"sourceHash",json.sha256(source))));value.putNull("solutionRef");config=value;}
                case "EXPERT_HELP"->{if(config!=null)throw DomainException.invalid("Guidance context is derived from the chosen block");ObjectNode value=(ObjectNode)json.tree(json.write(Map.of("contextBlockId",origin.path("id").asText(),"initialHintLevel",0)));value.putNull("conversationId");config=value;}
                case "COUNTERFACTUAL"->{if(config==null)throw DomainException.invalid("Select published same-family variants and declare the comparison dimension");}
                case "REVIEW_LINKS"->{if(config==null)config=json.tree(json.write(Map.of("reviewIds",choices.reviewIds(),"artifactRefs",List.of(),"conceptIds",origin.path("conceptIds"))));if(config.path("reviewIds").isEmpty() && config.path("artifactRefs").isEmpty())throw DomainException.invalid("Select at least one actual review or source artifact");}
                default->throw DomainException.invalid("Unsupported notebook activity");
            }
            var newBlock=(ObjectNode)origin.deepCopy();String blockId="activity-"+UUID.randomUUID();newBlock.put("id",blockId);newBlock.put("kind",kind);newBlock.put("revision",1);newBlock.set("config",config);
            if(kind.equals("REVIEW_LINKS"))for(JsonNode review:config.path("reviewIds"))if(choices.reviewIds().stream().noneMatch(idValue->idValue.toString().equals(review.asText())))throw DomainException.invalid("A review link must belong to the selected source attempt");
            newBlock.put("prompt",switch(kind){case "TYPE_FLOW"->"在固定教学类型范围内选择引用与操作，再查看服务端推演。探索不计掌握分。";case "REPAIR_DIFF"->"保留原始代码，修改当前版本后分别检查两份源码。编译成功不等于通过原题。";case "COUNTERFACTUAL"->"选择已审核的同题族对照题再作答。比较维度由你指定，系统不自动证明只改变一个维度；结果按同题族复练记录。";case "EXPERT_HELP"->"针对来源题目的分级引导；使用已编写或确定性提示，不会自动调用模型。";default->"打开明确选取的本人作品或查看复习来源；导航和阅读不增加掌握样本。";});
            List<String> actions=switch(kind){case "TYPE_FLOW"->List.of("predict","selectType","setParameter","openEvidence");case "REPAIR_DIFF"->List.of("predict","requestCompile","submitAnswer","requestHint","openEvidence");case "COUNTERFACTUAL"->List.of("predict","setParameter","submitAnswer","requestHint","openEvidence");case "EXPERT_HELP"->List.of("requestHint","openEvidence");default->List.of("navigateArtifact","openEvidence");};
            newBlock.set("allowedActions",json.tree(json.write(actions)));
            var inputs=new LinkedHashMap<String,Reference>();for(Reference ref:context.version().inputRefs())inputs.put(ref.kind()+":"+ref.id(),ref);
            if(kind.equals("REVIEW_LINKS"))for(JsonNode ref:config.path("artifactRefs")) {
                var target=contentRef(ref);var owned=artifacts.get(owner,target.id());
                if(!owned.workspaceId().equals(artifact.workspaceId()) || owned.currentRevision()!=target.revision())throw DomainException.missing();
                var source=artifacts.validateCurrentInputs(owner,target.id(),target.revision());
                Reference admitted=new Reference("ARTIFACT",target.id(),target.revision(),source.contentHash());Reference prior=inputs.putIfAbsent("ARTIFACT:"+target.id(),admitted);
                if(prior!=null && !prior.equals(admitted))throw DomainException.conflict("An artifact link conflicts with an existing input version");
            }
            ObjectNode content=(ObjectNode)context.version().content().deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)content.path("blocks")).add(newBlock);
            var saved=artifacts.save(owner,id,context.version().revision(),content,List.copyOf(inputs.values()));UUID receipt=UUID.randomUUID();
            jdbc.update("INSERT INTO notebook_action_receipt(id,artifact_id,artifact_revision,block_key,action_key,result_json) VALUES(?,?,?,?,'appendBlock',?)",receipt.toString(),id.toString(),saved.revision(),blockId,json.write(Map.of("revision",saved.revision(),"blockId",blockId)));
            workspaces.remember(owner,"appendNotebookBlock",key,hash,receipt);return saved;
        });
    }
    public static ContentRef contentRef(JsonNode value) {return new ContentRef(UUID.fromString(value.path("id").asText()),value.path("revision").asInt());}
}
