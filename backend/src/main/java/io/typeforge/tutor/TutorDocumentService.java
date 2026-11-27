package io.typeforge.tutor;

import io.typeforge.artifacts.ArtifactService;
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

/** Explicit learner action turns a current answer into a draft with complete revocable lineage. */
@Service
public final class TutorDocumentService {
    public record DocumentResult(UUID requestId,UUID artifactId,int revision,String contentHash,String reviewStatus) {}
    private final TutorService tutors;
    private final ArtifactService artifacts;
    private final WorkspaceService workspaces;
    private final EventJournal events;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    public TutorDocumentService(TutorService tutors,ArtifactService artifacts,WorkspaceService workspaces,EventJournal events,JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json) {
        this.tutors=tutors;this.artifacts=artifacts;this.workspaces=workspaces;this.events=events;this.jdbc=jdbc;this.tx=tx;this.json=json;
    }
    public DocumentResult create(UUID owner,UUID requestId,String title,String key) {
        WorkspaceService.requireKey(key);WorkspaceService.requireText(title,128);
        return tx.execute(status->{
            var preview=tutors.get(owner,requestId);
            workspaces.lockOwned(owner,preview.workspaceId());
            String hash=json.hash(Map.of("requestId",requestId,"title",title));
            UUID replay=workspaces.replay(owner,"tutor.document",key,hash);
            if(replay!=null) return result(owner,requestId,replay);
            var response=tutors.get(owner,requestId);
            if(!response.status().equals("COMPLETED") || !response.sourceState().equals("CURRENT") || response.answer()==null) throw DomainException.conflict("A completed answer with current context and evidence is required");
            var refs=new LinkedHashMap<String,Reference>();
            for(Reference ref:workspaces.readContext(owner,response.workspaceId(),response.snapshotId()).references()) add(refs,ref);
            for(var evidence:response.evidenceRefs()) add(refs,new Reference("KNOWLEDGE_CHUNK",evidence.chunkId(),evidence.revision(),evidence.contentHash()));
            if(refs.size()>64) throw new DomainException("TUTOR_DOCUMENT_REFERENCE_LIMIT",422,"This document needs more than 64 source references; choose a smaller fresh context instead of dropping dependencies");
            List<Reference> inputs=new ArrayList<>(refs.values());
            var evidenceRefs=response.evidenceRefs().stream().map(e->Map.of("id",e.chunkId(),"revision",e.revision())).toList();
            var blocks=new ArrayList<Map<String,Object>>(List.of(
                Map.of("id","origin","kind","PARAGRAPH","text","导师请求 "+requestId+"；模式 "+response.mode()+"。本稿未经过人工事实核验，保存为 DRAFT。","evidenceRefs",List.of()),
                Map.of("id","question","kind","PARAGRAPH","text","学习问题："+response.question(),"evidenceRefs",List.of()),
                Map.of("id","answer","kind","PARAGRAPH","text",response.answer(),"evidenceRefs",evidenceRefs),
                Map.of("id","limitations","kind","PARAGRAPH","text",response.limitations(),"evidenceRefs",List.of())));
            String edits=jdbc.queryForObject("SELECT review_edits FROM tutor_message WHERE request_id=?",String.class,requestId.toString());
            if(edits!=null) json.tree(edits).properties().forEach(entry->{
                if(!entry.getValue().asText().isBlank()) blocks.add(Map.of("id","review-edit-"+entry.getKey(),
                    "kind","PARAGRAPH","text",entry.getValue().textValue(),"evidenceRefs",evidenceRefs));
            });
            var content=json.tree(json.write(Map.of("kind","DOCUMENT","schemaVersion","1.0","title",title,"blocks",blocks)));
            String artifactKey=json.sha256("tutor.document:"+owner+":"+key);
            var artifact=artifacts.create(owner,response.workspaceId(),"DOCUMENT",title,null,artifactKey);
            var version=artifacts.save(owner,artifact.id(),0,content,inputs);
            jdbc.update("INSERT INTO tutor_artifact_link(artifact_id,artifact_revision,request_id,snapshot_id,response_hash) VALUES(?,?,?,?,?)",artifact.id().toString(),version.revision(),requestId.toString(),response.snapshotId().toString(),json.hash(Map.of("answer",response.answer(),"citations",response.evidenceRefs())));
            workspaces.remember(owner,"tutor.document",key,hash,artifact.id());
            events.append(response.workspaceId(),"TUTOR_DOCUMENT_CREATED","CLIENT",Map.of("requestId",requestId,"artifactId",artifact.id(),"revision",version.revision()));
            return new DocumentResult(requestId,artifact.id(),version.revision(),version.contentHash(),version.reviewStatus());
        });
    }
    private DocumentResult result(UUID owner,UUID request,UUID artifact) {
        var rows=jdbc.queryForList("SELECT artifact_revision FROM tutor_artifact_link WHERE artifact_id=? AND request_id=?",Integer.class,artifact.toString(),request.toString());
        if(rows.isEmpty()) throw DomainException.missing();
        var version=artifacts.version(owner,artifact,rows.get(0));
        return new DocumentResult(request,artifact,version.revision(),version.contentHash(),version.reviewStatus());
    }
    private static void add(Map<String,Reference> refs,Reference next) {
        Reference old=refs.putIfAbsent(next.kind()+":"+next.id(),next);
        if(old!=null && !old.equals(next)) throw new DomainException("LINEAGE_VERSION_CONFLICT",409,"Snapshot and answer contain conflicting source versions");
    }
}
