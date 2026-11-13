package io.typeforge.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ArtifactService implements ArtifactStudio<JsonNode> {
    public record Artifact(UUID id,UUID workspaceId,String kind,String title,int currentRevision) {}
    public record Version(UUID artifactId,int revision,String schemaVersion,JsonNode content,String contentHash,List<Reference> inputRefs,String reviewStatus) {}
    private static final Set<String> KINDS=Set.of("DOCUMENT","SLIDES","DIAGRAM","IMAGE","AUDIO","VIDEO","INTERACTION","NOTEBOOK");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final EventJournal events;
    private final ArtifactInvalidation invalidation;
    private final JsonCodec json;
    private final SchemaCatalog schemas;
    public ArtifactService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,EventJournal events,ArtifactInvalidation invalidation,JsonCodec json,SchemaCatalog schemas) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.events=events;this.invalidation=invalidation;this.json=json;this.schemas=schemas;
    }
    public Artifact create(UUID owner,UUID workspace,String kind,String title,UUID goal,String key) {
        WorkspaceService.requireText(title,255);WorkspaceService.requireKey(key);
        if(kind==null || !KINDS.contains(kind)) throw DomainException.invalid("Unsupported artifact kind");
        return tx.execute(status->{
            workspaces.lockOwned(owner,workspace);
            if(goal!=null) workspaces.goal(owner,workspace,goal);
            String hash=json.hash(Map.of("workspaceId",workspace,"kind",kind,"title",title,"goalId",goal==null?"":goal));
            UUID previous=workspaces.replay(owner,"createArtifact",key,hash);
            if(previous!=null) return get(owner,previous);
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO learning_artifact(id,workspace_id,goal_id,kind,title) VALUES(?,?,?,?,?)",id.toString(),workspace.toString(),goal==null?null:goal.toString(),kind,title);
            events.append(workspace,"ARTIFACT_CREATED","CLIENT",Map.of("artifactId",id,"kind",kind));
            workspaces.remember(owner,"createArtifact",key,hash,id);
            return get(owner,id);
        });
    }
    public Artifact get(UUID owner,UUID id) {
        UUID workspace=workspaces.workspaceFor(owner,"ARTIFACT",id);
        return jdbc.queryForObject("SELECT kind,title,current_revision FROM learning_artifact WHERE id=?",(rs,n)->new Artifact(id,workspace,rs.getString(1),rs.getString(2),rs.getInt(3)),id.toString());
    }
    public List<Artifact> list(UUID owner,UUID workspace) {
        workspaces.requireOwned(owner,workspace);
        return jdbc.query("SELECT id,kind,title,current_revision FROM learning_artifact WHERE workspace_id=? ORDER BY created_at DESC,id LIMIT 100",(rs,n)->new Artifact(UUID.fromString(rs.getString(1)),workspace,rs.getString(2),rs.getString(3),rs.getInt(4)),workspace.toString());
    }
    public Version save(UUID owner,UUID artifact,int baseRevision,JsonNode content,List<Reference> inputs) {
        if(baseRevision<0) throw DomainException.invalid("Invalid artifact base revision");
        schemas.validate("ArtifactDsl",content);
        return tx.execute(status->{
            UUID workspace=workspaces.workspaceFor(owner,"ARTIFACT",artifact);
            workspaces.lockOwned(owner,workspace);
            var rows=jdbc.queryForList("SELECT kind,current_revision FROM learning_artifact WHERE id=? FOR UPDATE",artifact.toString());
            String kind=rows.get(0).get("kind").toString();
            if(((Number)rows.get(0).get("current_revision")).intValue()!=baseRevision) throw DomainException.conflict("Artifact changed; keep your draft and refresh before merging");
            if(!kind.equals(content.path("kind").asText())) throw DomainException.invalid("Artifact kind does not match source DSL");
            if(inputs!=null && inputs.stream().anyMatch(ref->ref!=null && "SNAPSHOT".equals(ref.kind()))) {
                throw new DomainException("CAPABILITY_UNAVAILABLE",503,"Snapshot lineage expansion is not configured; use explicit typed source references");
            }
            workspaces.validateReferences(owner,workspace,inputs);
            List<Reference> lineage=expandLineage(workspace,artifact,inputs);
            for(int start=0;start<lineage.size();start+=64) workspaces.validateReferences(owner,workspace,lineage.subList(start,Math.min(start+64,lineage.size())));
            validateEmbeddedReferences(owner,workspace,workspaces.requireOwned(owner,workspace).releaseId(),content,inputs);
            int next=baseRevision+1;
            if(next<=0) throw DomainException.conflict("Artifact revision exhausted");
            invalidation.validateAdmission(workspace,artifact,next,lineage);
            // Human edits win: a previously held agent lease cannot commit over this new revision.
            jdbc.update("DELETE FROM artifact_write_lease WHERE artifact_id=?",artifact.toString());
            jdbc.update("INSERT INTO artifact_revision(artifact_id,revision,schema_version,content_json,content_hash,input_refs,review_status) VALUES(?,?,?,?,?,?,'DRAFT')",artifact.toString(),next,content.path("schemaVersion").asText(),json.write(content),json.hash(content),json.write(inputs));
            for(Reference ref:lineage) {
                jdbc.update("INSERT INTO artifact_input_reference(workspace_id,artifact_id,artifact_revision,source_kind,source_id,source_revision,source_hash) VALUES(?,?,?,?,?,?,?)",workspace.toString(),artifact.toString(),next,ref.kind(),ref.id().toString(),ref.revision(),ref.contentHash());
            }
            for(Reference ref:inputs) {
                if(ref.kind().equals("ARTIFACT")) jdbc.update("INSERT INTO artifact_dependency(workspace_id,source_id,source_revision,target_id,target_revision,dependency_kind,segment_refs) VALUES(?,?,?,?,?,'FACT','[]')",workspace.toString(),ref.id().toString(),ref.revision(),artifact.toString(),next);
            }
            jdbc.update("UPDATE learning_artifact SET current_revision=? WHERE id=?",next,artifact.toString());
            events.append(workspace,"ARTIFACT_REVISION_CREATED","CLIENT",Map.of("artifactId",artifact,"revision",next,"previousRevision",baseRevision));
            invalidation.invalidate(workspace,"ARTIFACT",artifact,next);
            return version(owner,artifact,next);
        });
    }
    private List<Reference> expandLineage(UUID workspace,UUID target,List<Reference> inputs) {
        var pending=new ArrayDeque<Reference>();var sources=new LinkedHashMap<String,Reference>();
        for(Reference ref:inputs) addSource(target,ref,sources,pending);
        while(!pending.isEmpty()) {
            Reference current=pending.remove();
            if(!current.kind().equals("ARTIFACT")) continue;
            var inherited=jdbc.query("SELECT source_kind,source_id,source_revision,source_hash FROM artifact_input_reference WHERE workspace_id=? AND artifact_id=? AND artifact_revision=? LIMIT 257",
                (rs,n)->new Reference(rs.getString(1),UUID.fromString(rs.getString(2)),rs.getInt(3),rs.getString(4)),workspace.toString(),current.id().toString(),current.revision());
            if(inherited.size()>ArtifactInvalidation.MAX_LINEAGE) throw new DomainException("LINEAGE_REBUILD_REQUIRED",409,"Legacy artifact has an oversized source index");
            for(Reference ref:inherited) addSource(target,ref,sources,pending);
        }
        return new ArrayList<>(sources.values());
    }
    private static void addSource(UUID target,Reference ref,Map<String,Reference> sources,ArrayDeque<Reference> pending) {
        if(ref.kind().equals("ARTIFACT") && ref.id().equals(target)) throw DomainException.invalid("An artifact revision cannot derive from itself or its own older versions");
        String key=ref.kind()+":"+ref.id();
        Reference previous=sources.putIfAbsent(key,ref);
        if(previous!=null && !previous.equals(ref)) throw new DomainException("LINEAGE_VERSION_CONFLICT",409,"Ancestor paths contain conflicting versions or hashes of the same source");
        if(sources.size()>ArtifactInvalidation.MAX_LINEAGE) throw new DomainException("LINEAGE_LIMIT",409,"A revision may depend on at most 256 distinct direct and transitive sources");
        if(previous==null) pending.add(ref);
    }
    private void validateEmbeddedReferences(UUID owner,UUID workspace,UUID release,JsonNode node,List<Reference> inputs) {
        if(node.isObject()) {
            // Citations are references too; a syntactically valid UUID cannot introduce unauthorized evidence.
            if(node.has("kind") && node.has("id") && node.has("revision") && node.has("contentHash")) {
                Reference embedded=json.read(node.toString(),Reference.class);
                if(!inputs.contains(embedded)) throw DomainException.invalid("Embedded citation is not in the admitted input manifest");
            }
            if(node.has("assetId") && !node.path("assetId").isNull()) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"Asset admission is not configured in this runtime");
            if(node.has("sourceHash") && node.has("originalSource") && !json.sha256(node.path("originalSource").asText()).equals(node.path("sourceHash").asText())) throw DomainException.invalid("Source hash does not match the exact UTF-8 source");
            if(node.has("diagnosticIds") && !node.path("diagnosticIds").isEmpty()) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"Compiler diagnostic admission is not configured in this runtime");
            if(node.has("solutionRef") && !node.path("solutionRef").isNull()) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"Hidden solution admission is not configured in this runtime");
            if(node.has("conversationId") && !node.path("conversationId").isNull()) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"Tutor conversation admission is not configured in this runtime");
            for(String field:List.of("evidenceRefs","factEvidenceRefs")) if(node.has(field)) {
                for(JsonNode citation:node.path(field)) {
                    if(inputs.stream().noneMatch(ref->ref.kind().equals("KNOWLEDGE_CHUNK") && ref.id().toString().equals(citation.path("id").asText()) && ref.revision()==citation.path("revision").asInt())) throw DomainException.invalid("Evidence citation is outside the admitted input manifest");
                }
            }
            if(node.has("attemptId") && !node.path("attemptId").isNull() && inputs.stream().noneMatch(ref->ref.kind().equals("ATTEMPT") && ref.id().toString().equals(node.path("attemptId").asText()))) throw DomainException.invalid("Notebook attempt is outside the admitted input manifest");
            if(node.has("conceptIds")) for(JsonNode concept:node.path("conceptIds")) {
                if(jdbc.queryForList("SELECT concept_id FROM release_concept WHERE release_id=? AND concept_id=?",release.toString(),concept.asText()).isEmpty()) throw DomainException.invalid("Concept is not in the workspace release");
            }
            for(String field:List.of("exerciseRef","baseExerciseRef")) if(node.has(field) && !node.path(field).isNull()) validateExerciseReference(release,node.path(field));
            if(node.has("candidateExerciseRefs")) for(JsonNode ref:node.path("candidateExerciseRefs")) validateExerciseReference(release,ref);
            if(node.has("artifactRefs")) for(JsonNode ref:node.path("artifactRefs")) {
                if(inputs.stream().noneMatch(input->input.kind().equals("ARTIFACT") && input.id().toString().equals(ref.path("id").asText()) && input.revision()==ref.path("revision").asInt())) throw DomainException.invalid("Linked artifact is outside the admitted input manifest");
            }
            if(node.has("reviewIds")) for(JsonNode id:node.path("reviewIds")) {
                if(jdbc.queryForList("SELECT r.id FROM review_item r JOIN workspace_attempt wa ON wa.attempt_id=r.origin_attempt_id WHERE r.id=? AND r.user_id=? AND wa.workspace_id=?",id.asText(),owner.toString(),workspace.toString()).isEmpty()) throw DomainException.invalid("Review link is outside this workspace");
            }
            if(node.has("attemptId") && !node.path("attemptId").isNull() && node.has("exerciseRef") && !node.path("exerciseRef").isNull()) {
                JsonNode exercise=node.path("exerciseRef");
                if(jdbc.queryForList("SELECT id FROM exercise_attempt WHERE id=? AND user_id=? AND exercise_id=? AND exercise_revision=?",node.path("attemptId").asText(),owner.toString(),exercise.path("id").asText(),exercise.path("revision").asInt()).isEmpty()) throw DomainException.invalid("Attempt and exercise links do not describe the same submitted version");
            }
            for(String field:List.of("blocks","nodes","slides","elements","segments","clips")) if(node.has(field)) {
                var ids=new java.util.HashSet<String>();
                for(JsonNode entry:node.path(field)) if(entry.has("id") && !ids.add(entry.path("id").asText())) throw DomainException.invalid("Duplicate DSL element identity");
            }
            if(node.has("nodes") && node.has("edges")) {
                var ids=new java.util.HashSet<String>();node.path("nodes").forEach(n->ids.add(n.path("id").asText()));
                for(JsonNode edge:node.path("edges")) if(!ids.contains(edge.path("from").asText()) || !ids.contains(edge.path("to").asText())) throw DomainException.invalid("Diagram edge has no matching node");
            }
            if(node.has("blocks")) for(JsonNode block:node.path("blocks")) {
                if(block.path("kind").asText().equals("EXPERT_HELP")) {
                    String target=block.path("config").path("contextBlockId").asText();
                    boolean found=false;
                    for(JsonNode candidate:node.path("blocks")) if(candidate.path("id").asText().equals(target) && !candidate.path("kind").asText().equals("EXPERT_HELP")) found=true;
                    if(!found) throw DomainException.invalid("Expert help must reference an existing non-help block in the same artifact");
                }
            }
        }
        if(node.isContainerNode()) node.forEach(child->validateEmbeddedReferences(owner,workspace,release,child,inputs));
    }
    private void validateExerciseReference(UUID release,JsonNode exercise) {
        if(jdbc.queryForList("SELECT re.exercise_id FROM release_exercise re JOIN exercise e ON e.id=re.exercise_id AND e.revision=re.exercise_revision JOIN content_release r ON r.id=re.release_id WHERE re.release_id=? AND re.exercise_id=? AND re.exercise_revision=? AND e.status='REVIEWED' AND r.status='PUBLISHED'",
            release.toString(),exercise.path("id").asText(),exercise.path("revision").asInt()).isEmpty()) throw DomainException.invalid("Exercise is not currently available in the workspace release");
    }
    public Version version(UUID owner,UUID artifact,int revision) {
        get(owner,artifact);
        var rows=jdbc.query("SELECT * FROM artifact_revision WHERE artifact_id=? AND revision=?",(rs,n)->new Version(artifact,revision,rs.getString("schema_version"),json.tree(rs.getString("content_json")),rs.getString("content_hash"),List.of(json.read(rs.getString("input_refs"),Reference[].class)),rs.getString("review_status")),artifact.toString(),revision);
        if(rows.isEmpty()) throw DomainException.missing();
        return rows.get(0);
    }
    @Override public Revision<JsonNode> loadOwn(UUID owner,UUID artifact,int revision) {
        Version result=version(owner,artifact,revision);
        return new Revision<>(artifact,revision,result.schemaVersion(),result.content(),result.contentHash());
    }
    @Override public Revision<JsonNode> saveOwn(UUID owner,UUID artifact,int expectedRevision,JsonNode content) {
        Version result=save(owner,artifact,expectedRevision,content,List.of());
        return new Revision<>(artifact,result.revision(),result.schemaVersion(),result.content(),result.contentHash());
    }
}
