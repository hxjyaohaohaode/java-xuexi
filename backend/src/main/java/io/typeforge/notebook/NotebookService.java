package io.typeforge.notebook;

import io.typeforge.artifacts.ArtifactService;
import io.typeforge.exercise.LearningService;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Deterministic evidence-backed notebook source compiler. No generated diagnoses or invented learner intent. */
@Service
public class NotebookService implements NotebookCompiler {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final LearningService learning;
    private final ArtifactService artifacts;
    private final EventJournal events;
    private final JsonCodec json;
    public NotebookService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,LearningService learning,ArtifactService artifacts,EventJournal events,JsonCodec json) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.learning=learning;this.artifacts=artifacts;this.events=events;this.json=json;
    }
    public ArtifactService.Artifact build(UUID owner,UUID workspace,List<UUID> attemptIds,String title,String key) {
        WorkspaceService.requireKey(key);WorkspaceService.requireText(title,255);
        if(attemptIds==null || attemptIds.isEmpty() || attemptIds.size()>20 || attemptIds.stream().distinct().count()!=attemptIds.size()) throw DomainException.invalid("Select 1..20 distinct attempts");
        return tx.execute(status->{
            var scope=workspaces.lockOwned(owner,workspace);
            String hash=json.hash(Map.of("workspaceId",workspace,"attemptIds",attemptIds,"title",title));
            UUID previous=workspaces.replay(owner,"buildNotebookDraft",key,hash);
            if(previous!=null) return artifacts.get(owner,previous);
            var inputs=new LinkedHashMap<String,Reference>();
            var blocks=new ArrayList<Map<String,Object>>();
            var entries=new ArrayList<Map<String,Object>>();
            for(UUID attemptId:attemptIds) {
                var attempt=learning.get(owner,attemptId);
                if(!attempt.workspaceId().equals(workspace) || !attempt.releaseId().equals(scope.releaseId())) throw DomainException.missing();
                if(attempt.score().compareTo(attempt.maximum())==0 && attempt.hintLevel()==0) throw DomainException.invalid("A correction notebook requires incorrect or assisted attempts");
                inputs.put("ATTEMPT:"+attemptId,attempt.evidenceRef());
                List<String> concepts=jdbc.queryForList("SELECT concept_id FROM exercise_concept WHERE exercise_id=? AND exercise_revision=? ORDER BY weight DESC,concept_id",String.class,attempt.exerciseId().toString(),attempt.exerciseRevision());
                if(concepts.isEmpty()) throw new DomainException("INVALID_PUBLISHED_EXERCISE",503,"Exercise has no concept attribution");
                var citations=new ArrayList<Map<String,Object>>();
                for(String concept:concepts) {
                    var chunks=jdbc.query("SELECT k.id,k.revision,k.content_hash FROM knowledge_chunk k JOIN release_chunk rc ON rc.chunk_id=k.id AND rc.chunk_revision=k.revision WHERE rc.release_id=? AND k.concept_id=? AND k.status='REVIEWED' ORDER BY k.id LIMIT 2",(rs,n)->new Reference("KNOWLEDGE_CHUNK",UUID.fromString(rs.getString(1)),rs.getInt(2),rs.getString(3)),scope.releaseId().toString(),concept);
                    for(Reference chunk:chunks) { inputs.put(chunk.kind()+":"+chunk.id(),chunk);citations.add(Map.of("id",chunk.id(),"revision",chunk.revision())); }
                }
                String prompt=jdbc.queryForObject("SELECT prompt FROM exercise WHERE id=? AND revision=?",String.class,attempt.exerciseId().toString(),attempt.exerciseRevision());
                String blockId="error-"+attemptId;
                String source=attempt.answer().path("source").asText("");
                Map<String,Object> block=new LinkedHashMap<>();
                block.put("id",blockId);block.put("kind","ERROR_CONTEXT");block.put("revision",1);
                block.put("prompt",prompt);block.put("conceptIds",concepts);
                block.put("exerciseRef",Map.of("id",attempt.exerciseId(),"revision",attempt.exerciseRevision()));
                block.put("attemptId",attemptId);block.put("allowedActions",List.of("predict","submitAnswer","openEvidence"));
                block.put("evidenceRefs",citations);
                block.put("config",Map.of("studentReason","学习者尚未记录自己的理由。已提交答案："+json.write(attempt.answer()),"originalSource",source,"sourceHash",json.sha256(source),"diagnosticIds",List.of()));
                blocks.add(block);
                entries.add(Map.of("attempt",attemptId,"concept",concepts.get(0),"key",blockId,"content",block));
            }
            if(inputs.size()>64) throw DomainException.invalid("Notebook input manifest exceeds 64 references");
            var artifact=artifacts.create(owner,workspace,"NOTEBOOK",title,null,json.hash(Map.of("key",key,"operation","notebook-artifact")));
            var dsl=json.tree(json.write(Map.of("kind","NOTEBOOK","schemaVersion","1.0","blocks",blocks)));
            var revision=artifacts.save(owner,artifact.id(),0,dsl,List.copyOf(inputs.values()));
            for(var entry:entries) jdbc.update("INSERT INTO notebook_entry(id,artifact_id,artifact_revision,attempt_id,concept_id,block_key,block_kind,content_json) VALUES(?,?,?,?,?,?,'ERROR_CONTEXT',?)",UUID.randomUUID().toString(),artifact.id().toString(),revision.revision(),entry.get("attempt").toString(),entry.get("concept"),entry.get("key"),json.write(entry.get("content")));
            events.append(workspace,"NOTEBOOK_DRAFT_BUILT","SERVER",Map.of("artifactId",artifact.id(),"revision",revision.revision(),"attemptCount",attemptIds.size(),"generationMode","DETERMINISTIC_EVIDENCE"));
            workspaces.remember(owner,"buildNotebookDraft",key,hash,artifact.id());
            return artifacts.get(owner,artifact.id());
        });
    }
    @Override public UUID requestBuild(Request request) {
        var scope=workspaces.requireOwned(request.ownerId(),request.workspaceId());
        if(!scope.releaseId().equals(request.releaseId())) throw DomainException.invalid("Notebook release must match its workspace");
        return build(request.ownerId(),request.workspaceId(),request.attemptIds(),"泛型错题证据草稿",json.hash(request)).id();
    }
}
