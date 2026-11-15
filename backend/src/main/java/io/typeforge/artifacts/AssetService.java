package io.typeforge.artifacts;

import io.typeforge.operations.RuntimeCapabilityProvider;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceService;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** User-owned, bounded byte storage. URLs and browser-provided MIME never determine the stored type or path. */
@Service
public class AssetService implements RuntimeCapabilityProvider {
    public static final int MAX_FILE_BYTES=8_388_608;
    public record Asset(UUID id,UUID workspaceId,String filename,String mimeType,long byteSize,String contentHash,int revision,String status,Instant createdAt,Integer width,Integer height,Long durationMillis,String downloadPath,String validation) {}
    public record Usage(long usedBytes,long limitBytes,int maxFileBytes,long activeFiles,int maxFiles) {}
    public record Page(List<Asset> items,UUID nextCursor,Usage usage) {}
    public record Download(Asset asset,byte[] bytes) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final ArtifactInvalidation invalidation;
    private final EventJournal events;
    private final JsonCodec json;
    private final Clock clock;
    private final MediaInspector inspector;
    private final long ownerLimit;
    private final Semaphore permits=new Semaphore(2);
    private static final String SELECT="SELECT a.id,a.workspace_id,a.mime_type,a.byte_size,a.content_hash,a.created_at,p.filename,p.revision,p.status,p.pixel_width,p.pixel_height,p.duration_millis,p.validation_method FROM asset_object a JOIN asset_payload p ON p.asset_id=a.id";
    public AssetService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,ArtifactInvalidation invalidation,EventJournal events,JsonCodec json,Clock clock,MediaInspector inspector,@Value("${typeforge.assets.max-owner-bytes:104857600}") long ownerLimit) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.invalidation=invalidation;this.events=events;this.json=json;this.clock=clock;this.inspector=inspector;
        if(ownerLimit<1 || ownerLimit>1_073_741_824L) throw new IllegalArgumentException("Asset owner quota must be 1 byte to 1 GiB");this.ownerLimit=ownerLimit;
    }
    @Override public Set<String> enabledCapabilities() {return Set.of("PRIVATE_ASSETS");}
    public Asset upload(UUID owner,UUID workspace,String originalName,String declaredType,InputStream input,String key) {
        WorkspaceService.requireKey(key);workspaces.requireOwned(owner,workspace);
        if(!permits.tryAcquire()) throw new DomainException("ASSET_UPLOAD_BUSY",429,"Two uploads are already being validated; keep the selected file and retry");
        try {
            byte[] bytes=input.readNBytes(MAX_FILE_BYTES+1);
            if(bytes.length>MAX_FILE_BYTES) throw new DomainException("ASSET_TOO_LARGE",413,"Each asset must be at most 8 MiB");
            MediaInspector.Inspection info=inspector.inspect(bytes);checkDeclaredType(declaredType,info.mimeType());
            String filename=safeName(originalName,info.extension()),hash=sha256(bytes);
            String requestHash=json.hash(Map.of("workspaceId",workspace,"filename",filename,"contentHash",hash,"mimeType",info.mimeType()));
            return tx.execute(status->{
                workspaces.lockOwned(owner,workspace);UUID previous=workspaces.replay(owner,"uploadAsset",key,requestHash);if(previous!=null) return get(owner,previous);
                Usage usage=usage(owner);
                if(usage.usedBytes()>ownerLimit-bytes.length || usage.activeFiles()>=usage.maxFiles()) throw new DomainException("ASSET_QUOTA_EXCEEDED",429,"Private asset quota is full; revoke an unused asset before uploading another");
                Long daily=jdbc.queryForObject("SELECT COUNT(*) FROM asset_object a JOIN learning_workspace w ON w.id=a.workspace_id WHERE w.owner_id=? AND a.created_at>=?",Long.class,owner.toString(),Timestamp.from(clock.instant().minusSeconds(86400)));
                if(daily!=null && daily>=500) throw new DomainException("ASSET_UPLOAD_DAILY_LIMIT",429,"At most 500 asset uploads are accepted per account in a rolling 24-hour window");
                UUID id=UUID.randomUUID();Instant now=clock.instant();
                jdbc.update("INSERT INTO asset_object(id,workspace_id,object_key,mime_type,byte_size,content_hash,scan_status,created_at) VALUES(?,?,?,?,?,?,'CLEAN',?)",id.toString(),workspace.toString(),"private-db/"+id,info.mimeType(),bytes.length,hash,Timestamp.from(now));
                jdbc.update("INSERT INTO asset_payload(asset_id,filename,content_bytes,pixel_width,pixel_height,duration_millis,validation_method) VALUES(?,?,?,?,?,?,'STRUCTURE_VERIFIED_NO_ANTIVIRUS')",id.toString(),filename,bytes,info.width(),info.height(),info.durationMillis());
                workspaces.remember(owner,"uploadAsset",key,requestHash,id);events.append(workspace,"ASSET_UPLOADED","CLIENT",Map.of("assetId",id,"byteSize",bytes.length,"mimeType",info.mimeType(),"contentHash",hash));return get(owner,id);
            });
        } catch(IOException failure) {throw new DomainException("ASSET_READ_FAILED",400,"Upload was incomplete; select the file again and retry with the same idempotency key");}
        finally {permits.release();}
    }
    public Asset get(UUID owner,UUID id) {
        UUID workspace=workspace(owner,id);workspaces.requireOwned(owner,workspace);
        return jdbc.queryForObject(SELECT+" WHERE a.id=?",this::asset,id.toString());
    }
    public Page list(UUID owner,UUID workspace,UUID cursor,int limit) {
        if(limit<1 || limit>100) throw DomainException.invalid("Asset list limit must be 1..100");workspaces.requireOwned(owner,workspace);
        String condition=" WHERE a.workspace_id=? AND p.status='READY'";var arguments=new ArrayList<Object>();arguments.add(workspace.toString());
        if(cursor!=null) {var after=get(owner,cursor);if(!after.workspaceId().equals(workspace)) throw DomainException.invalid("Asset cursor belongs to another workspace");condition+=" AND (a.created_at<? OR (a.created_at=? AND a.id<?))";arguments.add(Timestamp.from(after.createdAt()));arguments.add(Timestamp.from(after.createdAt()));arguments.add(cursor.toString());}
        arguments.add(limit+1);var rows=jdbc.query(SELECT+condition+" ORDER BY a.created_at DESC,a.id DESC LIMIT ?",this::asset,arguments.toArray());var items=rows.stream().limit(limit).toList();
        return new Page(items,rows.size()>limit?items.get(items.size()-1).id():null,usage(owner));
    }
    public Usage usage(UUID owner) {
        var rows=jdbc.queryForMap("SELECT COALESCE(SUM(a.byte_size),0) AS used_bytes,COUNT(*) AS active_files FROM asset_object a JOIN asset_payload p ON p.asset_id=a.id JOIN learning_workspace w ON w.id=a.workspace_id WHERE w.owner_id=? AND p.status='READY'",owner.toString());
        return new Usage(((Number)rows.get("used_bytes")).longValue(),ownerLimit,MAX_FILE_BYTES,((Number)rows.get("active_files")).longValue(),1000);
    }
    public Download download(UUID owner,UUID id) {
        return tx.execute(status->{
            UUID workspace=workspace(owner,id);workspaces.lockOwned(owner,workspace);var asset=get(owner,id);
            if(!asset.status().equals("READY")) throw new DomainException("ASSET_REVOKED",410,"This asset was revoked and its stored bytes have been removed");
            byte[] bytes=jdbc.queryForObject("SELECT content_bytes FROM asset_payload WHERE asset_id=? AND status='READY'",byte[].class,id.toString());
            if(bytes==null || bytes.length!=asset.byteSize() || !sha256(bytes).equals(asset.contentHash())) throw new DomainException("ASSET_INTEGRITY_FAILURE",503,"Stored asset bytes do not match the fixed content hash");
            return new Download(asset,bytes);
        });
    }
    public void revoke(UUID owner,UUID id) {
        tx.executeWithoutResult(status->{
            UUID workspace=workspace(owner,id);workspaces.lockOwned(owner,workspace);var asset=get(owner,id);if(asset.status().equals("REVOKED")) return;
            revokeLocked(asset);
        });
    }
    /** Internal lifecycle hook also handles already archived spaces; it never trusts the supplied owner without locking it. */
    public void revokeForAccountClosure(UUID owner,UUID workspace) {
        tx.executeWithoutResult(status->{
            if(jdbc.queryForList("SELECT id FROM app_user WHERE id=? AND status='ACTIVE' FOR UPDATE",owner.toString()).isEmpty()) throw DomainException.missing();
            if(jdbc.queryForList("SELECT id FROM learning_workspace WHERE id=? AND owner_id=? FOR UPDATE",workspace.toString(),owner.toString()).isEmpty()) throw DomainException.missing();
            var rows=jdbc.query(SELECT+" WHERE a.workspace_id=? AND p.status='READY' ORDER BY a.id",this::asset,workspace.toString());
            for(var asset:rows) revokeLocked(asset);
        });
    }
    private void revokeLocked(Asset asset) {
        EventJournal.requireTransaction();
        invalidation.invalidate(asset.workspaceId(),"ASSET",asset.id(),asset.revision()+1);
        jdbc.update("UPDATE asset_payload SET content_bytes=NULL,status='REVOKED',revision=revision+1,revoked_at=? WHERE asset_id=? AND status='READY'",Timestamp.from(clock.instant()),asset.id().toString());
        jdbc.update("UPDATE asset_object SET scan_status='REJECTED' WHERE id=?",asset.id().toString());
        events.append(asset.workspaceId(),"ASSET_REVOKED","CLIENT",Map.of("assetId",asset.id(),"revision",asset.revision()+1));
    }
    private UUID workspace(UUID owner,UUID id) {
        var rows=jdbc.queryForList("SELECT a.workspace_id FROM asset_object a JOIN learning_workspace w ON w.id=a.workspace_id JOIN app_user u ON u.id=w.owner_id JOIN asset_payload p ON p.asset_id=a.id WHERE a.id=? AND w.owner_id=? AND u.status='ACTIVE'",String.class,id.toString(),owner.toString());
        if(rows.isEmpty()) throw DomainException.missing();return UUID.fromString(rows.get(0));
    }
    private Asset asset(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        String status=rs.getString("status");UUID id=UUID.fromString(rs.getString("id"));
        return new Asset(id,UUID.fromString(rs.getString("workspace_id")),rs.getString("filename"),rs.getString("mime_type"),rs.getLong("byte_size"),rs.getString("content_hash"),rs.getInt("revision"),status,rs.getTimestamp("created_at").toInstant(),rs.getObject("pixel_width",Integer.class),rs.getObject("pixel_height",Integer.class),rs.getObject("duration_millis",Long.class),status.equals("READY")?"/api/v1/assets/"+id+"/download":null,rs.getString("validation_method"));
    }
    private static String safeName(String value,String extension) {
        String name=value==null?"asset":value.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        int dot=name.lastIndexOf('.');if(dot>0) name=name.substring(0,dot);
        name=name.replaceAll("[\\p{Cntrl}/\\\\<>:\"|?*]","").strip();if(name.isBlank() || name.equals(".") || name.equals("..")) name="asset";
        if(name.length()>110) name=name.substring(0,Character.isHighSurrogate(name.charAt(109))?109:110);return name+"."+extension;
    }
    private static void checkDeclaredType(String value,String actual) {
        if(value==null || value.isBlank()) return;String declared=value.toLowerCase(Locale.ROOT).split(";",2)[0].strip();
        if(Set.of("application/octet-stream","binary/octet-stream").contains(declared)) return;
        if(actual.equals("audio/wav") && Set.of("audio/x-wav","audio/wave","audio/vnd.wave").contains(declared)) return;
        if(actual.equals("image/jpeg") && declared.equals("image/jpg")) return;
        if(actual.equals("audio/mpeg") && declared.equals("audio/mp3")) return;
        if(!actual.equals(declared)) throw new DomainException("ASSET_MIME_MISMATCH",400,"The declared file type does not match its actual bytes");
    }
    public static String sha256(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException("SHA-256 is required",impossible);}
    }
}
