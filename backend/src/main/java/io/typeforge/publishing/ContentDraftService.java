package io.typeforge.publishing;

import static io.typeforge.identity.ContentAccess.text;
import static io.typeforge.publishing.ContentWorkbenchModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.typeforge.ingestion.ManualIngestionService;
import io.typeforge.ingestion.IngestionModels.SourceInput;
import io.typeforge.ingestion.IngestionModels.SourceReference;
import io.typeforge.identity.ContentAccess;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Append-only editorial revisions, exact provenance and one terminal independent decision per revision. */
@Service
public final class ContentDraftService {
    public static final String CHECKER_VERSION="content-workbench-v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final ContentAccess access;
    private final ManualIngestionService ingestion;
    private final Clock clock;
    public ContentDraftService(JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json,ContentAccess access,ManualIngestionService ingestion,Clock clock) {
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.access=access;this.ingestion=ingestion;this.clock=clock;
    }
    public Concept createConcept(UUID actor,ConceptInput input,String key) {
        if(input==null || input.id()==null || input.code()==null || !input.code().matches("[A-Z][A-Z0-9_]{1,15}")) throw DomainException.invalid("A stable concept id and uppercase code are required");
        text(input.title(),128);uniqueIds(input.prerequisiteIds(),0,16);
        if(input.prerequisiteIds().contains(input.id())) throw DomainException.invalid("A concept cannot require itself");
        return tx.execute(status->{
            access.lockEditor(actor);
            UUID previous=access.replay(actor,"editorCreateConcept",key,input);
            if(previous!=null) return concept(actor,previous);
            if(!jdbc.queryForList("SELECT id FROM concept WHERE id=? OR code=?",input.id().toString(),input.code()).isEmpty()) throw DomainException.conflict("Concept identity or code is already registered");
            // A new identity may point only to existing concepts, so this insertion cannot create a cycle.
            input.prerequisiteIds().forEach(id->concept(actor,id));
            jdbc.update("INSERT INTO concept(id,code,title) VALUES(?,?,?)",input.id().toString(),input.code(),input.title());
            input.prerequisiteIds().forEach(id->jdbc.update("INSERT INTO concept_edge(concept_id,prerequisite_id) VALUES(?,?)",input.id().toString(),id.toString()));
            access.remember(actor,"editorCreateConcept",key,input,input.id());
            access.audit(actor,"CONCEPT_REGISTERED",input.id(),Map.of("code",input.code()));
            return concept(actor,input.id());
        });
    }
    public List<Concept> concepts(UUID actor,int limit) {
        access.requireEditor(actor);ContentAccess.limit(limit);
        return jdbc.queryForList("SELECT id FROM concept ORDER BY code LIMIT ?",String.class,limit).stream().map(id->concept(actor,UUID.fromString(id))).toList();
    }
    public Concept concept(UUID actor,UUID id) {
        access.requireEditor(actor);
        var rows=jdbc.query("SELECT id,code,title FROM concept WHERE id=?",(rs,n)->new Concept(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),
            jdbc.queryForList("SELECT prerequisite_id FROM concept_edge WHERE concept_id=? ORDER BY prerequisite_id",String.class,id.toString()).stream().map(UUID::fromString).toList()),id.toString());
        if(rows.isEmpty()) throw DomainException.missing();
        return rows.get(0);
    }
    public Chunk createChunk(UUID actor,ChunkInput input,String key) {
        if(input==null || input.id()==null || input.conceptId()==null) throw DomainException.invalid("Knowledge id and concept are required");
        text(input.title(),255);text(input.body(),16000);validRevision(input.revision(),input.baseRevision());
        if(input.sources()==null || input.sources().isEmpty() || input.sources().size()>10) throw DomainException.invalid("Supply 1..10 pinned source captures");
        var sourceIds=new HashSet<UUID>();
        for(var source:input.sources()) {
            if(source==null || source.sourceId()==null || source.ingestionId()==null || !sourceIds.add(source.sourceId())) throw DomainException.invalid("Each knowledge source must have one distinct captured receipt");
            text(source.section(),255);
        }
        return tx.execute(status->{
            access.lockEditor(actor);
            UUID previous=access.replay(actor,"editorCreateChunk",key,input);
            if(previous!=null) return chunk(actor,previous,input.revision());
            currentRevision("knowledge_chunk",input.id(),input.baseRevision());concept(actor,input.conceptId());
            for(var source:input.sources()) {
                ingestion.source(actor,source.sourceId());
                if(!ingestion.capture(actor,source.ingestionId()).sourceId().equals(source.sourceId())) throw DomainException.invalid("Capture belongs to a different source");
            }
            String bodyHash=json.sha256(input.body());
            if(!jdbc.queryForList("SELECT id FROM knowledge_chunk WHERE concept_id=? AND content_hash=? AND id<>?",input.conceptId().toString(),bodyHash,input.id().toString()).isEmpty()) throw DomainException.conflict("Exact duplicate knowledge already exists for this concept");
            jdbc.update("INSERT INTO knowledge_chunk(id,revision,concept_id,title,body,content_hash,status,author_id) VALUES(?,?,?,?,?,?,'DRAFT',?)",
                input.id().toString(),input.revision(),input.conceptId().toString(),input.title(),input.body(),bodyHash,actor.toString());
            for(var source:input.sources()) {
                jdbc.update("INSERT INTO chunk_source(chunk_id,chunk_revision,source_id,section_ref) VALUES(?,?,?,?)",input.id().toString(),input.revision(),source.sourceId().toString(),source.section());
                jdbc.update("INSERT INTO chunk_ingestion(chunk_id,chunk_revision,source_id,ingestion_id) VALUES(?,?,?,?)",input.id().toString(),input.revision(),source.sourceId().toString(),source.ingestionId().toString());
            }
            access.remember(actor,"editorCreateChunk",key,input,input.id());
            access.audit(actor,"KNOWLEDGE_REVISION_CREATED",input.id(),Map.of("revision",input.revision(),"bodyHash",bodyHash));
            return chunk(actor,input.id(),input.revision());
        });
    }
    public Chunk chunk(UUID actor,UUID id,int revision) {
        access.requireEditor(actor);positiveRevision(revision);
        var sources=jdbc.query("SELECT cs.source_id,cs.section_ref,ci.ingestion_id FROM chunk_source cs LEFT JOIN chunk_ingestion ci ON ci.chunk_id=cs.chunk_id AND ci.chunk_revision=cs.chunk_revision AND ci.source_id=cs.source_id WHERE cs.chunk_id=? AND cs.chunk_revision=? ORDER BY cs.source_id",
            (rs,n)->new SourceReference(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3)==null?null:UUID.fromString(rs.getString(3))),id.toString(),revision);
        var rows=jdbc.query("SELECT * FROM knowledge_chunk WHERE id=? AND revision=?",(rs,n)->new Chunk(id,revision,UUID.fromString(rs.getString("concept_id")),rs.getString("title"),rs.getString("body"),rs.getString("content_hash"),"",rs.getString("status"),UUID.fromString(rs.getString("author_id")),sources),id.toString(),revision);
        if(rows.isEmpty()) throw DomainException.missing();
        Chunk value=rows.get(0);
        if(!json.sha256(value.body()).equals(value.contentHash())) throw DomainException.conflict("Knowledge body failed its integrity check");
        var lineage=new ArrayList<Object>();
        for(var reference:sources) {
            var source=ingestion.source(actor,reference.sourceId());
            var entry=new LinkedHashMap<String,Object>();
            entry.put("source",new SourceInput(source.title(),source.url(),source.edition(),source.usageNote()));
            entry.put("sourceId",source.id());entry.put("section",reference.section());entry.put("ingestionId",reference.ingestionId());
            entry.put("captureHash",reference.ingestionId()==null?"NOT_CAPTURED":json.hash(ingestion.capture(actor,reference.ingestionId())));
            lineage.add(entry);
        }
        String hash=json.hash(Map.of("id",id,"revision",revision,"conceptId",value.conceptId(),"title",value.title(),"bodyHash",value.contentHash(),"authorId",value.authorId(),"sources",lineage));
        return new Chunk(id,revision,value.conceptId(),value.title(),value.body(),value.contentHash(),hash,value.status(),value.authorId(),sources);
    }
    public Exercise createExercise(UUID actor,ExerciseInput input,String key) {
        if(input==null || input.id()==null || input.familyId()==null) throw DomainException.invalid("Exercise and family identities are required");
        validRevision(input.revision(),input.baseRevision());text(input.prompt(),16000);uniqueIds(input.conceptIds(),1,16);
        validateAnswer(input.kind(),input.answerSpec());refs(input.knowledgeRefs(),1,16);
        if(input.difficulty()<1 || input.difficulty()>3) throw DomainException.invalid("Difficulty must be 1..3");
        return tx.execute(status->{
            access.lockEditor(actor);
            UUID previous=access.replay(actor,"editorCreateExercise",key,input);
            if(previous!=null) return exercise(actor,previous,input.revision());
            currentRevision("exercise",input.id(),input.baseRevision());
            if(input.baseRevision()>0 && !jdbc.queryForObject("SELECT family_id FROM exercise WHERE id=? AND revision=?",String.class,input.id().toString(),input.baseRevision()).equals(input.familyId().toString())) throw DomainException.conflict("An exercise revision cannot change its learning-evidence family");
            input.conceptIds().forEach(id->concept(actor,id));
            var supported=new HashSet<UUID>();
            for(var ref:input.knowledgeRefs()) supported.add(chunk(actor,ref.id(),ref.revision()).conceptId());
            if(!supported.containsAll(input.conceptIds())) throw DomainException.invalid("Every exercise concept needs an exact supporting knowledge revision");
            jdbc.update("INSERT INTO exercise(id,revision,family_id,kind,difficulty,prompt,answer_spec,rubric_version,status,author_id) VALUES(?,?,?,?,?,?,?,'choice-v1','DRAFT',?)",
                input.id().toString(),input.revision(),input.familyId().toString(),input.kind(),input.difficulty(),input.prompt(),json.write(input.answerSpec()),actor.toString());
            List<UUID> concepts=input.conceptIds().stream().sorted().toList();
            BigDecimal share=BigDecimal.ONE.divide(BigDecimal.valueOf(concepts.size()),4,RoundingMode.DOWN);
            for(int i=0;i<concepts.size();i++) {
                BigDecimal weight=i==concepts.size()-1?BigDecimal.ONE.subtract(share.multiply(BigDecimal.valueOf(i))):share;
                jdbc.update("INSERT INTO exercise_concept(exercise_id,exercise_revision,concept_id,weight) VALUES(?,?,?,?)",input.id().toString(),input.revision(),concepts.get(i).toString(),weight);
            }
            for(var ref:input.knowledgeRefs()) jdbc.update("INSERT INTO exercise_knowledge(exercise_id,exercise_revision,chunk_id,chunk_revision) VALUES(?,?,?,?)",input.id().toString(),input.revision(),ref.id().toString(),ref.revision());
            access.remember(actor,"editorCreateExercise",key,input,input.id());
            access.audit(actor,"EXERCISE_REVISION_CREATED",input.id(),Map.of("revision",input.revision(),"kind",input.kind()));
            return exercise(actor,input.id(),input.revision());
        });
    }
    public Exercise exercise(UUID actor,UUID id,int revision) {
        access.requireEditor(actor);positiveRevision(revision);
        var concepts=jdbc.queryForList("SELECT concept_id FROM exercise_concept WHERE exercise_id=? AND exercise_revision=? ORDER BY concept_id",String.class,id.toString(),revision).stream().map(UUID::fromString).toList();
        var refs=jdbc.query("SELECT chunk_id,chunk_revision FROM exercise_knowledge WHERE exercise_id=? AND exercise_revision=? ORDER BY chunk_id",(rs,n)->new ContentRef(UUID.fromString(rs.getString(1)),rs.getInt(2)),id.toString(),revision);
        var rows=jdbc.query("SELECT * FROM exercise WHERE id=? AND revision=?",(rs,n)->new Exercise(id,revision,UUID.fromString(rs.getString("family_id")),rs.getString("kind"),rs.getInt("difficulty"),rs.getString("prompt"),concepts,storedAnswer(rs.getObject("answer_spec"),rs.getString("kind")),refs,"",rs.getString("status"),UUID.fromString(rs.getString("author_id"))),id.toString(),revision);
        if(rows.isEmpty()) throw DomainException.missing();
        Exercise value=rows.get(0);
        var hashInput=new LinkedHashMap<String,Object>();
        hashInput.put("id",id);hashInput.put("revision",revision);hashInput.put("familyId",value.familyId());hashInput.put("kind",value.kind());hashInput.put("difficulty",value.difficulty());
        // Hash the complete stored specification, including explicit legacy demo provenance.
        // Selecting the editable fields for the DTO must never discard integrity evidence.
        hashInput.put("prompt",value.prompt());hashInput.put("answer",json.tree(jdbc.queryForObject("SELECT answer_spec FROM exercise WHERE id=? AND revision=?",Object.class,id.toString(),revision)));hashInput.put("authorId",value.authorId());
        hashInput.put("rubricVersion",jdbc.queryForObject("SELECT rubric_version FROM exercise WHERE id=? AND revision=?",String.class,id.toString(),revision));
        hashInput.put("concepts",jdbc.query("SELECT concept_id,weight FROM exercise_concept WHERE exercise_id=? AND exercise_revision=? ORDER BY concept_id",(rs,n)->Map.of("id",rs.getString(1),"weight",rs.getBigDecimal(2).stripTrailingZeros()),id.toString(),revision));
        hashInput.put("knowledge",refs.stream().map(ref->Map.of("id",ref.id(),"revision",ref.revision(),"reviewHash",chunk(actor,ref.id(),ref.revision()).reviewHash())).toList());
        return new Exercise(id,revision,value.familyId(),value.kind(),value.difficulty(),value.prompt(),concepts,value.answerSpec(),refs,json.hash(hashInput),value.status(),value.authorId());
    }
    private ChoiceAnswer storedAnswer(Object stored,String kind) {
        try {
            JsonNode raw=json.tree(stored);
            if(!(raw instanceof ObjectNode object)) throw DomainException.conflict("Stored answer specification needs a supported editorial revision");
            ObjectNode editable=object.deepCopy();
            // v0.3 demo questions have this explicit, non-authoritative metadata. Permit viewing
            // their known shape; the independent review and capture gates still reject publication.
            if(editable.has("origin")) {
                if(!editable.path("origin").isTextual() || !"demo-authored-code-reviewed".equals(editable.path("origin").asText())) throw DomainException.conflict("Stored answer provenance is not supported by this workbench");
                editable.remove("origin");
            }
            ChoiceAnswer answer=json.read(json.write(editable),ChoiceAnswer.class);
            validateAnswer(kind,answer);
            return answer;
        } catch(IllegalArgumentException invalid) {
            throw DomainException.conflict("Stored answer specification needs a supported editorial revision");
        } catch(DomainException invalid) {
            throw DomainException.conflict("Stored answer specification or provenance needs a supported editorial revision");
        }
    }
    public List<DraftSummary> drafts(UUID actor,String kind,String status,int limit) {
        access.requireEditor(actor);ContentAccess.limit(limit);
        String table=table(kind),title="CHUNK".equals(kind)?"title":"prompt";
        if(status!=null && !Set.of("DRAFT","REVIEWED","RETIRED").contains(status)) throw DomainException.invalid("Unsupported content status");
        var args=new ArrayList<Object>();if(status!=null) args.add(status);args.add(limit);
        return jdbc.query("SELECT c.id,c.revision,c."+title+" AS title,c.status,c.author_id FROM "+table+" c WHERE NOT EXISTS (SELECT 1 FROM "+table+" newer WHERE newer.id=c.id AND newer.revision>c.revision)"+(status==null?"":" AND c.status=?")+" ORDER BY c.created_at DESC,c.id LIMIT ?",
            (rs,n)->new DraftSummary(UUID.fromString(rs.getString("id")),rs.getInt("revision"),rs.getString("title"),rs.getString("status"),UUID.fromString(rs.getString("author_id"))),args.toArray());
    }
    public Review review(UUID actor,ReviewInput input,String key) {
        if(input==null || input.targetId()==null) throw DomainException.invalid("A content review is required");
        table(input.targetKind());positiveRevision(input.targetRevision());ContentAccess.hash(input.expectedContentHash());text(input.note(),4000);
        if(!Set.of("APPROVED","REJECTED").contains(Objects.toString(input.decision(),""))) throw DomainException.invalid("Unsupported review decision");
        return tx.execute(status->{
            List<String> roles=access.lockEditor(actor);
            UUID previous=access.replay(actor,"editorReviewContent",key,input);
            if(previous!=null) return review(actor,previous);
            UUID author;String hash;String contentStatus;
            if(input.targetKind().equals("CHUNK")) {
                Chunk chunk=chunk(actor,input.targetId(),input.targetRevision());author=chunk.authorId();hash=chunk.reviewHash();contentStatus=chunk.status();
                if(input.decision().equals("APPROVED")) validateChunk(chunk);
            } else {
                Exercise exercise=exercise(actor,input.targetId(),input.targetRevision());author=exercise.authorId();hash=exercise.reviewHash();contentStatus=exercise.status();
                if(input.decision().equals("APPROVED")) {
                    validateAnswer(exercise.kind(),exercise.answerSpec());
                    if(exercise.knowledgeRefs().isEmpty()) throw DomainException.conflict("Exercise needs independently reviewed knowledge evidence");
                    exercise.knowledgeRefs().forEach(ref->requireApproved(actor,"CHUNK",ref.id(),ref.revision()));
                }
            }
            if(author.equals(actor)) throw new DomainException("INDEPENDENT_REVIEW_REQUIRED",403,"The author cannot review their own revision");
            if(!contentStatus.equals("DRAFT")) throw DomainException.conflict("This revision already has a terminal review state; create a new revision");
            if(!hash.equals(input.expectedContentHash())) throw DomainException.conflict("Content or source lineage changed after review preview");
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO content_review(id,target_kind,target_id,target_revision,author_id,reviewer_id,decision,note,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id.toString(),input.targetKind(),input.targetId().toString(),input.targetRevision(),author.toString(),actor.toString(),input.decision(),input.note(),Timestamp.from(clock.instant()));
            jdbc.update("INSERT INTO content_review_evidence(review_id,reviewed_hash,checker_version,reviewer_roles) VALUES(?,?,?,?)",id.toString(),hash,CHECKER_VERSION,json.write(roles));
            jdbc.update("UPDATE "+table(input.targetKind())+" SET status=? WHERE id=? AND revision=? AND status='DRAFT'",input.decision().equals("APPROVED")?"REVIEWED":"RETIRED",input.targetId().toString(),input.targetRevision());
            access.remember(actor,"editorReviewContent",key,input,id);
            access.audit(actor,"CONTENT_REVIEWED",id,Map.of("targetId",input.targetId(),"revision",input.targetRevision(),"decision",input.decision(),"reviewedHash",hash));
            return review(actor,id);
        });
    }
    public Review review(UUID actor,UUID id) {
        access.requireEditor(actor);
        var rows=jdbc.query("SELECT r.*,e.reviewed_hash FROM content_review r JOIN content_review_evidence e ON e.review_id=r.id WHERE r.id=?",
            (rs,n)->new Review(id,rs.getString("target_kind"),UUID.fromString(rs.getString("target_id")),rs.getInt("target_revision"),UUID.fromString(rs.getString("author_id")),UUID.fromString(rs.getString("reviewer_id")),rs.getString("reviewed_hash"),rs.getString("decision"),rs.getString("note"),rs.getTimestamp("created_at").toInstant()),id.toString());
        if(rows.isEmpty()) throw DomainException.missing();return rows.get(0);
    }
    public List<Review> reviews(UUID actor,int limit) {
        access.requireEditor(actor);ContentAccess.limit(limit);
        return jdbc.queryForList("SELECT r.id FROM content_review r JOIN content_review_evidence e ON e.review_id=r.id ORDER BY r.created_at DESC,r.id LIMIT ?",String.class,limit).stream().map(id->review(actor,UUID.fromString(id))).toList();
    }
    public Review requireApproved(UUID actor,String kind,UUID id,int revision) {
        String hash;UUID author;String state;
        if(kind.equals("CHUNK")) { var content=chunk(actor,id,revision);validateChunk(content);hash=content.reviewHash();author=content.authorId();state=content.status(); }
        else { var content=exercise(actor,id,revision);validateAnswer(content.kind(),content.answerSpec());hash=content.reviewHash();author=content.authorId();state=content.status(); }
        var rows=jdbc.queryForList("SELECT r.id FROM content_review r JOIN content_review_evidence e ON e.review_id=r.id WHERE r.target_kind=? AND r.target_id=? AND r.target_revision=? AND r.author_id=? AND r.author_id<>r.reviewer_id AND r.decision='APPROVED' AND e.reviewed_hash=? AND e.checker_version=?",
            String.class,kind,id.toString(),revision,author.toString(),hash,CHECKER_VERSION);
        if(!"REVIEWED".equals(state) || rows.size()!=1) throw DomainException.conflict("Every exact revision needs an independent approval of its current content and lineage; demo status is insufficient");
        return review(actor,UUID.fromString(rows.get(0)));
    }
    private static void validateChunk(Chunk chunk) {
        if(chunk.sources().isEmpty() || chunk.sources().stream().anyMatch(source->source.ingestionId()==null)) throw DomainException.conflict("Knowledge approval requires registered and actually captured source evidence");
    }
    private void currentRevision(String table,UUID id,int expected) {
        Integer current=jdbc.queryForObject("SELECT MAX(revision) FROM "+table+" WHERE id=?",Integer.class,id.toString());
        if((current==null?0:current)!=expected) throw DomainException.conflict("Content revision changed; reload the latest version");
    }
    public static void refs(List<ContentRef> refs,int minimum,int maximum) {
        if(refs==null || refs.size()<minimum || refs.size()>maximum) throw DomainException.invalid("Content reference count is outside its supported range");
        var ids=new HashSet<UUID>();
        for(var ref:refs) if(ref==null || ref.id()==null || ref.revision()<1 || !ids.add(ref.id())) throw DomainException.invalid("Pin one valid revision per content identity");
    }
    private static void validRevision(int revision,int base) { if(base<0 || base==Integer.MAX_VALUE || revision!=base+1) throw DomainException.invalid("A new revision must equal baseRevision plus one"); }
    private static void positiveRevision(int revision) { if(revision<1) throw DomainException.invalid("Content revision must be positive"); }
    private static String table(String kind) { return switch(Objects.toString(kind,"")) { case "CHUNK"->"knowledge_chunk";case "EXERCISE"->"exercise";default->throw DomainException.invalid("Unsupported content kind"); }; }
    private static void uniqueIds(List<UUID> values,int minimum,int maximum) {
        if(values==null || values.size()<minimum || values.size()>maximum || values.stream().anyMatch(Objects::isNull) || new HashSet<>(values).size()!=values.size()) throw DomainException.invalid("Supply a bounded list of distinct identities");
    }
    public static void validateAnswer(String kind,ChoiceAnswer answer) {
        if(!Set.of("SINGLE","MULTIPLE").contains(Objects.toString(kind,""))) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"Only validated objective SINGLE and MULTIPLE exercises can be published by this workbench");
        if(answer==null || answer.options()==null || answer.options().size()<2 || answer.options().size()>8 || answer.optionIds()==null || answer.optionIds().isEmpty() || answer.optionIds().size()>8) throw DomainException.invalid("Choice answer requires 2..8 options and valid expected option ids");
        var offered=new HashSet<String>();
        for(var option:answer.options()) {
            if(option==null || option.id()==null || !option.id().matches("[A-Za-z0-9_-]{1,16}") || !offered.add(option.id())) throw DomainException.invalid("Option ids must be short and unique");
            text(option.text(),2000);
        }
        if(new HashSet<>(answer.optionIds()).size()!=answer.optionIds().size() || !offered.containsAll(answer.optionIds()) || kind.equals("SINGLE") && answer.optionIds().size()!=1) throw DomainException.invalid("Expected choices must belong to offered options and match the question kind");
        text(answer.explanation(),12000);
        if(answer.misconceptions()==null || answer.misconceptions().size()>16) throw DomainException.invalid("Supply at most 16 misconception codes");
        for(String code:answer.misconceptions()) if(code==null || !code.matches("[A-Za-z0-9_.:-]{1,64}")) throw DomainException.invalid("Invalid misconception code");
    }
}
