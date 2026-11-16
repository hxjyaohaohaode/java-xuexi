package io.typeforge.ingestion;

import static io.typeforge.ingestion.BatchImportModels.*;

import io.typeforge.identity.AccountClosureParticipant;
import io.typeforge.identity.ContentAccess;
import io.typeforge.operations.RuntimeCapabilityProvider;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** Parsing happens outside transactions; only explicit editor confirmation creates immutable captures. */
@Service
public final class BatchImportService implements RuntimeCapabilityProvider,AccountClosureParticipant {
    private record Upload(String name,byte[] bytes,String hash) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ContentAccess access;
    private final ManualIngestionService ingestion;
    private final JsonCodec json;
    private final Clock clock;
    private final Semaphore parsers=new Semaphore(2);
    public BatchImportService(JdbcTemplate jdbc,TransactionTemplate tx,ContentAccess access,ManualIngestionService ingestion,JsonCodec json,Clock clock) {
        this.jdbc=jdbc;this.tx=tx;this.access=access;this.ingestion=ingestion;this.json=json;this.clock=clock;
    }
    @Override public Set<String> enabledCapabilities() {return Set.of("BATCH_INGESTION");}
    public Batch preview(UUID actor,UUID source,String note,List<MultipartFile> files,String key) {
        if(source==null) throw DomainException.invalid("请选择已登记来源。");
        access.requireEditor(actor);ingestion.source(actor,source);ContentAccess.key(key);ContentAccess.text(note,2000);
        if(files==null || files.isEmpty() || files.size()>8) throw DomainException.invalid("一次选择1至8个文件。");
        if(!parsers.tryAcquire()) throw new DomainException("IMPORT_PARSER_BUSY",503,"文档解析正在处理其他请求，请稍后重试。");
        // The permit covers reads, hashing, parsing and persistence, not only the XML parser.
        // A rejected request must not first copy another 4 MiB into application memory.
        try {return admittedPreview(actor,source,note,files,key);} finally {parsers.release();}
    }
    private Batch admittedPreview(UUID actor,UUID source,String note,List<MultipartFile> files,String key) {
        var uploads=new ArrayList<Upload>();int total=0;
        for(MultipartFile file:files) {
            if(file==null || file.getSize()>BoundedDocumentText.MAX_FILE_BYTES) throw new DomainException("IMPORT_FILE_TOO_LARGE",413,"单个导入文件上限为1 MiB。");
            byte[] bytes;
            try(var input=file.getInputStream()) {bytes=input.readNBytes(BoundedDocumentText.MAX_FILE_BYTES+1);}
            catch(IOException failure) {throw DomainException.invalid("文件读取失败，请重新选择文件。");}
            if(bytes.length>BoundedDocumentText.MAX_FILE_BYTES || (total+=bytes.length)>4*1024*1024) throw new DomainException("IMPORT_BATCH_TOO_LARGE",413,"每批文件总量上限为4 MiB，单项上限1 MiB。");
            uploads.add(new Upload(BoundedDocumentText.fileName(file.getOriginalFilename()),bytes,hash(bytes)));
        }
        var command=Map.of("sourceId",source,"processingNote",note,"files",uploads.stream().map(u->Map.of("name",u.name(),"bytes",u.bytes().length,"hash",u.hash())).toList());
        Batch replay=tx.execute(status->{
            access.lockEditor(actor);expireOwner(actor);UUID previous=access.replay(actor,"previewImportBatch",key,command);
            if(previous!=null) return load(actor,previous,false);
            checkAdmission(actor);return null;
        });
        if(replay!=null) return replay;
        var items=new ArrayList<Item>();
        for(Upload upload:uploads) {
            try {
                var parsed=BoundedDocumentText.extract(upload.name(),upload.bytes());
                String cleaned=parsed.text().length()<=16000?parsed.text():"";
                String message=parsed.message()+(cleaned.isEmpty()?" 清洗稿上限16000字符，请自行编辑后再提交。":"");
                items.add(new Item(UUID.randomUUID(),upload.name(),parsed.mediaType(),upload.bytes().length,upload.hash(),json.sha256(parsed.text()),parsed.parser(),"READY",null,message,parsed.text(),cleaned,null));
            } catch(DomainException invalid) {
                items.add(new Item(UUID.randomUUID(),upload.name(),"application/octet-stream",upload.bytes().length,upload.hash(),null,"unsupported-or-invalid-v1","FAILED",invalid.code(),invalid.getMessage(),"","",null));
            }
        }
        return tx.execute(status->{
            access.lockEditor(actor);expireOwner(actor);ingestion.source(actor,source);
            UUID previous=access.replay(actor,"previewImportBatch",key,command);
            if(previous!=null) return load(actor,previous,false);
            checkAdmission(actor);
            UUID id=UUID.randomUUID();Instant now=clock.instant();
            jdbc.update("INSERT INTO import_batch(id,source_id,owner_id,revision,status,processing_note,created_at,expires_at) VALUES(?,?,?,1,'PREVIEW',?,?,?)",id.toString(),source.toString(),actor.toString(),note,Timestamp.from(now),Timestamp.from(now.plusSeconds(86400)));
            for(int i=0;i<items.size();i++) {
                Item item=items.get(i);
                jdbc.update("INSERT INTO import_batch_item(id,batch_id,source_id,ordinal_number,file_name,media_type,byte_size,file_hash,original_hash,parser_version,status,error_code,message,original_markdown,cleaned_markdown) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    item.id().toString(),id.toString(),source.toString(),i,item.fileName(),item.mediaType(),item.byteSize(),item.fileHash(),item.originalHash(),item.parser(),item.status(),item.errorCode(),item.message(),item.originalMarkdown(),item.cleanedMarkdown());
            }
            access.remember(actor,"previewImportBatch",key,command,id);
            access.audit(actor,"IMPORT_BATCH_PREVIEWED",id,Map.of("sourceId",source,"files",items.size(),"ready",items.stream().filter(i->i.status().equals("READY")).count(),"automaticPublish",false));
            return load(actor,id,false);
        });
    }
    public List<Summary> list(UUID actor,int limit) {
        ContentAccess.limit(limit);
        return tx.execute(status->{
            access.lockEditor(actor);expireOwner(actor);
            return jdbc.query("SELECT b.*,(SELECT COUNT(*) FROM import_batch_item i WHERE i.batch_id=b.id) item_count,(SELECT COUNT(*) FROM import_batch_item i WHERE i.batch_id=b.id AND i.status='READY') ready_count,(SELECT COUNT(*) FROM import_batch_item i WHERE i.batch_id=b.id AND i.status='FAILED') failed_count,(SELECT COUNT(*) FROM import_batch_item i WHERE i.batch_id=b.id AND i.status='COMMITTED') committed_count FROM import_batch b WHERE b.owner_id=? ORDER BY b.created_at DESC,b.id LIMIT ?",
                (rs,n)->new Summary(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("source_id")),rs.getInt("revision"),rs.getString("status"),rs.getString("processing_note"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),rs.getInt("item_count"),rs.getInt("ready_count"),rs.getInt("failed_count"),rs.getInt("committed_count")),actor.toString(),limit);
        });
    }
    public Batch get(UUID actor,UUID id) {
        return tx.execute(status->{access.lockEditor(actor);expireOwner(actor);return load(actor,id,false);});
    }
    public Batch commit(UUID actor,UUID id,CommitInput input,String key) {
        ContentAccess.key(key);
        if(input==null || input.expectedRevision()<1 || input.items()==null || input.items().isEmpty() || input.items().size()>8) throw DomainException.invalid("请选择1至8个成功解析的条目并提供预览版本。");
        var selections=new HashMap<UUID,Selection>();
        for(Selection item:input.items()) {
            if(item==null || item.itemId()==null || selections.put(item.itemId(),item)!=null) throw DomainException.invalid("导入条目不可重复。");
            ContentAccess.text(item.cleanedMarkdown(),16000);BoundedDocumentText.normalize(item.cleanedMarkdown());ContentAccess.text(item.processingNote(),4000);
        }
        return tx.execute(status->{
            access.lockEditor(actor);expireOwner(actor);
            var command=Map.of("batchId",id,"input",input);
            UUID previous=access.replay(actor,"commitImportBatch",key,command);
            if(previous!=null) return load(actor,previous,false);
            Batch batch=load(actor,id,true);requirePreview(batch,input.expectedRevision());
            Set<UUID> allowed=new HashSet<>();batch.items().stream().filter(item->item.status().equals("READY")).forEach(item->allowed.add(item.id()));
            if(!allowed.containsAll(selections.keySet())) throw DomainException.invalid("仅可提交属于本批次且成功解析的条目。");
            for(Item item:batch.items()) {
                Selection selected=selections.get(item.id());
                if(selected==null) continue;
                // Exact hash ties the editor confirmation to the stored extracted text, not a filename.
                if(!json.sha256(item.originalMarkdown()).equals(item.originalHash())) throw DomainException.conflict("预览正文校验失败，请重新导入原文件。");
                var capture=ingestion.capture(actor,batch.sourceId(),new IngestionModels.CaptureInput(item.originalMarkdown(),selected.cleanedMarkdown(),selected.processingNote()),"batch-"+json.sha256(id+"/"+item.id()).substring(0,58));
                jdbc.update("UPDATE import_batch_item SET status='COMMITTED',capture_id=?,cleaned_markdown=? WHERE id=? AND batch_id=? AND status='READY'",capture.id().toString(),BoundedDocumentText.normalize(selected.cleanedMarkdown()),item.id().toString(),id.toString());
            }
            jdbc.update("UPDATE import_batch SET status='COMMITTED',revision=revision+1 WHERE id=? AND status='PREVIEW'",id.toString());
            clearPreviewText(id);
            access.remember(actor,"commitImportBatch",key,command,id);
            access.audit(actor,"IMPORT_BATCH_COMMITTED",id,Map.of("sourceId",batch.sourceId(),"captures",selections.size(),"published",false));
            return load(actor,id,false);
        });
    }
    public Batch cancel(UUID actor,UUID id,CancelInput input,String key) {
        ContentAccess.key(key);if(input==null || input.expectedRevision()<1) throw DomainException.invalid("请提供当前预览版本。");
        return tx.execute(status->{
            access.lockEditor(actor);expireOwner(actor);
            var command=Map.of("batchId",id,"input",input);UUID previous=access.replay(actor,"cancelImportBatch",key,command);
            if(previous!=null) return load(actor,previous,false);
            Batch batch=load(actor,id,true);requirePreview(batch,input.expectedRevision());
            jdbc.update("UPDATE import_batch SET status='CANCELLED',revision=revision+1 WHERE id=?",id.toString());clearPreviewText(id);
            access.remember(actor,"cancelImportBatch",key,command,id);access.audit(actor,"IMPORT_BATCH_CANCELLED",id,Map.of("textRemoved",true));return load(actor,id,false);
        });
    }
    private Batch load(UUID actor,UUID id,boolean lock) {
        var batches=jdbc.query("SELECT * FROM import_batch WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),
            (rs,n)->new Batch(id,UUID.fromString(rs.getString("source_id")),actor,rs.getInt("revision"),rs.getString("status"),rs.getString("processing_note"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),List.of()),id.toString(),actor.toString());
        if(batches.isEmpty()) throw DomainException.missing();
        Batch batch=batches.get(0);
        List<Item> items=jdbc.query("SELECT * FROM import_batch_item WHERE batch_id=? ORDER BY ordinal_number",this::item,id.toString());
        if(batch.status().equals("PREVIEW")) for(Item item:items) if(item.status().equals("READY") && !json.sha256(item.originalMarkdown()).equals(item.originalHash())) throw DomainException.conflict("预览正文完整性校验失败。");
        return new Batch(id,batch.sourceId(),actor,batch.revision(),batch.status(),batch.processingNote(),batch.createdAt(),batch.expiresAt(),items);
    }
    private Item item(ResultSet rs,int row) throws SQLException {
        String capture=rs.getString("capture_id");
        return new Item(UUID.fromString(rs.getString("id")),rs.getString("file_name"),rs.getString("media_type"),rs.getInt("byte_size"),rs.getString("file_hash"),rs.getString("original_hash"),rs.getString("parser_version"),rs.getString("status"),rs.getString("error_code"),rs.getString("message"),rs.getString("original_markdown"),rs.getString("cleaned_markdown"),capture==null?null:UUID.fromString(capture));
    }
    private static void requirePreview(Batch batch,int revision) {
        if(!batch.status().equals("PREVIEW") || batch.revision()!=revision) throw DomainException.conflict("批次已被处理、已过期或版本发生变化，请重新读取。");
    }
    private void checkAdmission(UUID actor) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM import_batch WHERE owner_id=? AND status='PREVIEW'",Long.class,actor.toString())>=3)
            throw new DomainException("IMPORT_PREVIEW_LIMIT",429,"最多保留3个待确认批次，请提交或取消已有预览。");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM import_batch WHERE owner_id=? AND created_at>=?",Long.class,actor.toString(),Timestamp.from(clock.instant().minusSeconds(86400)))>=100)
            throw new DomainException("IMPORT_DAILY_LIMIT",429,"24小时内最多创建100个导入批次。");
    }
    private void clearPreviewText(UUID id) {
        jdbc.update("UPDATE import_batch_item SET status=CASE WHEN status='READY' THEN 'SKIPPED' ELSE status END,original_markdown='',cleaned_markdown='' WHERE batch_id=?",id.toString());
    }
    private void expireOwner(UUID owner) {
        var ids=jdbc.queryForList("SELECT id FROM import_batch WHERE owner_id=? AND status='PREVIEW' AND expires_at<=? ORDER BY id FOR UPDATE",String.class,owner.toString(),Timestamp.from(clock.instant()));
        for(String id:ids) expire(UUID.fromString(id));
    }
    private void expire(UUID id) {
        int changed=jdbc.update("UPDATE import_batch SET status='EXPIRED',revision=revision+1 WHERE id=? AND status='PREVIEW' AND expires_at<=?",id.toString(),Timestamp.from(clock.instant()));
        if(changed==1) clearPreviewText(id);
    }
    @Scheduled(fixedDelayString="${typeforge.ingestion.cleanup-delay-millis:60000}")
    public void clearExpiredPreviews() {
        var ids=jdbc.queryForList("SELECT id FROM import_batch WHERE status='PREVIEW' AND expires_at<=? ORDER BY expires_at,id LIMIT 100",String.class,Timestamp.from(clock.instant()));
        for(String id:ids) tx.executeWithoutResult(status->expire(UUID.fromString(id)));
    }
    @Override public void closeAccount(UUID owner) {
        var ids=jdbc.queryForList("SELECT id FROM import_batch WHERE owner_id=? ORDER BY id FOR UPDATE",String.class,owner.toString());
        for(String id:ids) {
            jdbc.update("UPDATE import_batch SET status='CANCELLED',revision=revision+1 WHERE id=? AND status='PREVIEW'",id);
            clearPreviewText(UUID.fromString(id));
        }
    }
    private static String hash(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException failure) {throw new IllegalStateException("SHA-256 is unavailable",failure);}
    }
}
