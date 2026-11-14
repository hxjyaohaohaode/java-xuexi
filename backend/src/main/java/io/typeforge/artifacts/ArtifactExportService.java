package io.typeforge.artifacts;

import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable local queue. A bounded inert renderer commits bytes and status atomically. */
@Service
public class ArtifactExportService {
    public record Job(UUID id,UUID artifactId,int artifactRevision,String status,UUID outputAssetId,List<String> limitations,String format,String downloadPath,String contentHash,String sourceState,String errorCode) {}
    public record Download(String mimeType,String filename,byte[] bytes,String contentHash) {public Download {bytes=bytes.clone();} @Override public byte[] bytes(){return bytes.clone();}}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final ArtifactService artifacts;
    private final TextArtifactRenderer renderer;
    private final EventJournal events;
    private final JsonCodec json;
    private final boolean enabled;
    public ArtifactExportService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,ArtifactService artifacts,TextArtifactRenderer renderer,EventJournal events,JsonCodec json,@Value("${typeforge.export.dispatch-enabled:true}") boolean enabled) {this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.artifacts=artifacts;this.renderer=renderer;this.events=events;this.json=json;this.enabled=enabled;}
    public Job create(UUID owner,UUID artifactId,int revision,String format,String key) {
        WorkspaceService.requireKey(key);
        if(revision<1 || format==null) throw DomainException.invalid("Valid export revision and format are required");
        return tx.execute(status->{
            var artifact=artifacts.get(owner,artifactId);workspaces.lockOwned(owner,artifact.workspaceId());
            String hash=json.hash(Map.of("artifactId",artifactId,"revision",revision,"format",format,"renderer",TextArtifactRenderer.VERSION));
            UUID previous=workspaces.replay(owner,"createExport",key,hash);if(previous!=null) return get(owner,previous);
            if(!renderer.supports(artifact.kind(),format)) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"No configured renderer supports this artifact and format");
            var version=usable(owner,artifactId,revision);
            Long pending=jdbc.queryForObject("SELECT COUNT(*) FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id JOIN learning_workspace w ON w.id=a.workspace_id WHERE w.owner_id=? AND e.status IN ('QUEUED','RUNNING')",Long.class,owner.toString());
            if(pending!=null && pending>=8) throw new DomainException("EXPORT_QUEUE_LIMIT",429,"At most 8 pending exports per account");
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO export_job(id,artifact_id,artifact_revision,format_key,renderer_version,input_hash,execution_key,status) VALUES(?,?,?,?,?,?,?,'QUEUED')",id.toString(),artifactId.toString(),revision,format,TextArtifactRenderer.VERSION,json.hash(Map.of("content",version.contentHash(),"refs",version.inputRefs(),"title",artifact.title(),"format",format)),json.hash(Map.of("owner",owner,"key",key,"operation","export")));
            events.append(artifact.workspaceId(),"EXPORT_QUEUED","SERVER",Map.of("exportId",id,"artifactId",artifactId,"artifactRevision",revision,"format",format));
            workspaces.remember(owner,"createExport",key,hash,id);return get(owner,id);
        });
    }
    public Job get(UUID owner,UUID id) {
        var rows=jdbc.queryForList("SELECT e.* FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id JOIN learning_workspace w ON w.id=a.workspace_id WHERE e.id=? AND w.owner_id=?",id.toString(),owner.toString());
        if(rows.isEmpty()) throw DomainException.missing();var row=rows.get(0);
        UUID artifactId=UUID.fromString(row.get("artifact_id").toString());var artifact=artifacts.get(owner,artifactId);
        int revision=((Number)row.get("artifact_revision")).intValue();String state;
        try {
            var version=artifacts.version(owner,artifactId,revision);
            state=Set.of("DRAFT","VERIFIED").contains(version.reviewStatus())?artifact.currentRevision()==revision?"CURRENT":"SUPERSEDED":version.reviewStatus();
            if(state.equals("CURRENT")) artifacts.validateCurrentInputs(owner,artifactId,revision);
        } catch(DomainException failure) {
            if(failure.status()==409 || failure.status()==404) state="STALE";
            else if(Set.of("ARTIFACT_INTEGRITY_FAILURE","CONTENT_INTEGRITY_FAILURE").contains(failure.code())) state="CORRUPT";
            else throw failure;
        }
        String status=row.get("status").toString();var limitations=new ArrayList<String>();
        limitations.add("固定 revision 的源稿渲染，不代表教学事实已经核验。");
        if(Set.of("NOTEBOOK","INTERACTION").contains(artifact.kind())) limitations.add("静态阅读导出，在线提交与复练需要返回平台。");
        if(artifact.kind().equals("SLIDES")) limitations.add("HTML 幻灯片，不是 PPTX。只支持文字与代码元素。");
        if(artifact.kind().equals("DIAGRAM")) limitations.add("图中关系来自用户源稿，不是编译器裁决。");
        if(!state.equals("CURRENT")) limitations.add("源稿已更新或引用失效，此作业不可下载；请按当前源稿重新导出。");
        String error=row.get("failure_code")==null?null:row.get("failure_code").toString();if(error!=null) limitations.add("导出失败："+error);
        var hashes=jdbc.queryForList("SELECT content_hash FROM artifact_export_content WHERE export_id=?",String.class,id.toString());
        return new Job(id,artifactId,revision,status,null,List.copyOf(limitations),row.get("format_key").toString(),status.equals("SUCCEEDED") && state.equals("CURRENT")?"/api/v1/export-jobs/"+id+"/download":null,hashes.isEmpty()?null:hashes.get(0),state,error);
    }
    public Job cancel(UUID owner,UUID id) {
        return tx.execute(status->{var current=get(owner,id);var artifact=artifacts.get(owner,current.artifactId());workspaces.lockOwned(owner,artifact.workspaceId());
            current=get(owner,id);if(current.status().equals("CANCELLED")) return current;
            if(!current.status().equals("QUEUED")) throw DomainException.conflict("Export already completed; cancellation cannot undo a downloaded file");
            jdbc.update("UPDATE export_job SET status='CANCELLED' WHERE id=? AND status='QUEUED'",id.toString());events.append(artifact.workspaceId(),"EXPORT_CANCELLED","CLIENT",Map.of("exportId",id));return get(owner,id);});
    }
    public Download download(UUID owner,UUID id) {
        return tx.execute(status->{var current=get(owner,id);var artifact=artifacts.get(owner,current.artifactId());workspaces.lockOwned(owner,artifact.workspaceId());
            current=get(owner,id);usable(owner,current.artifactId(),current.artifactRevision());
            if(!current.status().equals("SUCCEEDED")) throw DomainException.conflict("Export has no successful file");
            var rows=jdbc.query("SELECT * FROM artifact_export_content WHERE export_id=?",(rs,n)->new Download(rs.getString("mime_type"),rs.getString("filename"),rs.getString("content_text").getBytes(StandardCharsets.UTF_8),rs.getString("content_hash")),id.toString());
            if(rows.isEmpty() || !json.sha256(new String(rows.get(0).bytes(),StandardCharsets.UTF_8)).equals(rows.get(0).contentHash())) throw new DomainException("EXPORT_INTEGRITY_FAILURE",503,"Export content requires repair");
            return rows.get(0);
        });
    }
    private ArtifactService.Version usable(UUID owner,UUID artifactId,int revision) {
        var artifact=artifacts.get(owner,artifactId);var version=artifacts.version(owner,artifactId,revision);
        if(artifact.currentRevision()!=revision || !Set.of("DRAFT","VERIFIED").contains(version.reviewStatus())) throw new DomainException("STALE_EXPORT_SOURCE",409,"Only the current non-stale artifact revision may be exported or downloaded");
        artifacts.validateCurrentInputs(owner,artifactId,revision);return version;
    }
    @Scheduled(fixedDelayString="${typeforge.export.poll-millis:1000}") public void scheduled() {if(enabled) dispatchOnce();}
    public int dispatchOnce() {
        var pending=jdbc.queryForList("SELECT e.id,w.owner_id,a.workspace_id FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id JOIN learning_workspace w ON w.id=a.workspace_id WHERE e.status='QUEUED' ORDER BY e.created_at,e.id LIMIT 8");int processed=0;
        for(var row:pending) {
            UUID id=UUID.fromString(row.get("id").toString()),owner=UUID.fromString(row.get("owner_id").toString()),workspace=UUID.fromString(row.get("workspace_id").toString());
            try {Boolean changed=tx.execute(status->{
                workspaces.lockOwned(owner,workspace);var job=get(owner,id);if(!job.status().equals("QUEUED")) return false;
                String failure=null;
                try {
                    String rendererVersion=jdbc.queryForObject("SELECT renderer_version FROM export_job WHERE id=?",String.class,id.toString());
                    if(!TextArtifactRenderer.VERSION.equals(rendererVersion)) throw new DomainException("RENDERER_VERSION_CHANGED",409,"Queued job requires its originally admitted renderer version");
                    var artifact=artifacts.get(owner,job.artifactId());var version=usable(owner,job.artifactId(),job.artifactRevision());
                    var rendered=renderer.render(artifact,version,job.format());
                    String actualInput=json.hash(Map.of("content",version.contentHash(),"refs",version.inputRefs(),"title",artifact.title(),"format",job.format()));
                    String storedInput=jdbc.queryForObject("SELECT input_hash FROM export_job WHERE id=?",String.class,id.toString());
                    if(!actualInput.equals(storedInput)) throw new DomainException("EXPORT_INPUT_CHANGED",409,"Export input no longer matches its pinned hash");
                    jdbc.update("INSERT INTO artifact_export_content(export_id,mime_type,filename,content_text,content_hash,byte_size) VALUES(?,?,?,?,?,?)",id.toString(),rendered.mimeType(),"typeforge-"+job.artifactId()+"-r"+job.artifactRevision()+"."+rendered.extension(),rendered.content(),json.sha256(rendered.content()),rendered.content().getBytes(StandardCharsets.UTF_8).length);
                    jdbc.update("UPDATE export_job SET status='SUCCEEDED' WHERE id=?",id.toString());
                } catch(DomainException exception) {failure=exception.code();jdbc.update("UPDATE export_job SET status='FAILED',failure_code=? WHERE id=?",failure,id.toString());}
                events.append(workspace,failure==null?"EXPORT_SUCCEEDED":"EXPORT_FAILED","SERVER",Map.of("exportId",id,"artifactId",job.artifactId(),"artifactRevision",job.artifactRevision(),"reason",failure==null?"":failure));return true;
            });if(Boolean.TRUE.equals(changed)) processed++;}
            catch(DomainException exception) {
                // Disabled/archived owners cannot retain an unclaimable queue head. No file bytes are created.
                if(exception.status()==404 || exception.status()==409) failUnavailable(owner,workspace,id);else throw exception;
            }
        }return processed;
    }
    private void failUnavailable(UUID owner,UUID workspace,UUID id) {
        tx.executeWithoutResult(status->{
            jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",owner.toString());
            if(jdbc.queryForList("SELECT id FROM learning_workspace WHERE id=? AND owner_id=? FOR UPDATE",workspace.toString(),owner.toString()).isEmpty()) return;
            if(jdbc.update("UPDATE export_job SET status='FAILED',failure_code='WORKSPACE_UNAVAILABLE' WHERE id=? AND status='QUEUED'",id.toString())==1) events.append(workspace,"EXPORT_FAILED","SERVER",Map.of("exportId",id,"reason","WORKSPACE_UNAVAILABLE"));
        });
    }
}
