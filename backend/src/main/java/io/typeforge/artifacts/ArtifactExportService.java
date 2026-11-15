package io.typeforge.artifacts;

import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceService;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable queue: claim and commit are short transactions; rendering never holds an owner/SQL lock. */
@Service
public class ArtifactExportService {
    public record Job(UUID id,UUID artifactId,int artifactRevision,String status,UUID outputAssetId,List<String> limitations,String format,String downloadPath,String contentHash,String sourceState,String errorCode) {}
    public record Download(String mimeType,String filename,byte[] bytes,String contentHash) {public Download {bytes=bytes.clone();} @Override public byte[] bytes(){return bytes.clone();}}
    private record Ticket(UUID id,UUID owner,UUID workspace,UUID lease,ArtifactService.Artifact artifact,ArtifactService.Version version,String format,String inputHash) {}
    private record LeaseState(String status,String token,Instant until) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final ArtifactService artifacts;
    private final ArtifactFileRenderer renderer;
    private final EventJournal events;
    private final JsonCodec json;
    private final Clock clock;
    private final boolean enabled;
    private static final long MAX_OWNER_BYTES=268435456L;
    private final ExecutorService workers=Executors.newFixedThreadPool(2,r->{var thread=new Thread(r,"artifact-export");thread.setDaemon(true);return thread;});
    private final AtomicInteger inFlight=new AtomicInteger();
    public ArtifactExportService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,ArtifactService artifacts,ArtifactFileRenderer renderer,EventJournal events,JsonCodec json,Clock clock,@Value("${typeforge.export.dispatch-enabled:true}") boolean enabled) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.artifacts=artifacts;this.renderer=renderer;this.events=events;this.json=json;this.clock=clock;this.enabled=enabled;
    }
    public Job create(UUID owner,UUID artifactId,int revision,String format,String key) {
        WorkspaceService.requireKey(key);
        if(revision<1 || format==null)throw DomainException.invalid("Valid export revision and format are required");
        return tx.execute(status->{
            var artifact=artifacts.get(owner,artifactId);workspaces.lockOwned(owner,artifact.workspaceId());
            String hash=json.hash(Map.of("artifactId",artifactId,"revision",revision,"format",format,"renderer",ArtifactFileRenderer.VERSION));
            UUID previous=workspaces.replay(owner,"createExport",key,hash);if(previous!=null)return get(owner,previous);
            if(!renderer.supports(artifact.kind(),format))throw new DomainException("CAPABILITY_UNAVAILABLE",503,"No configured renderer supports this artifact and format");
            var version=usable(owner,artifactId,revision);
            pruneObsoleteFiles(owner);
            var reusable=jdbc.queryForList("SELECT id FROM export_job WHERE artifact_id=? AND artifact_revision=? AND format_key=? AND renderer_version=? AND input_hash=? AND status IN ('QUEUED','RUNNING','SUCCEEDED') ORDER BY created_at DESC,id LIMIT 1",String.class,artifactId.toString(),revision,format,ArtifactFileRenderer.VERSION,inputHash(artifact,version,format));
            if(!reusable.isEmpty()){UUID id=UUID.fromString(reusable.get(0));workspaces.remember(owner,"createExport",key,hash,id);return get(owner,id);}
            Long daily=jdbc.queryForObject("SELECT COUNT(*) FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id JOIN learning_workspace w ON w.id=a.workspace_id WHERE w.owner_id=? AND e.created_at>=?",Long.class,owner.toString(),Timestamp.from(clock.instant().minusSeconds(86400)));
            if(daily!=null && daily>=200)throw new DomainException("EXPORT_DAILY_LIMIT",429,"At most 200 new export jobs may be created per account in 24 hours");
            if(storedBytes(owner)>=MAX_OWNER_BYTES)throw new DomainException("EXPORT_STORAGE_LIMIT",429,"The account has reached its 256 MiB export cache limit");
            Long pending=jdbc.queryForObject("SELECT COUNT(*) FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id JOIN learning_workspace w ON w.id=a.workspace_id WHERE w.owner_id=? AND e.status IN ('QUEUED','RUNNING')",Long.class,owner.toString());
            if(pending!=null && pending>=8)throw new DomainException("EXPORT_QUEUE_LIMIT",429,"At most 8 pending exports per account");
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO export_job(id,artifact_id,artifact_revision,format_key,renderer_version,input_hash,execution_key,status) VALUES(?,?,?,?,?,?,?,'QUEUED')",id.toString(),artifactId.toString(),revision,format,ArtifactFileRenderer.VERSION,inputHash(artifact,version,format),json.hash(Map.of("owner",owner,"key",key,"operation","export")));
            events.append(artifact.workspaceId(),"EXPORT_QUEUED","SERVER",Map.of("exportId",id,"artifactId",artifactId,"artifactRevision",revision,"format",format));
            workspaces.remember(owner,"createExport",key,hash,id);return get(owner,id);
        });
    }
    public Job get(UUID owner,UUID id) {
        var rows=jdbc.queryForList("SELECT e.* FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id JOIN learning_workspace w ON w.id=a.workspace_id WHERE e.id=? AND w.owner_id=?",id.toString(),owner.toString());
        if(rows.isEmpty())throw DomainException.missing();var row=rows.get(0);
        UUID artifactId=UUID.fromString(row.get("artifact_id").toString());var artifact=artifacts.get(owner,artifactId);
        int revision=((Number)row.get("artifact_revision")).intValue();String state;
        try {
            var version=artifacts.version(owner,artifactId,revision);
            state=Set.of("DRAFT","VERIFIED").contains(version.reviewStatus())?artifact.currentRevision()==revision?"CURRENT":"SUPERSEDED":version.reviewStatus();
            if(state.equals("CURRENT"))artifacts.validateCurrentInputs(owner,artifactId,revision);
        } catch(DomainException failure) {
            if(failure.status()==409 || failure.status()==404)state="STALE";
            else if(Set.of("ARTIFACT_INTEGRITY_FAILURE","CONTENT_INTEGRITY_FAILURE").contains(failure.code()))state="CORRUPT";
            else throw failure;
        }
        String status=row.get("status").toString(),format=row.get("format_key").toString();var limitations=new ArrayList<String>();
        limitations.add("固定版本源稿渲染；生成文件不会把草稿变为已核验的教学事实。");
        if(Set.of("NOTEBOOK","INTERACTION").contains(artifact.kind()))limitations.add("静态阅读导出，提交与复练请返回在线工作空间。");
        if(artifact.kind().equals("SLIDES"))limitations.add(format.equals("PPTX")?"文字与代码可编辑；讲稿和引用保存在演示备注。":format.equals("PDF")?"固定版式幻灯片PDF；演示讲稿保留在源稿及PPTX备注。":"可打印的HTML幻灯片。");
        if(artifact.kind().equals("DIAGRAM"))limitations.add("关系来自源稿，不是编译器裁决。");
        if(!state.equals("CURRENT"))limitations.add("源稿已更新或来源失效，请按当前源稿重新导出。");
        String error=row.get("failure_code")==null?null:row.get("failure_code").toString();
        var hashes=jdbc.queryForList("SELECT content_hash FROM artifact_export_binary WHERE export_id=? UNION ALL SELECT content_hash FROM artifact_export_content WHERE export_id=?",String.class,id.toString(),id.toString());
        return new Job(id,artifactId,revision,status,null,List.copyOf(limitations),format,status.equals("SUCCEEDED")&&state.equals("CURRENT")?"/api/v1/export-jobs/"+id+"/download":null,hashes.isEmpty()?null:hashes.get(0),state,error);
    }
    public Job cancel(UUID owner,UUID id) {
        return tx.execute(status->{var current=get(owner,id);var artifact=artifacts.get(owner,current.artifactId());workspaces.lockOwned(owner,artifact.workspaceId());
            current=get(owner,id);if(current.status().equals("CANCELLED"))return current;
            if(!Set.of("QUEUED","RUNNING").contains(current.status()))throw DomainException.conflict("Export already finished; cancellation cannot undo an already downloaded file");
            jdbc.update("UPDATE export_job SET status='CANCELLED',lease_token=NULL,lease_until=NULL WHERE id=? AND status IN ('QUEUED','RUNNING')",id.toString());
            events.append(artifact.workspaceId(),"EXPORT_CANCELLED","CLIENT",Map.of("exportId",id));return get(owner,id);});
    }
    public Download download(UUID owner,UUID id) {
        return tx.execute(status->{var current=get(owner,id);var artifact=artifacts.get(owner,current.artifactId());workspaces.lockOwned(owner,artifact.workspaceId());
            current=get(owner,id);usable(owner,current.artifactId(),current.artifactRevision());
            if(!current.status().equals("SUCCEEDED"))throw DomainException.conflict("Export has no successful file");
            var rows=jdbc.query("SELECT * FROM artifact_export_binary WHERE export_id=?",(rs,n)->new Download(rs.getString("mime_type"),rs.getString("filename"),rs.getBytes("content_bytes"),rs.getString("content_hash")),id.toString());
            if(rows.isEmpty())rows=jdbc.query("SELECT * FROM artifact_export_content WHERE export_id=?",(rs,n)->new Download(rs.getString("mime_type"),rs.getString("filename"),rs.getString("content_text").getBytes(StandardCharsets.UTF_8),rs.getString("content_hash")),id.toString());
            if(rows.isEmpty() || !ArtifactFileRenderer.sha256(rows.get(0).bytes()).equals(rows.get(0).contentHash()))throw new DomainException("EXPORT_INTEGRITY_FAILURE",503,"Export content requires repair");
            return rows.get(0);
        });
    }
    private ArtifactService.Version usable(UUID owner,UUID artifactId,int revision) {
        var artifact=artifacts.get(owner,artifactId);var version=artifacts.version(owner,artifactId,revision);
        if(artifact.currentRevision()!=revision || !Set.of("DRAFT","VERIFIED").contains(version.reviewStatus()))throw new DomainException("STALE_EXPORT_SOURCE",409,"Only the current non-stale artifact revision may be exported or downloaded");
        artifacts.validateCurrentInputs(owner,artifactId,revision);return version;
    }
    private String inputHash(ArtifactService.Artifact artifact,ArtifactService.Version version,String format) {
        return json.hash(Map.of("content",version.contentHash(),"refs",version.inputRefs(),"title",artifact.title(),"format",format));
    }
    @Scheduled(fixedDelayString="${typeforge.export.poll-millis:1000}") public void scheduled() {
        if(!enabled || inFlight.get()>=2)return;
        if(inFlight.incrementAndGet()>2){inFlight.decrementAndGet();return;}
        workers.submit(()->{try{dispatchOnce();}finally{inFlight.decrementAndGet();}});
    }
    public int dispatchOnce() {
        recoverExpired();
        var pending=jdbc.queryForList("SELECT e.id,w.owner_id,a.workspace_id FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id JOIN learning_workspace w ON w.id=a.workspace_id WHERE e.status='QUEUED' ORDER BY e.created_at,e.id LIMIT 8");int processed=0;
        for(var row:pending) {
            UUID id=UUID.fromString(row.get("id").toString()),owner=UUID.fromString(row.get("owner_id").toString()),workspace=UUID.fromString(row.get("workspace_id").toString());
            Ticket ticket;
            try{ticket=claim(owner,workspace,id);}catch(DomainException exception){fail(owner,workspace,id,null,exception.code());processed++;continue;}
            if(ticket==null)continue;processed++;
            try{finish(ticket,renderer.render(ticket.owner(),ticket.artifact(),ticket.version(),ticket.format()));}
            catch(DomainException exception){fail(owner,workspace,id,ticket.lease(),exception.code());}
            catch(RuntimeException exception){fail(owner,workspace,id,ticket.lease(),"EXPORT_RENDER_FAILED");}
        }return processed;
    }
    private void pruneObsoleteFiles(UUID owner) {
        // Obsolete source files cannot be downloaded. Keep their immutable job/audit metadata,
        // reclaim only reproducible payloads; the owner lock serializes concurrent download/commit.
        var obsolete=jdbc.queryForList("SELECT e.id FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id JOIN artifact_revision r ON r.artifact_id=a.id AND r.revision=e.artifact_revision JOIN learning_workspace w ON w.id=a.workspace_id WHERE w.owner_id=? AND (a.current_revision<>e.artifact_revision OR r.review_status NOT IN ('DRAFT','VERIFIED')) AND (EXISTS (SELECT 1 FROM artifact_export_binary b WHERE b.export_id=e.id) OR EXISTS (SELECT 1 FROM artifact_export_content c WHERE c.export_id=e.id)) ORDER BY e.created_at,e.id LIMIT 256",String.class,owner.toString());
        for(String id:obsolete){jdbc.update("DELETE FROM artifact_export_binary WHERE export_id=?",id);jdbc.update("DELETE FROM artifact_export_content WHERE export_id=?",id);}
    }
    private long storedBytes(UUID owner) {
        Long value=jdbc.queryForObject("SELECT COALESCE(SUM(COALESCE(b.byte_size,0)+COALESCE(c.byte_size,0)),0) FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id JOIN learning_workspace w ON w.id=a.workspace_id LEFT JOIN artifact_export_binary b ON b.export_id=e.id LEFT JOIN artifact_export_content c ON c.export_id=e.id WHERE w.owner_id=?",Long.class,owner.toString());return value==null?0:value;
    }
    private Ticket claim(UUID owner,UUID workspace,UUID id) {
        return tx.execute(status->{
            workspaces.lockOwned(owner,workspace);
            var rows=jdbc.queryForList("SELECT * FROM export_job WHERE id=? FOR UPDATE",id.toString());
            if(rows.isEmpty() || !rows.get(0).get("status").equals("QUEUED"))return null;
            var row=rows.get(0);String rendererVersion=row.get("renderer_version").toString();
            if(!Set.of(ArtifactFileRenderer.VERSION,TextArtifactRenderer.VERSION).contains(rendererVersion))throw new DomainException("RENDERER_VERSION_CHANGED",409,"Queued job requires its originally admitted renderer version");
            UUID artifactId=UUID.fromString(row.get("artifact_id").toString());int revision=((Number)row.get("artifact_revision")).intValue();
            var artifact=artifacts.get(owner,artifactId);var version=usable(owner,artifactId,revision);String format=row.get("format_key").toString(),input=row.get("input_hash").toString();
            if(!input.equals(inputHash(artifact,version,format)))throw new DomainException("EXPORT_INPUT_CHANGED",409,"Pinned export input changed");
            UUID lease=UUID.randomUUID();
            jdbc.update("UPDATE export_job SET status='RUNNING',lease_token=?,lease_until=? WHERE id=? AND status='QUEUED'",lease.toString(),Timestamp.from(clock.instant().plusSeconds(120)),id.toString());
            events.append(workspace,"EXPORT_STARTED","SERVER",Map.of("exportId",id));
            return new Ticket(id,owner,workspace,lease,artifact,version,format,input);
        });
    }
    private void finish(Ticket ticket,ArtifactFileRenderer.Rendered rendered) {
        tx.executeWithoutResult(status->{
            workspaces.lockOwned(ticket.owner(),ticket.workspace());
            var rows=jdbc.query("SELECT status,lease_token,lease_until FROM export_job WHERE id=? FOR UPDATE",(rs,n)->{Timestamp until=rs.getTimestamp("lease_until");return new LeaseState(rs.getString("status"),rs.getString("lease_token"),until==null?null:until.toInstant());},ticket.id().toString());
            if(rows.isEmpty() || !rows.get(0).status().equals("RUNNING") || !ticket.lease().toString().equals(rows.get(0).token()))return;
            if(rows.get(0).until()==null || !rows.get(0).until().isAfter(clock.instant()))throw new DomainException("EXPORT_LEASE_EXPIRED",409,"Export exceeded its rendering deadline");
            var artifact=artifacts.get(ticket.owner(),ticket.artifact().id());var version=usable(ticket.owner(),artifact.id(),ticket.version().revision());
            if(!ticket.inputHash().equals(inputHash(artifact,version,ticket.format())))throw new DomainException("EXPORT_INPUT_CHANGED",409,"Pinned export input changed while rendering");
            byte[] bytes=rendered.bytes();
            if(storedBytes(ticket.owner())>MAX_OWNER_BYTES-bytes.length)throw new DomainException("EXPORT_STORAGE_LIMIT",429,"The account has reached its 256 MiB export cache limit");
            String hash=ArtifactFileRenderer.sha256(bytes),name="typeforge-"+artifact.id()+"-r"+version.revision()+"."+rendered.extension();
            if(bytes.length<=1048576 && Set.of("text/markdown","text/html","image/svg+xml").contains(rendered.mimeType())) {
                jdbc.update("INSERT INTO artifact_export_content(export_id,mime_type,filename,content_text,content_hash,byte_size) VALUES(?,?,?,?,?,?)",ticket.id().toString(),rendered.mimeType(),name,new String(bytes,StandardCharsets.UTF_8),hash,bytes.length);
            } else jdbc.update("INSERT INTO artifact_export_binary(export_id,mime_type,filename,content_bytes,content_hash,byte_size) VALUES(?,?,?,?,?,?)",ticket.id().toString(),rendered.mimeType(),name,bytes,hash,bytes.length);
            jdbc.update("UPDATE export_job SET status='SUCCEEDED',lease_token=NULL,lease_until=NULL WHERE id=?",ticket.id().toString());
            events.append(ticket.workspace(),"EXPORT_SUCCEEDED","SERVER",Map.of("exportId",ticket.id(),"artifactId",artifact.id(),"artifactRevision",version.revision(),"byteSize",bytes.length));
        });
    }
    private void fail(UUID owner,UUID workspace,UUID id,UUID lease,String reason) {
        tx.executeWithoutResult(status->{
            // Also close jobs of disabled owners/archived workspaces, without admitting their content.
            jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",owner.toString());
            if(jdbc.queryForList("SELECT id FROM learning_workspace WHERE id=? AND owner_id=? FOR UPDATE",workspace.toString(),owner.toString()).isEmpty())return;
            int changed=lease==null?jdbc.update("UPDATE export_job SET status='FAILED',failure_code=?,lease_token=NULL,lease_until=NULL WHERE id=? AND status='QUEUED'",reason,id.toString()):jdbc.update("UPDATE export_job SET status='FAILED',failure_code=?,lease_token=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND lease_token=?",reason,id.toString(),lease.toString());
            if(changed==1)events.append(workspace,"EXPORT_FAILED","SERVER",Map.of("exportId",id,"reason",reason));
        });
    }
    private void recoverExpired() {
        Instant now=clock.instant();
        var rows=jdbc.queryForList("SELECT e.id,e.lease_token,w.owner_id,a.workspace_id FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id JOIN learning_workspace w ON w.id=a.workspace_id WHERE e.status='RUNNING' AND (e.lease_until IS NULL OR e.lease_until<=?) LIMIT 32",Timestamp.from(now));
        for(var row:rows){
            if(row.get("lease_token")==null){jdbc.update("UPDATE export_job SET status='FAILED',failure_code='EXPORT_LEASE_EXPIRED' WHERE id=? AND status='RUNNING' AND lease_token IS NULL",row.get("id"));continue;}
            fail(UUID.fromString(row.get("owner_id").toString()),UUID.fromString(row.get("workspace_id").toString()),UUID.fromString(row.get("id").toString()),UUID.fromString(row.get("lease_token").toString()),"EXPORT_LEASE_EXPIRED");
        }
    }
    @PreDestroy public void close(){workers.shutdownNow();}
}
