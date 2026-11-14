package io.typeforge.ingestion;

import static io.typeforge.identity.ContentAccess.text;

import io.typeforge.identity.ContentAccess;
import io.typeforge.ingestion.IngestionModels.Capture;
import io.typeforge.ingestion.IngestionModels.CaptureInput;
import io.typeforge.ingestion.IngestionModels.Source;
import io.typeforge.ingestion.IngestionModels.SourceInput;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** No HTTP client, filesystem read, parser execution or model is used for manually supplied source text. */
@Service
public final class ManualIngestionService implements KnowledgeIngestion {
    public static final String PROCESSOR_VERSION="manual-newlines-v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final ContentAccess access;
    private final Clock clock;
    public ManualIngestionService(JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json,ContentAccess access,Clock clock) {
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.access=access;this.clock=clock;
    }
    public Source register(UUID actor,SourceInput input,String key) {
        if(input==null) throw DomainException.invalid("A source registration is required");
        text(input.title(),255);text(input.edition(),128);text(input.usageNote(),512);text(input.url(),2048);
        URI uri;
        try { uri=URI.create(input.url()); } catch(IllegalArgumentException invalid) { throw DomainException.invalid("Source URL is invalid"); }
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) throw DomainException.invalid("Register an HTTPS source URL without credentials; URLs are not fetched");
        return tx.execute(status->{
            access.lockEditor(actor);
            UUID previous=access.replay(actor,"editorRegisterSource",key,input);
            if(previous!=null) return source(actor,previous);
            var same=jdbc.queryForList("SELECT id,title,usage_note FROM knowledge_source WHERE url=? AND edition=?",input.url(),input.edition());
            UUID id;
            if(!same.isEmpty()) {
                var row=same.get(0);
                if(!input.title().equals(row.get("title")) || !input.usageNote().equals(row.get("usage_note"))) throw DomainException.conflict("This source edition is already registered with different metadata; use a distinct edition");
                id=UUID.fromString(row.get("id").toString());
            } else {
                id=UUID.randomUUID();
                jdbc.update("INSERT INTO knowledge_source(id,title,url,edition,usage_note) VALUES(?,?,?,?,?)",id.toString(),input.title(),input.url(),input.edition(),input.usageNote());
                access.audit(actor,"SOURCE_REGISTERED",id,Map.of("automaticFetch",false,"captured",false));
            }
            access.remember(actor,"editorRegisterSource",key,input,id);
            return source(actor,id);
        });
    }
    public List<Source> sources(UUID actor,int limit) {
        access.requireEditor(actor);ContentAccess.limit(limit);
        return jdbc.query("SELECT s.*,(SELECT COUNT(*) FROM content_ingestion i WHERE i.source_id=s.id) capture_count FROM knowledge_source s ORDER BY s.created_at DESC,s.id LIMIT ?",this::sourceRow,limit);
    }
    public Source source(UUID actor,UUID id) {
        access.requireEditor(actor);
        var rows=jdbc.query("SELECT s.*,(SELECT COUNT(*) FROM content_ingestion i WHERE i.source_id=s.id) capture_count FROM knowledge_source s WHERE s.id=?",this::sourceRow,id.toString());
        if(rows.isEmpty()) throw DomainException.missing();
        return rows.get(0);
    }
    private Source sourceRow(ResultSet rs,int row) throws SQLException {
        return new Source(UUID.fromString(rs.getString("id")),rs.getString("title"),rs.getString("url"),rs.getString("edition"),rs.getString("usage_note"),rs.getInt("capture_count"));
    }
    @Override public UUID importDraft(ImportRequest request) {
        if(request==null) throw DomainException.invalid("A manual import is required");
        return capture(request.editorId(),request.sourceId(),new CaptureInput(request.originalMarkdown(),request.cleanedMarkdown(),request.processingNote()),request.idempotencyKey()).id();
    }
    public Capture capture(UUID actor,UUID source,CaptureInput input,String key) {
        if(source==null || input==null) throw DomainException.invalid("Source and manual text are required");
        String original=normalize(input.originalMarkdown(),32000),cleaned=normalize(input.cleanedMarkdown(),16000);
        text(input.processingNote(),4000);
        return tx.execute(status->{
            access.lockEditor(actor);source(actor,source);
            var command=Map.of("sourceId",source,"input",input);
            UUID previous=access.replay(actor,"editorCaptureSource",key,command);
            if(previous!=null) return capture(actor,previous);
            String rawHash=json.sha256(original),cleanHash=json.sha256(cleaned);
            var duplicate=jdbc.queryForList("SELECT id FROM content_ingestion WHERE source_id=? AND original_hash=? AND cleaned_hash=?",String.class,source.toString(),rawHash,cleanHash);
            UUID id;
            if(!duplicate.isEmpty()) id=UUID.fromString(duplicate.get(0));
            else {
                id=UUID.randomUUID();
                jdbc.update("INSERT INTO content_ingestion(id,source_id,author_id,original_markdown,cleaned_markdown,original_hash,cleaned_hash,processor_version,processing_note,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id.toString(),source.toString(),actor.toString(),original,cleaned,rawHash,cleanHash,PROCESSOR_VERSION,input.processingNote(),Timestamp.from(clock.instant()));
                // The original source row records its first actual capture; subsequent captures are separate immutable receipts.
                jdbc.update("UPDATE knowledge_source SET raw_hash=?,collected_at=? WHERE id=? AND raw_hash IS NULL",rawHash,Timestamp.from(clock.instant()),source.toString());
                access.audit(actor,"SOURCE_MANUALLY_CAPTURED",id,Map.of("sourceId",source,"originalHash",rawHash,"cleanedHash",cleanHash,"automaticFetch",false));
            }
            access.remember(actor,"editorCaptureSource",key,command,id);
            return capture(actor,id);
        });
    }
    public List<Capture> captures(UUID actor,UUID source,int limit) {
        source(actor,source);ContentAccess.limit(limit);
        return jdbc.query("SELECT * FROM content_ingestion WHERE source_id=? ORDER BY created_at DESC,id LIMIT ?",this::captureRow,source.toString(),limit);
    }
    public Capture capture(UUID actor,UUID id) {
        access.requireEditor(actor);
        var rows=jdbc.query("SELECT * FROM content_ingestion WHERE id=?",this::captureRow,id.toString());
        if(rows.isEmpty()) throw DomainException.missing();
        return rows.get(0);
    }
    private Capture captureRow(ResultSet rs,int row) throws SQLException {
        var capture=new Capture(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("source_id")),UUID.fromString(rs.getString("author_id")),
            rs.getString("original_markdown"),rs.getString("cleaned_markdown"),rs.getString("original_hash"),rs.getString("cleaned_hash"),
            rs.getString("processor_version"),rs.getString("processing_note"),rs.getTimestamp("created_at").toInstant());
        if(!json.sha256(capture.originalMarkdown()).equals(capture.originalHash()) || !json.sha256(capture.cleanedMarkdown()).equals(capture.cleanedHash())) throw DomainException.conflict("Captured source content failed its integrity check");
        return capture;
    }
    private static String normalize(String value,int maximum) {
        text(value,maximum);
        String normalized=value.replace("\r\n","\n").replace('\r','\n');
        if(normalized.startsWith("\uFEFF")) normalized=normalized.substring(1);
        return text(normalized,maximum); // Indentation and fenced code are deliberately preserved.
    }
}
