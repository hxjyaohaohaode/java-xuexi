package io.typeforge.publishing;

import static io.typeforge.publishing.ContentWorkbenchModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.identity.ContentAccess;
import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic publication never rewrites historical release membership or migrates personal workspaces. */
@Service
public final class ReleaseWorkbenchService implements ContentPublisher {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final ContentAccess access;
    private final ContentDraftService drafts;
    private final Clock clock;
    public ReleaseWorkbenchService(JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json,ContentAccess access,ContentDraftService drafts,Clock clock) {
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.access=access;this.drafts=drafts;this.clock=clock;
    }
    public Release create(UUID actor,ReleaseInput input,String key) {
        if(input==null || input.version()==null || !input.version().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}") || input.version().startsWith("demo-")) throw DomainException.invalid("Use a distinct 1..32 character formal release version; demo versions are reserved");
        ContentDraftService.refs(input.chunks(),1,1000);ContentDraftService.refs(input.exercises(),1,1000);
        return tx.execute(status->{
            access.lockEditor(actor);
            UUID previous=access.replay(actor,"editorBuildRelease",key,input);
            if(previous!=null) return get(actor,previous);
            if(!jdbc.queryForList("SELECT id FROM content_release WHERE version=?",input.version()).isEmpty()) throw DomainException.conflict("Release version is already in use");
            var concepts=new LinkedHashMap<UUID,Concept>();
            for(var ref:input.chunks()) collectConcept(actor,drafts.chunk(actor,ref.id(),ref.revision()).conceptId(),concepts,new HashSet<>());
            for(var ref:input.exercises()) for(UUID concept:drafts.exercise(actor,ref.id(),ref.revision()).conceptIds()) collectConcept(actor,concept,concepts,new HashSet<>());
            var frozen=concepts.values().stream().sorted(Comparator.comparing(Concept::code)).toList();
            JsonNode manifest=manifest(actor,input.version(),input.chunks(),input.exercises(),frozen);
            String hash=json.hash(manifest);
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO content_release(id,version,status,manifest_hash,created_by) VALUES(?,?,'DRAFT',?,?)",id.toString(),input.version(),hash,actor.toString());
            for(var concept:frozen) jdbc.update("INSERT INTO release_concept(release_id,concept_id,title,prerequisite_ids) VALUES(?,?,?,?)",id.toString(),concept.id().toString(),concept.title(),json.write(concept.prerequisiteIds()));
            for(var ref:input.chunks()) jdbc.update("INSERT INTO release_chunk(release_id,chunk_id,chunk_revision) VALUES(?,?,?)",id.toString(),ref.id().toString(),ref.revision());
            for(var ref:input.exercises()) jdbc.update("INSERT INTO release_exercise(release_id,exercise_id,exercise_revision) VALUES(?,?,?)",id.toString(),ref.id().toString(),ref.revision());
            jdbc.update("INSERT INTO release_manifest(release_id,manifest_json,checker_version) VALUES(?,?,?)",id.toString(),json.write(manifest),ContentDraftService.CHECKER_VERSION);
            // BM25 reads the bounded SQL corpus directly. READY attests the checked SQL membership;
            // this is not an offline vector index, a fabricated file or a completed model evaluation.
            jdbc.update("INSERT INTO index_snapshot(id,release_id,index_version,tokenizer_version,content_hash,storage_key,status) VALUES(?,?,?,?,?,?,'READY')",
                UUID.randomUUID().toString(),id.toString(),LocalKnowledgeSearch.INDEX_VERSION,"script-boundaries-v2",hash,"sql:release/"+id);
            access.remember(actor,"editorBuildRelease",key,input,id);
            access.audit(actor,"FORMAL_RELEASE_BUILT",id,Map.of("manifestHash",hash,"chunks",input.chunks().size(),"exercises",input.exercises().size(),"corpus","SQL_BM25","reviewPolicy","AUTHOR_AND_INDEPENDENT_EDITOR"));
            return get(actor,id);
        });
    }
    public List<Release> releases(UUID actor,int limit) {
        access.requireEditor(actor);ContentAccess.limit(limit);
        return jdbc.queryForList("SELECT r.id FROM content_release r JOIN release_manifest m ON m.release_id=r.id ORDER BY r.created_at DESC,r.id LIMIT ?",String.class,limit).stream().map(id->get(actor,UUID.fromString(id))).toList();
    }
    public Release get(UUID actor,UUID id) {
        access.requireEditor(actor);
        var rows=jdbc.query("SELECT r.*,m.manifest_json FROM content_release r JOIN release_manifest m ON m.release_id=r.id WHERE r.id=?",(rs,n)->{
            var index=jdbc.queryForList("SELECT status FROM index_snapshot WHERE release_id=? AND index_version=?",String.class,id.toString(),LocalKnowledgeSearch.INDEX_VERSION);
            var active=jdbc.queryForList("SELECT release_id,revision FROM active_release WHERE singleton_id=1");
            Timestamp published=rs.getTimestamp("published_at");
            return new Release(id,rs.getString("version"),rs.getString("status"),rs.getString("manifest_hash"),UUID.fromString(rs.getString("created_by")),published==null?null:published.toInstant(),
                json.tree(rs.getObject("manifest_json")),index.isEmpty()?"UNAVAILABLE":index.get(0),LocalKnowledgeSearch.INDEX_VERSION,
                !active.isEmpty() && id.toString().equals(active.get(0).get("release_id")),active.isEmpty()?0:((Number)active.get(0).get("revision")).intValue());
        },id.toString());
        if(rows.isEmpty()) throw DomainException.missing();return rows.get(0);
    }
    @Override public void publish(PublishCommand command) {
        if(command==null) throw DomainException.invalid("A publish command is required");
        publish(command.editorId(),command.releaseId(),new PublishInput(command.expectedManifestHash(),command.expectedActiveRevision()),command.idempotencyKey());
    }
    public Release publish(UUID actor,UUID id,PublishInput input,String key) { return activate(actor,id,input,key,false); }
    public Release activateHistorical(UUID actor,UUID id,PublishInput input,String key) { return activate(actor,id,input,key,true); }
    private Release activate(UUID actor,UUID id,PublishInput input,String key,boolean historical) {
        if(id==null || input==null || input.expectedActiveRevision()<0 || input.expectedActiveRevision()==Integer.MAX_VALUE) throw DomainException.invalid("Release and a valid active pointer revision are required");
        ContentAccess.hash(input.expectedManifestHash());
        return tx.execute(status->{
            access.lockEditor(actor);
            String operation=historical?"editorActivateRelease":"editorPublishRelease";
            var command=Map.of("releaseId",id,"input",input);
            UUID previous=access.replay(actor,operation,key,command);
            if(previous!=null) return get(actor,previous); // Never reactivate on a retry after a later editor change.
            jdbc.queryForList("SELECT id FROM content_release WHERE id=? FOR UPDATE",id.toString());
            Release release=get(actor,id);
            if(!release.status().equals(historical?"PUBLISHED":"DRAFT")) throw DomainException.conflict(historical?"Only a published formal release can be activated":"Only a checked draft release can be published");
            if(!release.manifestHash().equals(input.expectedManifestHash()) || !json.hash(release.manifest()).equals(release.manifestHash())) throw DomainException.conflict("Release manifest changed after preview");
            List<ContentRef> chunks=jdbc.query("SELECT chunk_id,chunk_revision FROM release_chunk WHERE release_id=? ORDER BY chunk_id",(rs,n)->new ContentRef(UUID.fromString(rs.getString(1)),rs.getInt(2)),id.toString());
            List<ContentRef> exercises=jdbc.query("SELECT exercise_id,exercise_revision FROM release_exercise WHERE release_id=? ORDER BY exercise_id",(rs,n)->new ContentRef(UUID.fromString(rs.getString(1)),rs.getInt(2)),id.toString());
            var frozen=jdbc.query("SELECT c.id,c.code,rc.title,rc.prerequisite_ids FROM release_concept rc JOIN concept c ON c.id=rc.concept_id WHERE rc.release_id=? ORDER BY c.code",(rs,n)->new Concept(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3),List.of(json.read(json.write(json.tree(rs.getObject(4))),UUID[].class))),id.toString());
            if(!json.hash(manifest(actor,release.version(),chunks,exercises,frozen)).equals(release.manifestHash())) throw DomainException.conflict("An approved revision, source, answer or frozen course graph no longer matches the release manifest");
            if(jdbc.queryForList("SELECT id FROM index_snapshot WHERE release_id=? AND index_version=? AND content_hash=? AND storage_key=? AND status='READY'",id.toString(),LocalKnowledgeSearch.INDEX_VERSION,release.manifestHash(),"sql:release/"+id).size()!=1) throw DomainException.conflict("The actual SQL corpus validation is not ready for this manifest");
            var active=jdbc.queryForList("SELECT release_id,revision FROM active_release WHERE singleton_id=1 FOR UPDATE");
            int current=active.isEmpty()?0:((Number)active.get(0).get("revision")).intValue();
            if(current!=input.expectedActiveRevision()) throw DomainException.conflict("Another editor changed the active course; reload before publishing or activating");
            if(!historical) jdbc.update("UPDATE content_release SET status='PUBLISHED',published_at=? WHERE id=? AND status='DRAFT'",Timestamp.from(clock.instant()),id.toString());
            if(active.isEmpty()) jdbc.update("INSERT INTO active_release(singleton_id,release_id,revision) VALUES(1,?,1)",id.toString());
            else jdbc.update("UPDATE active_release SET release_id=?,revision=? WHERE singleton_id=1 AND revision=?",id.toString(),current+1,current);
            access.remember(actor,operation,key,command,id);
            access.audit(actor,historical?"HISTORICAL_RELEASE_ACTIVATED":"FORMAL_RELEASE_PUBLISHED",id,Map.of("manifestHash",release.manifestHash(),"activeRevision",current+1,"personalWorkspacesMigrated",false));
            return get(actor,id);
        });
    }
    private JsonNode manifest(UUID actor,String version,List<ContentRef> chunks,List<ContentRef> exercises,List<Concept> concepts) {
        ContentDraftService.refs(chunks,1,1000);ContentDraftService.refs(exercises,1,1000);
        var graph=new HashMap<UUID,Concept>();
        for(var concept:concepts) if(graph.put(concept.id(),concept)!=null) throw DomainException.conflict("Duplicate frozen concept");
        if(graph.isEmpty() || graph.size()>100) throw DomainException.conflict("A release needs a bounded, nonempty concept graph");
        for(UUID id:graph.keySet()) validateGraph(id,graph,new HashSet<>(),new HashSet<>());
        var covered=new HashSet<UUID>();
        var included=new HashMap<UUID,Integer>();
        var cards=new ArrayList<Object>();
        for(var ref:chunks.stream().sorted(Comparator.comparing(r->r.id().toString())).toList()) {
            Chunk card=drafts.chunk(actor,ref.id(),ref.revision());
            Review approval=drafts.requireApproved(actor,"CHUNK",ref.id(),ref.revision());
            if(!graph.containsKey(card.conceptId())) throw DomainException.conflict("Knowledge references a concept outside the frozen course");
            covered.add(card.conceptId());included.put(ref.id(),ref.revision());
            cards.add(Map.of("id",ref.id(),"revision",ref.revision(),"bodyHash",card.contentHash(),"reviewHash",card.reviewHash(),"reviewId",approval.id(),"authorId",approval.authorId(),"reviewerId",approval.reviewerId()));
        }
        if(!covered.containsAll(graph.keySet())) throw DomainException.conflict("Every included concept and prerequisite needs a reviewed knowledge card");
        var questions=new ArrayList<Object>();
        for(var ref:exercises.stream().sorted(Comparator.comparing(r->r.id().toString())).toList()) {
            Exercise exercise=drafts.exercise(actor,ref.id(),ref.revision());
            Review approval=drafts.requireApproved(actor,"EXERCISE",ref.id(),ref.revision());
            if(exercise.knowledgeRefs().isEmpty() || !graph.keySet().containsAll(exercise.conceptIds())) throw DomainException.conflict("Exercise concept or teaching evidence is outside the release");
            for(var knowledge:exercise.knowledgeRefs()) if(!Integer.valueOf(knowledge.revision()).equals(included.get(knowledge.id()))) throw DomainException.conflict("An exercise's exact supporting knowledge must be in the same release");
            BigDecimal weight=jdbc.queryForObject("SELECT SUM(weight) FROM exercise_concept WHERE exercise_id=? AND exercise_revision=?",BigDecimal.class,ref.id().toString(),ref.revision());
            if(weight==null || weight.compareTo(BigDecimal.ONE)!=0) throw DomainException.conflict("Exercise concept weights must total one");
            questions.add(Map.of("id",ref.id(),"revision",ref.revision(),"reviewHash",exercise.reviewHash(),"reviewId",approval.id(),"authorId",approval.authorId(),"reviewerId",approval.reviewerId()));
        }
        return json.tree(json.write(Map.of("manifestVersion",ContentDraftService.CHECKER_VERSION,"version",version,"reviewPolicy","AUTHOR_AND_INDEPENDENT_EDITOR",
            "indexVersion",LocalKnowledgeSearch.INDEX_VERSION,"concepts",concepts.stream().sorted(Comparator.comparing(Concept::code)).toList(),"chunks",cards,"exercises",questions)));
    }
    private void collectConcept(UUID actor,UUID id,Map<UUID,Concept> collected,Set<UUID> visiting) {
        if(visiting.contains(id)) throw DomainException.conflict("Concept prerequisite graph contains a cycle");
        if(collected.containsKey(id)) return;
        if(collected.size()>=100) throw DomainException.conflict("Course concept graph exceeds the supported bound");
        visiting.add(id);Concept concept=drafts.concept(actor,id);collected.put(id,concept);
        for(UUID prerequisite:concept.prerequisiteIds()) collectConcept(actor,prerequisite,collected,visiting);
        visiting.remove(id);
    }
    private static void validateGraph(UUID id,Map<UUID,Concept> graph,Set<UUID> visiting,Set<UUID> visited) {
        if(visiting.contains(id)) throw DomainException.conflict("Frozen prerequisite graph contains a cycle");
        if(visited.contains(id)) return;
        Concept concept=graph.get(id);if(concept==null) throw DomainException.conflict("Frozen prerequisite graph has a missing concept");
        visiting.add(id);
        for(UUID prerequisite:concept.prerequisiteIds()) validateGraph(prerequisite,graph,visiting,visited);
        visiting.remove(id);visited.add(id);
    }
}
