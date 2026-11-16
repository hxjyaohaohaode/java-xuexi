package io.typeforge.evidence;

import static io.typeforge.evidence.EvaluationModels.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.identity.ContentAccess;
import io.typeforge.operations.RuntimeCapabilityProvider;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.Page;
import io.typeforge.workspace.WorkspaceAccess;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Real bounded retrieval runs; labels are explicitly user authored, never teaching-quality or mastery scores. */
@Service
public class EvaluationService implements RuntimeCapabilityProvider {
    public static final String EVALUATOR_VERSION="retrieval-macro-v1";
    public static final int MAX_REPORT_BYTES=2_097_152;
    public static final int MAX_RESULT_BYTES=1_048_576;
    public static final long MAX_RUNTIME_MILLIS=60_000;
    private static final Set<String> PENDING=Set.of("QUEUED","RUNNING");
    private static final Map<String,String> DEFINITIONS=Map.of(
        "precisionAtK","Relevant returned chunks / requested topK; missing ranks count as non-relevant. Mean over MATCH cases only.",
        "recallAtK","Relevant returned chunks / distinct expected chunks. Mean over MATCH cases only.",
        "meanReciprocalRank","Mean of 1 / first relevant rank, or zero when none is retrieved; MATCH cases only.",
        "hitRateAtK","Fraction of MATCH cases with at least one relevant returned chunk.",
        "noResultAccuracy","Fraction of NO_RESULTS cases returning zero chunks. Null when there are no such cases.",
        "passedCases","MATCH passes only when every expected chunk is returned; NO_RESULTS passes only when empty.",
        "elapsedMillis","Observed monotonic execution time in this run. Not a production latency SLO.");
    private static final List<String> LIMITATIONS=List.of("Labels were supplied by this suite's owner; no independent human review is implied.","Retrieval metrics do not measure teaching quality, learner mastery, model accuracy or individual contribution.","The frozen published corpus, algorithm version, queries and topK define reproducibility; elapsed times vary by environment.","Only complete runs have aggregate metrics or a downloadable report. Partial results remain visible after cancellation or failure.");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final WorkspaceAccess workspaces;
    private final CurriculumService curriculum;
    private final LocalKnowledgeSearch search;
    private final EventJournal events;
    private final Clock clock;
    public EvaluationService(JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json,WorkspaceAccess workspaces,CurriculumService curriculum,LocalKnowledgeSearch search,EventJournal events,Clock clock) {
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.workspaces=workspaces;this.curriculum=curriculum;this.search=search;this.events=events;this.clock=clock;
    }
    @Override public Set<String> enabledCapabilities(){return Set.of("EVALUATION_WORKBENCH");}
    private record Scope(UUID id,UUID owner,UUID workspace,UUID release,int revision,String status,Instant created,Instant updated) {}
    private record Version(Snapshot snapshot,List<CorpusEntry> corpus,String hash,Instant created) {}
    private record Corpus(List<CorpusEntry> entries,String hash,String manifestHash) {}
    private record RunRow(Run view,String lease,Instant leaseUntil,String report,String reportHash,int reportBytes,int resultBytes) {}
    public record Ticket(UUID id,UUID owner,UUID workspace,UUID suite,UUID release,int revision,int topK,String lease,Snapshot snapshot,List<CorpusEntry> corpus,String suiteHash) {}

    public Page<SuiteSummary> suites(UUID owner,UUID workspace,int limit,String cursor,boolean includeArchived) {
        workspaces.requireOwned(owner,workspace);String after=cursor(cursor);ContentAccess.limit(limit);
        var ids=jdbc.queryForList("SELECT id FROM evaluation_suite WHERE owner_id=? AND workspace_id=? AND id>?"+(includeArchived?"":" AND status='ACTIVE'")+" ORDER BY id LIMIT ?",String.class,owner.toString(),workspace.toString(),after,limit+1);
        var items=ids.stream().limit(limit).map(id->summary(get(owner,UUID.fromString(id)))).toList();
        return new Page<>(items,ids.size()>limit?items.get(items.size()-1).id().toString():null,limit);
    }
    public Suite get(UUID owner,UUID id){Scope scope=owned(owner,id);return suite(scope,version(id,scope.revision()));}
    public Suite revision(UUID owner,UUID id,int revision){if(revision<1 || revision>100)throw DomainException.invalid("Revision must be 1..100");return suite(owned(owner,id),version(id,revision),revision);}
    public Suite create(UUID owner,UUID workspace,SuiteInput input,String key) {
        validate(input);ContentAccess.key(key);Object request=Map.of("workspaceId",workspace,"input",input);
        return tx.execute(status->{
            var scope=workspaces.lockOwned(owner,workspace);
            UUID prior=replay(owner,"evaluationSuite.create",key,request);if(prior!=null)return revision(owner,prior,1);
            if(count("SELECT COUNT(*) FROM evaluation_suite WHERE owner_id=?",owner.toString())>=30)throw limit("An account may keep at most 30 evaluation suites including archived history");
            Corpus corpus=corpus(scope.releaseId());Snapshot snapshot=snapshot(scope.releaseId(),input,corpus);int bytes=bytes(snapshot,corpus.entries());checkSuiteStorage(owner,bytes);
            UUID id=UUID.randomUUID();Instant now=clock.instant();
            jdbc.update("INSERT INTO evaluation_suite(id,owner_id,workspace_id,release_id,created_at,updated_at) VALUES(?,?,?,?,?,?)",id.toString(),owner.toString(),workspace.toString(),scope.releaseId().toString(),time(now),time(now));
            insertVersion(id,1,snapshot,corpus,bytes,now);remember(owner,"evaluationSuite.create",key,request,id);
            events.append(workspace,"EVALUATION_SUITE_CREATED","CLIENT",Map.of("suiteId",id,"revision",1,"contentHash",json.hash(snapshot),"caseCount",snapshot.cases().size()));
            return get(owner,id);
        });
    }
    public Suite revise(UUID owner,UUID id,RevisionInput input,String key) {
        if(input==null || input.expectedRevision()<1 || input.expectedRevision()>=100)throw DomainException.invalid("A valid base revision below 100 is required");
        SuiteInput content=new SuiteInput(input.title(),input.description(),input.cases());validate(content);ContentAccess.key(key);Object request=Map.of("suiteId",id,"input",input);
        return tx.execute(status->{
            Scope scope=lockOwned(owner,id);UUID prior=replay(owner,"evaluationSuite.revise",key,request);if(prior!=null)return revision(owner,prior,input.expectedRevision()+1);
            requireActive(scope);if(scope.revision()!=input.expectedRevision())throw DomainException.conflict("The evaluation suite changed; preserve your cases and merge with the latest revision");
            Corpus corpus=corpus(scope.release());Snapshot snapshot=snapshot(scope.release(),content,corpus);int bytes=bytes(snapshot,corpus.entries());checkSuiteStorage(owner,bytes);
            int next=scope.revision()+1;Instant now=clock.instant();insertVersion(id,next,snapshot,corpus,bytes,now);
            jdbc.update("UPDATE evaluation_suite SET current_revision=?,updated_at=? WHERE id=?",next,time(now),id.toString());remember(owner,"evaluationSuite.revise",key,request,id);
            events.append(scope.workspace(),"EVALUATION_SUITE_REVISED","CLIENT",Map.of("suiteId",id,"revision",next,"contentHash",json.hash(snapshot)));
            return get(owner,id);
        });
    }
    public Suite archive(UUID owner,UUID id,int expectedRevision) {
        if(expectedRevision<1 || expectedRevision>100)throw DomainException.invalid("A valid current revision is required");
        return tx.execute(status->{
            Scope scope=lockOwned(owner,id);if(scope.revision()!=expectedRevision)throw DomainException.conflict("The suite changed before archive");
            if(scope.status().equals("ARCHIVED"))return get(owner,id);
            jdbc.update("UPDATE evaluation_suite SET status='ARCHIVED',updated_at=? WHERE id=?",time(clock.instant()),id.toString());
            jdbc.update("UPDATE evaluation_run SET status='CANCELLED',error_code='SUITE_ARCHIVED',lease_token=NULL,lease_until=NULL,finished_at=? WHERE suite_id=? AND status IN ('QUEUED','RUNNING')",time(clock.instant()),id.toString());
            events.append(scope.workspace(),"EVALUATION_SUITE_ARCHIVED","CLIENT",Map.of("suiteId",id,"revision",scope.revision()));return get(owner,id);
        });
    }
    public Run createRun(UUID owner,UUID suiteId,RunInput input,String key) {
        if(input==null || input.suiteRevision()<1 || input.suiteRevision()>100 || input.topK()<1 || input.topK()>8)throw DomainException.invalid("Choose a suite revision and topK from 1..8");
        ContentAccess.key(key);Object request=Map.of("suiteId",suiteId,"input",input);
        return tx.execute(status->{
            Scope scope=lockOwned(owner,suiteId);UUID prior=replay(owner,"evaluationRun.create",key,request);if(prior!=null)return run(owner,prior);
            requireActive(scope);Version version=version(suiteId,input.suiteRevision());validateCorpus(version.snapshot());
            jdbc.queryForObject("SELECT singleton_id FROM evaluation_admission_lock WHERE singleton_id=1 FOR UPDATE",Integer.class);
            long pending=count("SELECT COUNT(*) FROM evaluation_run WHERE owner_id=? AND status IN ('QUEUED','RUNNING')",owner.toString());
            if(pending>=2 || count("SELECT COUNT(*) FROM evaluation_run WHERE status IN ('QUEUED','RUNNING')")>=64)throw limit("The bounded evaluation queue is full; wait for an existing run or cancel it");
            if(count("SELECT COUNT(*) FROM evaluation_run WHERE owner_id=?",owner.toString())>=200)throw limit("The account's 200 retained evaluation runs are at capacity");
            Instant day=clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            if(count("SELECT COUNT(*) FROM evaluation_run WHERE owner_id=? AND created_at>=?",owner.toString(),time(day))>=20)throw limit("At most 20 new evaluation runs are admitted per UTC day");
            long stored=count("SELECT COALESCE(SUM(report_bytes+result_bytes),0) FROM evaluation_run WHERE owner_id=?",owner.toString());
            if(stored+(pending+1)*(MAX_REPORT_BYTES+MAX_RESULT_BYTES)>67_108_864L)throw limit("The bounded private evaluation evidence storage is full");
            UUID id=UUID.randomUUID();Snapshot snap=version.snapshot();Instant now=clock.instant();
            jdbc.update("INSERT INTO evaluation_run(id,suite_id,suite_revision,owner_id,workspace_id,release_id,suite_hash,corpus_hash,index_version,evaluator_version,top_k,case_count,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",id.toString(),suiteId.toString(),input.suiteRevision(),owner.toString(),scope.workspace().toString(),scope.release().toString(),version.hash(),snap.corpusHash(),snap.indexVersion(),EVALUATOR_VERSION,input.topK(),snap.cases().size(),time(now));
            remember(owner,"evaluationRun.create",key,request,id);events.append(scope.workspace(),"EVALUATION_RUN_QUEUED","CLIENT",Map.of("runId",id,"suiteId",suiteId,"suiteRevision",input.suiteRevision(),"suiteHash",version.hash()));return run(owner,id);
        });
    }
    public Page<Run> runs(UUID owner,UUID suiteId,int limit,String cursor) {
        owned(owner,suiteId);ContentAccess.limit(limit);String after=cursor(cursor);
        // Listing summaries never materializes every retained report (up to 2 MiB each). Detail/download reads verify the evidence bytes.
        var rows=jdbc.query("SELECT r.id,r.suite_id,r.workspace_id,r.release_id,r.suite_revision,v.title AS suite_title,r.suite_hash,r.corpus_hash,r.index_version,r.evaluator_version,r.top_k,r.case_count,r.completed_cases,r.status,r.error_code,r.created_at,r.started_at,r.finished_at,r.metrics_json,r.result_hash FROM evaluation_run r JOIN evaluation_suite_revision v ON v.suite_id=r.suite_id AND v.revision=r.suite_revision WHERE r.owner_id=? AND r.suite_id=? AND r.id>? ORDER BY r.id LIMIT ?",(rs,n)->runView(rs),owner.toString(),suiteId.toString(),after,limit+1);
        var items=rows.stream().limit(limit).toList();return new Page<>(items,rows.size()>limit?items.get(items.size()-1).id().toString():null,limit);
    }
    public Run run(UUID owner,UUID id) {RunRow row=ownedRun(owner,id);return view(row,true);}
    public Run cancel(UUID owner,UUID id) {
        return tx.execute(status->{RunRow before=ownedRun(owner,id);workspaces.lockOwned(owner,before.view().workspaceId());RunRow row=lockedRow(id);
            if(row.view().status().equals("CANCELLED"))return view(row,true);
            if(!PENDING.contains(row.view().status()))throw DomainException.conflict("A finished evaluation cannot be cancelled");
            terminal(row,"CANCELLED","USER_CANCELLED",true);return run(owner,id);
        });
    }
    public Download report(UUID owner,UUID id) {
        RunRow row=ownedRun(owner,id);if(!row.view().status().equals("COMPLETED"))throw DomainException.conflict("Only a complete evaluation has a report");
        view(row,true);String canonical=json.write(json.tree(row.report()));return new Download(canonical.getBytes(StandardCharsets.UTF_8),row.reportHash(),"evaluation-"+id+".json");
    }

    /** Internal worker lease; no public execute/complete endpoint accepts fabricated results. */
    public Ticket claimNext() {
        Instant now=clock.instant();var candidates=jdbc.queryForList("SELECT id FROM evaluation_run WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<=?) ORDER BY created_at,id LIMIT 16",String.class,time(now));
        for(String candidate:candidates) {
            Ticket ticket=tx.execute(status->{UUID id=UUID.fromString(candidate);boolean active=lockRunScope(id);RunRow row=lockedRow(id);if(!PENDING.contains(row.view().status()))return null;
                if(!active){terminal(row,"CANCELLED","EVALUATION_SCOPE_INACTIVE",false);return null;}
                if(row.view().status().equals("RUNNING")){if(row.leaseUntil()!=null && !row.leaseUntil().isAfter(clock.instant()))terminal(row,"FAILED","EVALUATION_LEASE_EXPIRED",true);return null;}
                if(row.view().createdAt().plusSeconds(600).isBefore(clock.instant())){terminal(row,"FAILED","EVALUATION_QUEUE_EXPIRED",true);return null;}
                Version version;
                try{version=version(row.view().suiteId(),row.view().suiteRevision());}
                catch(DomainException corrupt){terminal(row,"FAILED","EVALUATION_INTEGRITY_FAILURE",true);return null;}
                if(!version.hash().equals(row.view().suiteHash()) || !version.snapshot().corpusHash().equals(row.view().corpusHash()) || !LocalKnowledgeSearch.INDEX_VERSION.equals(row.view().indexVersion()) || !EVALUATOR_VERSION.equals(row.view().evaluatorVersion())){terminal(row,"FAILED","EVALUATION_VERSION_CHANGED",true);return null;}
                String lease=UUID.randomUUID().toString();Instant started=clock.instant();
                jdbc.update("UPDATE evaluation_run SET status='RUNNING',started_at=?,lease_token=?,lease_until=? WHERE id=?",time(started),lease,time(started.plusSeconds(90)),id.toString());
                events.append(row.view().workspaceId(),"EVALUATION_RUN_STARTED","SERVER",Map.of("runId",id));
                return new Ticket(id,scope(row.view().suiteId()).owner(),row.view().workspaceId(),row.view().suiteId(),row.view().releaseId(),row.view().suiteRevision(),row.view().topK(),lease,version.snapshot(),version.corpus(),version.hash());
            });if(ticket!=null)return ticket;
        }return null;
    }
    public boolean active(Ticket ticket) {
        return count("SELECT COUNT(*) FROM evaluation_run r JOIN app_user u ON u.id=r.owner_id JOIN learning_workspace w ON w.id=r.workspace_id JOIN evaluation_suite s ON s.id=r.suite_id WHERE r.id=? AND r.lease_token=? AND r.status='RUNNING' AND r.lease_until>? AND u.status='ACTIVE' AND w.status='ACTIVE' AND s.status='ACTIVE'",ticket.id().toString(),ticket.lease(),time(clock.instant()))==1;
    }
    public void validateCorpus(Ticket ticket){validateCorpus(ticket.snapshot());}
    public CaseResult evaluate(Ticket ticket,CaseView sample) {
        long start=System.nanoTime();var found=search.search(ticket.release(),sample.query(),sample.conceptId(),ticket.topK());
        if(!found.indexVersion().equals(ticket.snapshot().indexVersion()))throw new DomainException("EVALUATION_VERSION_CHANGED",409,"The search implementation changed after admission");
        Set<UUID> expected=new HashSet<>(sample.expectedChunks().stream().map(ChunkReference::chunkId).toList());var hits=new ArrayList<Hit>();int matched=0;Integer first=null;
        for(var item:found.items()) {boolean relevant=expected.contains(item.chunkId());int rank=hits.size()+1;if(relevant){matched++;if(first==null)first=rank;}
            hits.add(new Hit(rank,item.chunkId(),item.revision(),item.conceptId(),item.title(),clip(item.excerpt(),800),item.contentHash(),item.rankScore(),item.source(),item.sourceSection(),relevant));
        }
        boolean match=sample.expectation().equals("MATCH");
        return new CaseResult(sample.key(),sample.query(),sample.conceptId(),sample.expectation(),sample.expectedChunks().stream().map(ChunkReference::chunkId).toList(),List.copyOf(hits),matched,first,match?(double)matched/ticket.topK():null,match?(double)matched/expected.size():null,match?(first==null?0.0:1.0/first):null,match?matched==expected.size():hits.isEmpty(),Math.max(0,(System.nanoTime()-start)/1_000_000));
    }
    public boolean acceptCase(Ticket ticket,int position,CaseResult result) {
        return Boolean.TRUE.equals(tx.execute(status->{boolean active=lockRunScope(ticket.id());RunRow row=lockedRow(ticket.id());if(!leaseMatches(row,ticket))return false;
            if(!active){terminal(row,"CANCELLED","EVALUATION_SCOPE_INACTIVE",false);return false;}
            if(position!=row.view().completedCases() || position>=ticket.snapshot().cases().size() || !result.caseKey().equals(ticket.snapshot().cases().get(position).key()))throw new IllegalStateException("Evaluation case order changed");
            String encoded=json.write(result);int bytes=encoded.getBytes(StandardCharsets.UTF_8).length;
            if(bytes>65_536 || row.resultBytes()+bytes>MAX_RESULT_BYTES)throw new DomainException("EVALUATION_RESULT_TOO_LARGE",413,"Actual case evidence exceeds the 64 KiB per case or 1 MiB per run bound");
            jdbc.update("INSERT INTO evaluation_case_result(run_id,case_key,case_position,result_json,result_hash,byte_size) VALUES(?,?,?,?,?,?)",ticket.id().toString(),result.caseKey(),position,encoded,json.sha256(encoded),bytes);
            jdbc.update("UPDATE evaluation_run SET completed_cases=completed_cases+1,result_bytes=result_bytes+? WHERE id=?",bytes,ticket.id().toString());return true;
        }));
    }
    public void complete(Ticket ticket,long elapsedMillis) {
        validateCorpus(ticket);
        tx.executeWithoutResult(status->{boolean active=lockRunScope(ticket.id());RunRow row=lockedRow(ticket.id());if(!leaseMatches(row,ticket))return;
            if(!active){terminal(row,"CANCELLED","EVALUATION_SCOPE_INACTIVE",false);return;}
            var results=results(ticket.id(),row.view().completedCases());if(results.size()!=row.view().caseCount() || row.view().completedCases()!=results.size())throw integrity();
            Metrics metrics=metrics(results,elapsedMillis);String resultHash=semanticHash(row.view(),results);Instant ended=clock.instant();
            Report report=new Report("EVALUATION_REPORT_V1","PRIVATE_USER_LABELLED_PUBLISHED_RETRIEVAL",ticket.id(),ticket.suite(),ticket.revision(),ended,ticket.snapshot(),ticket.suiteHash(),ticket.corpus(),EVALUATOR_VERSION,ticket.topK(),results,metrics,resultHash,DEFINITIONS,LIMITATIONS);
            String encoded=json.write(report);int bytes=encoded.getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_REPORT_BYTES)throw new DomainException("EVALUATION_REPORT_TOO_LARGE",413,"The complete evidence report exceeds 2 MiB");
            jdbc.update("UPDATE evaluation_run SET status='COMPLETED',metrics_json=?,result_hash=?,report_json=?,report_hash=?,report_bytes=?,lease_token=NULL,lease_until=NULL,finished_at=? WHERE id=?",json.write(metrics),resultHash,encoded,json.sha256(encoded),bytes,time(ended),ticket.id().toString());
            events.append(ticket.workspace(),"EVALUATION_RUN_COMPLETED","SERVER",Map.of("runId",ticket.id(),"resultHash",resultHash,"caseCount",results.size()));
        });
    }
    public void fail(Ticket ticket,String code) {
        if(code==null || !code.matches("[A-Z][A-Z0-9_]{0,63}"))code="EVALUATION_FAILED";String failure=code;
        tx.executeWithoutResult(status->{boolean active=lockRunScope(ticket.id());RunRow row=lockedRow(ticket.id());if(!row.view().status().equals("RUNNING") || !Objects.equals(row.lease(),ticket.lease()))return;
            terminal(row,active?"FAILED":"CANCELLED",active?failure:"EVALUATION_SCOPE_INACTIVE",active);
        });
    }

    private Scope owned(UUID owner,UUID id){Scope scope=scope(id);if(!scope.owner().equals(owner))throw DomainException.missing();workspaces.requireOwned(owner,scope.workspace());return scope;}
    private Scope lockOwned(UUID owner,UUID id){Scope initial=owned(owner,id);workspaces.lockOwned(owner,initial.workspace());jdbc.queryForObject("SELECT id FROM evaluation_suite WHERE id=? FOR UPDATE",String.class,id.toString());return scope(id);}
    private Scope scope(UUID id){var rows=jdbc.query("SELECT * FROM evaluation_suite WHERE id=?",(rs,n)->new Scope(id,UUID.fromString(rs.getString("owner_id")),UUID.fromString(rs.getString("workspace_id")),UUID.fromString(rs.getString("release_id")),rs.getInt("current_revision"),rs.getString("status"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant()),id.toString());if(rows.isEmpty())throw DomainException.missing();return rows.get(0);}
    private Version version(UUID suite,int revision){var rows=jdbc.query("SELECT * FROM evaluation_suite_revision WHERE suite_id=? AND revision=?",(rs,n)->{
        Snapshot snap=json.read(rs.getString("snapshot_json"),Snapshot.class);List<CorpusEntry> corpus=List.of(json.read(rs.getString("corpus_json"),CorpusEntry[].class));String hash=rs.getString("content_hash");
        if(!json.hash(snap).equals(hash) || !json.hash(corpus).equals(rs.getString("corpus_hash")) || !snap.corpusHash().equals(rs.getString("corpus_hash")) || snap.cases().size()!=rs.getInt("case_count") || !snap.title().equals(rs.getString("title")) || !snap.description().equals(rs.getString("description")) || bytes(snap,corpus)!=rs.getInt("byte_size"))throw integrity();
        return new Version(snap,corpus,hash,rs.getTimestamp("created_at").toInstant());
    },suite.toString(),revision);if(rows.isEmpty())throw DomainException.missing();return rows.get(0);}
    private Suite suite(Scope scope,Version version){return suite(scope,version,scope.revision());}
    private Suite suite(Scope scope,Version version,int revision){var s=version.snapshot();return new Suite(scope.id(),scope.workspace(),scope.release(),s.title(),s.description(),revision,scope.status(),s.cases().size(),version.hash(),s.corpusHash(),s.releaseManifestHash(),s.indexVersion(),scope.created(),revision==scope.revision()?scope.updated():version.created(),s.cases());}
    private static SuiteSummary summary(Suite s){return new SuiteSummary(s.id(),s.workspaceId(),s.releaseId(),s.title(),s.description(),s.revision(),s.status(),s.caseCount(),s.contentHash(),s.corpusHash(),s.createdAt(),s.updatedAt());}
    private Corpus corpus(UUID release) {
        curriculum.requirePublished(release);
        if(count("SELECT COUNT(*) FROM release_chunk WHERE release_id=?",release.toString())>512 || count("SELECT COALESCE(SUM(OCTET_LENGTH(k.body)),0) FROM release_chunk r JOIN knowledge_chunk k ON k.id=r.chunk_id AND k.revision=r.chunk_revision WHERE r.release_id=?",release.toString())>1_048_576)throw new DomainException("EVALUATION_CORPUS_TOO_LARGE",422,"This in-process evaluator supports at most 512 cards and 1 MiB of published knowledge text");
        var entries=curriculum.chunks(release,List.of()).stream().map(c->new CorpusEntry(c.chunkId(),c.revision(),c.conceptId(),c.title(),c.contentHash(),c.sources())).toList();
        String manifest=jdbc.queryForObject("SELECT manifest_hash FROM content_release WHERE id=? AND status='PUBLISHED'",String.class,release.toString());return new Corpus(entries,json.hash(entries),manifest);
    }
    private void validateCorpus(Snapshot snapshot){Corpus current=corpus(snapshot.releaseId());if(!snapshot.corpusHash().equals(current.hash()) || !snapshot.releaseManifestHash().equals(current.manifestHash()))throw new DomainException("EVALUATION_CORPUS_CHANGED",409,"The fixed published evidence changed; preserve historical results and save a newly checked suite revision");if(!snapshot.indexVersion().equals(LocalKnowledgeSearch.INDEX_VERSION))throw new DomainException("EVALUATION_VERSION_CHANGED",409,"Save a new suite revision for the current retrieval implementation");}
    private Snapshot snapshot(UUID release,SuiteInput input,Corpus corpus){
        var views=new ArrayList<CaseView>();for(var sample:input.cases()){
            if(sample.conceptId()!=null && count("SELECT COUNT(*) FROM release_concept WHERE release_id=? AND concept_id=?",release.toString(),sample.conceptId().toString())!=1)throw DomainException.invalid("A case's concept must belong to its fixed release");
            var expected=new ArrayList<ChunkReference>();for(UUID id:sample.expectedChunkIds()){
                CorpusEntry entry=corpus.entries().stream().filter(c->c.chunkId().equals(id)).findFirst().orElseThrow(()->DomainException.invalid("Every expected chunk must belong to the suite's published release"));
                if(sample.conceptId()!=null && !sample.conceptId().equals(entry.conceptId()))throw DomainException.invalid("Expected evidence must match the case's concept filter");
                expected.add(new ChunkReference(entry.chunkId(),entry.revision(),entry.conceptId(),entry.title(),entry.contentHash()));
            }
            expected.sort(java.util.Comparator.comparing(c->c.chunkId().toString()));views.add(new CaseView(sample.key(),sample.query(),sample.conceptId(),sample.expectation(),List.copyOf(expected),sample.note()));
        }return new Snapshot(release,input.title(),input.description(),List.copyOf(views),corpus.manifestHash(),corpus.hash(),LocalKnowledgeSearch.INDEX_VERSION);
    }
    private void validate(SuiteInput input){
        if(input==null)throw DomainException.invalid("An evaluation suite is required");ContentAccess.text(input.title(),120);optionalText(input.description(),2000);
        if(input.cases()==null || input.cases().isEmpty() || input.cases().size()>40)throw DomainException.invalid("Choose 1..40 evaluation cases");Set<String> keys=new HashSet<>(),queries=new HashSet<>();
        for(var sample:input.cases()){
            if(sample==null || sample.key()==null || !sample.key().matches("[A-Za-z0-9_-]{1,64}") || !keys.add(sample.key()))throw DomainException.invalid("Every case needs a distinct stable key");
            ContentAccess.text(sample.query(),500);optionalText(sample.note(),1000);
            if(!queries.add(sample.query().strip().toLowerCase(Locale.ROOT)+"|"+sample.conceptId()))throw DomainException.invalid("Duplicate queries within the same concept filter cannot inflate evaluation samples");
            if(sample.expectation()==null || !Set.of("MATCH","NO_RESULTS").contains(sample.expectation()) || sample.expectedChunkIds()==null || sample.expectedChunkIds().stream().anyMatch(Objects::isNull) || new HashSet<>(sample.expectedChunkIds()).size()!=sample.expectedChunkIds().size() || sample.expectedChunkIds().size()>8 || sample.expectation().equals("MATCH")&&sample.expectedChunkIds().isEmpty() || sample.expectation().equals("NO_RESULTS")&&!sample.expectedChunkIds().isEmpty())throw DomainException.invalid("MATCH requires 1..8 distinct expected chunks; NO_RESULTS requires an empty list");
        }
    }
    private void insertVersion(UUID id,int revision,Snapshot snap,Corpus corpus,int bytes,Instant now){jdbc.update("INSERT INTO evaluation_suite_revision(suite_id,revision,title,description,case_count,snapshot_json,corpus_json,content_hash,corpus_hash,byte_size,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id.toString(),revision,snap.title(),snap.description(),snap.cases().size(),json.write(snap),json.write(corpus.entries()),json.hash(snap),corpus.hash(),bytes,time(now));}
    private int bytes(Snapshot snap,List<CorpusEntry> corpus){int size=json.write(snap).getBytes(StandardCharsets.UTF_8).length+json.write(corpus).getBytes(StandardCharsets.UTF_8).length;if(size>262144)throw new DomainException("EVALUATION_SUITE_TOO_LARGE",413,"The frozen definition and corpus manifest exceed 256 KiB");return size;}
    private void checkSuiteStorage(UUID owner,int bytes){if(count("SELECT COALESCE(SUM(v.byte_size),0) FROM evaluation_suite_revision v JOIN evaluation_suite s ON s.id=v.suite_id WHERE s.owner_id=?",owner.toString())+bytes>33_554_432)throw limit("The account's retained evaluation definitions exceed 32 MiB");}
    private UUID replay(UUID owner,String operation,String key,Object request){var rows=jdbc.queryForList("SELECT request_hash,resource_id FROM idempotency_record WHERE user_id=? AND operation=? AND idempotency_key=?",owner.toString(),operation,key);if(rows.isEmpty())return null;if(!json.hash(request).equals(rows.get(0).get("request_hash")))throw new DomainException("IDEMPOTENCY_CONFLICT",409,"The idempotency key belongs to different evaluation input");return UUID.fromString(rows.get(0).get("resource_id").toString());}
    private void remember(UUID owner,String operation,String key,Object request,UUID id){jdbc.update("INSERT INTO idempotency_record(user_id,operation,idempotency_key,request_hash,resource_id,expires_at) VALUES(?,?,?,?,?,?)",owner.toString(),operation,key,json.hash(request),id.toString(),time(clock.instant().plusSeconds(86400)));}
    private RunRow ownedRun(UUID owner,UUID id){var rows=jdbc.query("SELECT r.*,v.title AS suite_title FROM evaluation_run r JOIN evaluation_suite_revision v ON v.suite_id=r.suite_id AND v.revision=r.suite_revision WHERE r.id=? AND r.owner_id=?",this::runRow,id.toString(),owner.toString());if(rows.isEmpty())throw DomainException.missing();workspaces.requireOwned(owner,rows.get(0).view().workspaceId());return rows.get(0);}
    private RunRow lockedRow(UUID id){jdbc.queryForObject("SELECT id FROM evaluation_run WHERE id=? FOR UPDATE",String.class,id.toString());return jdbc.queryForObject("SELECT r.*,v.title AS suite_title FROM evaluation_run r JOIN evaluation_suite_revision v ON v.suite_id=r.suite_id AND v.revision=r.suite_revision WHERE r.id=?",this::runRow,id.toString());}
    private Run runView(ResultSet rs) throws SQLException {UUID id=UUID.fromString(rs.getString("id"));String state=rs.getString("status"),metrics=rs.getString("metrics_json");return new Run(id,UUID.fromString(rs.getString("suite_id")),UUID.fromString(rs.getString("workspace_id")),UUID.fromString(rs.getString("release_id")),rs.getInt("suite_revision"),rs.getString("suite_title"),rs.getString("suite_hash"),rs.getString("corpus_hash"),rs.getString("index_version"),rs.getString("evaluator_version"),rs.getInt("top_k"),rs.getInt("case_count"),rs.getInt("completed_cases"),state,rs.getString("error_code"),instant(rs,"created_at"),instant(rs,"started_at"),instant(rs,"finished_at"),metrics==null?null:json.read(metrics,Metrics.class),rs.getString("result_hash"),state.equals("COMPLETED")?"/api/v1/evaluation-runs/"+id+"/report":null,List.of());}
    private RunRow runRow(ResultSet rs,int row) throws SQLException {return new RunRow(runView(rs),rs.getString("lease_token"),instant(rs,"lease_until"),rs.getString("report_json"),rs.getString("report_hash"),rs.getInt("report_bytes"),rs.getInt("result_bytes"));}
    private Run view(RunRow row,boolean details){Run r=row.view();if(r.status().equals("COMPLETED")){
        String canonical=json.write(json.tree(row.report()));var report=json.tree(canonical);
        if(!json.sha256(canonical).equals(row.reportHash()) || canonical.getBytes(StandardCharsets.UTF_8).length!=row.reportBytes() || !r.id().toString().equals(report.path("runId").asText()) || !r.suiteId().toString().equals(report.path("suiteId").asText()) || r.suiteRevision()!=report.path("suiteRevision").asInt() || !r.suiteTitle().equals(report.path("suite").path("title").asText()) || !r.releaseId().toString().equals(report.path("suite").path("releaseId").asText()) || !r.corpusHash().equals(report.path("suite").path("corpusHash").asText()) || !r.indexVersion().equals(report.path("suite").path("indexVersion").asText()) || !r.evaluatorVersion().equals(report.path("evaluatorVersion").asText()) || r.topK()!=report.path("topK").asInt() || !r.suiteHash().equals(report.path("suiteHash").asText()) || !r.resultHash().equals(report.path("resultHash").asText()) || !json.hash(r.metrics()).equals(json.hash(report.path("metrics"))))throw integrity();
    }
        // A later case may commit after the metadata read. Read only its advertised immutable prefix.
        List<CaseResult> results=details?results(r.id(),r.completedCases()):List.of();if(details && (results.size()!=r.completedCases() || results.stream().mapToInt(result->json.write(result).getBytes(StandardCharsets.UTF_8).length).sum()!=row.resultBytes()))throw integrity();if(details && r.status().equals("COMPLETED") && !semanticHash(r,results).equals(r.resultHash()))throw integrity();
        return new Run(r.id(),r.suiteId(),r.workspaceId(),r.releaseId(),r.suiteRevision(),r.suiteTitle(),r.suiteHash(),r.corpusHash(),r.indexVersion(),r.evaluatorVersion(),r.topK(),r.caseCount(),r.completedCases(),r.status(),r.errorCode(),r.createdAt(),r.startedAt(),r.finishedAt(),r.metrics(),r.resultHash(),r.reportPath(),results);
    }
    private List<CaseResult> results(UUID id,int visible){return jdbc.query("SELECT result_json,result_hash,byte_size FROM evaluation_case_result WHERE run_id=? AND case_position<? ORDER BY case_position",(rs,n)->{var result=json.read(rs.getString(1),CaseResult.class);if(!json.hash(result).equals(rs.getString(2)) || json.write(result).getBytes(StandardCharsets.UTF_8).length!=rs.getInt(3))throw integrity();return result;},id.toString(),visible);}
    /** Same owner -> workspace -> suite -> run lock order as admission and account closure. */
    private boolean lockRunScope(UUID id){var ids=jdbc.queryForList("SELECT suite_id FROM evaluation_run WHERE id=?",String.class,id.toString());if(ids.isEmpty())throw DomainException.missing();Scope scope=scope(UUID.fromString(ids.get(0)));String owner=jdbc.queryForObject("SELECT status FROM app_user WHERE id=? FOR UPDATE",String.class,scope.owner().toString());String workspace=jdbc.queryForObject("SELECT status FROM learning_workspace WHERE id=? AND owner_id=? FOR UPDATE",String.class,scope.workspace().toString(),scope.owner().toString());String suite=jdbc.queryForObject("SELECT status FROM evaluation_suite WHERE id=? FOR UPDATE",String.class,scope.id().toString());return "ACTIVE".equals(owner)&&"ACTIVE".equals(workspace)&&"ACTIVE".equals(suite);}
    private boolean leaseMatches(RunRow row,Ticket ticket){return row.view().status().equals("RUNNING")&&Objects.equals(row.lease(),ticket.lease())&&row.leaseUntil()!=null&&row.leaseUntil().isAfter(clock.instant());}
    private void terminal(RunRow row,String state,String code,boolean event){jdbc.update("UPDATE evaluation_run SET status=?,error_code=?,lease_token=NULL,lease_until=NULL,finished_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')",state,code,time(clock.instant()),row.view().id().toString());if(event)events.append(row.view().workspaceId(),"EVALUATION_RUN_"+state,"SERVER",Map.of("runId",row.view().id(),"errorCode",code));}
    private String semanticHash(Run run,List<CaseResult> results){var canonical=results.stream().map(result->{ObjectNode node=(ObjectNode)json.tree(json.write(result));node.remove("elapsedMillis");return node;}).toList();return json.hash(Map.of("suiteHash",run.suiteHash(),"corpusHash",run.corpusHash(),"indexVersion",run.indexVersion(),"evaluatorVersion",run.evaluatorVersion(),"topK",run.topK(),"results",canonical));}
    private static Metrics metrics(List<CaseResult> results,long elapsed){var matches=results.stream().filter(r->r.expectation().equals("MATCH")).toList();var empty=results.stream().filter(r->r.expectation().equals("NO_RESULTS")).toList();int passed=(int)results.stream().filter(CaseResult::passed).count();return new Metrics(results.size(),matches.size(),empty.size(),passed,results.size()-passed,matches.isEmpty()?null:matches.stream().mapToDouble(CaseResult::precisionAtK).average().orElseThrow(),matches.isEmpty()?null:matches.stream().mapToDouble(CaseResult::recallAtK).average().orElseThrow(),matches.isEmpty()?null:matches.stream().mapToDouble(CaseResult::reciprocalRank).average().orElseThrow(),matches.isEmpty()?null:(double)matches.stream().filter(r->r.relevantCount()>0).count()/matches.size(),empty.isEmpty()?null:(double)empty.stream().filter(CaseResult::passed).count()/empty.size(),Math.max(0,elapsed));}
    private long count(String sql,Object... arguments){Long result=jdbc.queryForObject(sql,Long.class,arguments);return result==null?0:result;}
    private static void requireActive(Scope scope){if(!scope.status().equals("ACTIVE"))throw DomainException.conflict("The evaluation suite is archived");}
    private static String cursor(String value){if(value==null || value.isBlank())return "";try{return UUID.fromString(value).toString();}catch(IllegalArgumentException failure){throw DomainException.invalid("Invalid evaluation cursor");}}
    private static void optionalText(String value,int max){if(value==null || value.length()>max || value.chars().anyMatch(c->c<32&&c!='\n'&&c!='\r'&&c!='\t'))throw DomainException.invalid("Evaluation text is too long, missing or contains control characters");}
    private static String clip(String value,int max){if(value.length()<=max)return value;int end=Character.isHighSurrogate(value.charAt(max-1))?max-1:max;return value.substring(0,end);}
    private static Timestamp time(Instant value){return Timestamp.from(value);}
    private static Instant instant(ResultSet rs,String name) throws SQLException {Timestamp value=rs.getTimestamp(name);return value==null?null:value.toInstant();}
    private static DomainException integrity(){return new DomainException("EVALUATION_INTEGRITY_FAILURE",409,"The saved evaluation evidence does not match its frozen hashes");}
    private static DomainException limit(String message){return new DomainException("EVALUATION_LIMIT_EXCEEDED",429,message);}
}
