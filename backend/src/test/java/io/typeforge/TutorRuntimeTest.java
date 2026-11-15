package io.typeforge;

import io.typeforge.artifacts.ArtifactService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.tutor.HttpTutorModelProvider;
import io.typeforge.tutor.TutorAnswerProvider.Answer;
import io.typeforge.tutor.TutorAnswerProvider.Usage;
import io.typeforge.tutor.TutorDocumentService;
import io.typeforge.tutor.TutorService;
import io.typeforge.tutor.TutorService.Mode;
import io.typeforge.tutor.TutorSettings;
import io.typeforge.tutor.TutorWorker;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL requests, recovery, quotas and document lineage; no mocked persistence or model claims. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:typeforge-tutor-runtime;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH","typeforge.tutor.worker-enabled=false","typeforge.tutor.provider-url=","typeforge.tutor.api-key=","typeforge.tutor.model="})
@ActiveProfiles({"test","demo"})
@Timeout(30)
class TutorRuntimeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired WorkspaceService workspaces;
    @Autowired JsonCodec json;
    @Autowired Clock clock;
    @Autowired EventJournal events;
    @Autowired LocalKnowledgeSearch search;
    @Autowired TutorService tutors;
    @Autowired TutorWorker worker;
    @Autowired TutorDocumentService documents;
    @Autowired ArtifactService artifacts;
    @Autowired SchemaCatalog schemas;
    @Autowired TutorSettings settings;
    @Autowired HttpTutorModelProvider provider;
    private UUID owner;
    private UUID workspace;
    private WorkspaceService.Context snapshot;
    private TutorService.ConversationSummary conversation;
    private static String key() { return UUID.randomUUID().toString(); }

    @BeforeEach void fixture() {
        owner=user();workspace=workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"Tutor workspace","ON_DEMAND",key()).id();
        workspaces.createGoal(owner,workspace,"Learn PECS",List.of("Use published evidence"),key());
        snapshot=workspaces.createContext(owner,workspace);
        conversation=tutors.createConversation(owner,new TutorService.ConversationInput(workspace,DemoContentInitializer.RELEASE_ID,"Generic reasoning"),key());
    }
    private UUID user() {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",id.toString(),"tutor-"+id,"fixture-not-a-login-credential");
        return id;
    }
    @AfterEach void stopUnfinishedFixtures() {
        var rows=jdbc.queryForList("SELECT r.id,c.user_id,x.request_epoch FROM tutor_request r JOIN conversation c ON c.id=r.conversation_id JOIN tutor_request_runtime x ON x.request_id=r.id WHERE r.status IN ('QUEUED','RETRIEVING','GENERATING','VALIDATING')");
        for(var row:rows) tutors.cancel(UUID.fromString(row.get("user_id").toString()),UUID.fromString(row.get("id").toString()),((Number)row.get("request_epoch")).longValue());
    }
    private TutorService.TutorInput input(String question) { return new TutorService.TutorInput(conversation.id(),snapshot.id(),Mode.EVIDENCE_ONLY,question,1,null); }
    private TutorService.TutorResult submit() { return tutors.submit(owner,input("PECS 通配符 extends super"),key()); }
    private TutorService service(TutorSettings configuration,HttpTutorModelProvider model) { return new TutorService(jdbc,tx,workspaces,events,json,clock,configuration,model); }
    private static TutorSettings limits(int daily,int pending) { return new TutorSettings("","","",1000,256,1_000_000,daily,pending,false); }

    @Test void evidenceAnswerIsPersistentExplicitlyNonModelAndCanResumeWithNewObjects() {
        var queued=submit();assertEquals("QUEUED",queued.status());assertNull(queued.answer());
        var restarted=service(settings,provider);
        try(var workerAfterRestart=new WorkerClose(new TutorWorker(restarted,search,provider,settings))) {
            assertTrue(workerAfterRestart.worker.runOne());
        }
        var done=restarted.get(owner,queued.id());
        assertEquals("COMPLETED",done.status());assertEquals(Mode.EVIDENCE_ONLY,done.mode());
        assertTrue(done.answer().contains("未调用语言模型"));assertFalse(done.evidenceRefs().isEmpty());
        assertEquals("NONE",done.usage().billingState());assertEquals(0,done.usage().reservedTokens());
        schemas.validate("TutorResult",done);
        for(var evidence:done.evidenceRefs()) assertEquals(evidence.contentHash(),jdbc.queryForObject("SELECT content_hash FROM knowledge_chunk WHERE id=? AND revision=?",String.class,evidence.chunkId().toString(),evidence.revision()));
        var history=restarted.conversation(owner,conversation.id(),null,20);
        assertEquals(queued.id(),history.messages().get(0).id());schemas.validate("Conversation",history);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tutor_message WHERE request_id=?",Integer.class,queued.id().toString()));
    }
    @Test void identicalKeysReplayAndDifferentRequestsConflictWithoutChargingTwice() {
        String requestKey=key();var request=input("PECS");
        var first=tutors.submit(owner,request,requestKey);var replay=tutors.submit(owner,request,requestKey);
        assertEquals(first.id(),replay.id());
        assertEquals(409,assertThrows(DomainException.class,()->tutors.submit(owner,input("Different question"),requestKey)).status());
        assertEquals(1,jdbc.queryForObject("SELECT request_count FROM tutor_daily_budget WHERE user_id=?",Integer.class,owner.toString()));
        tutors.cancel(owner,first.id(),first.epoch());
        assertEquals("CANCELLED",tutors.submit(owner,request,requestKey).status());
        String conversationKey=key();var original=new TutorService.ConversationInput(workspace,DemoContentInitializer.RELEASE_ID,"One");
        assertEquals(tutors.createConversation(owner,original,conversationKey).id(),tutors.createConversation(owner,original,conversationKey).id());
        assertEquals(409,assertThrows(DomainException.class,()->tutors.createConversation(owner,new TutorService.ConversationInput(workspace,DemoContentInitializer.RELEASE_ID,"Two"),conversationKey)).status());
    }
    @Test void fixedScopePreventsForeignRequestsSnapshotsReleasesAndCursors() {
        var request=submit();UUID other=user();
        assertEquals(404,assertThrows(DomainException.class,()->tutors.get(other,request.id())).status());
        assertEquals(404,assertThrows(DomainException.class,()->tutors.cancel(other,request.id(),1)).status());
        assertEquals(404,assertThrows(DomainException.class,()->tutors.conversation(other,conversation.id(),null,20)).status());
        UUID otherWorkspace=workspaces.create(other,DemoContentInitializer.RELEASE_ID,"Foreign","QUIET",key()).id();
        UUID foreignSnapshot=workspaces.createContext(other,otherWorkspace).id();
        var forged=new TutorService.TutorInput(conversation.id(),foreignSnapshot,Mode.EVIDENCE_ONLY,"PECS",0,null);
        assertEquals(404,assertThrows(DomainException.class,()->tutors.submit(owner,forged,key())).status());
        assertEquals(400,assertThrows(DomainException.class,()->tutors.createConversation(owner,new TutorService.ConversationInput(workspace,UUID.randomUUID(),"Wrong release"),key())).status());
        var otherConversation=tutors.createConversation(other,new TutorService.ConversationInput(otherWorkspace,DemoContentInitializer.RELEASE_ID,"Foreign"),key());
        assertEquals(404,assertThrows(DomainException.class,()->tutors.conversations(owner,null,otherConversation.id(),10)).status());
    }
    @Test void unavailableModelIs503AndDoesNotCreateOrReserveARequest() {
        assertFalse(provider.readiness().configured());assertTrue(provider.enabledCapabilities().isEmpty());
        var request=new TutorService.TutorInput(conversation.id(),snapshot.id(),Mode.MODEL,"PECS",1,null);
        assertEquals(503,assertThrows(DomainException.class,()->tutors.submit(owner,request,key())).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tutor_request WHERE conversation_id=?",Integer.class,conversation.id().toString()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tutor_daily_budget WHERE user_id=?",Integer.class,owner.toString()));
    }
    @Test void zeroEvidenceIsTerminalWithoutInventingAnAnswer() {
        var queued=tutors.submit(owner,input("unmatchedtoken"+key().replace("-","")),key());
        assertTrue(worker.runOne());var result=tutors.get(owner,queued.id());
        assertEquals("INSUFFICIENT_EVIDENCE",result.status());assertNull(result.answer());assertTrue(result.evidenceRefs().isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tutor_message WHERE request_id=?",Integer.class,result.id().toString()));
    }
    @Test void queuedAndClaimedCancellationAreIdempotentAndRejectOldResults() {
        var queued=submit();var ticket=tutors.claimNext();assertNotNull(ticket);
        var evidence=search.search(DemoContentInitializer.RELEASE_ID,"PECS",null,2).items();tutors.prepare(ticket,evidence);
        var cancelled=tutors.cancel(owner,queued.id(),ticket.epoch());
        assertEquals(ticket.epoch()+1,cancelled.epoch());assertEquals("CANCELLED",cancelled.status());
        assertEquals(cancelled,tutors.cancel(owner,queued.id(),ticket.epoch()));
        tutors.accept(ticket,new Answer("Late stale reply",evidence.stream().map(e->e.chunkId()).toList(),"fixture",new Usage(0,0,0)));
        assertNull(tutors.get(owner,queued.id()).answer());
        assertEquals("TUTOR_STALE_EXECUTION",assertThrows(DomainException.class,()->tutors.prepare(ticket,evidence)).code());
        assertEquals(409,assertThrows(DomainException.class,()->tutors.cancel(owner,queued.id(),cancelled.epoch()+2)).status());
    }
    @Test void expiredUndispatchedLeaseCanRecoverButOriginalEpochCannotOverwrite() {
        var queued=submit();var old=tutors.claimNext();assertNotNull(old);
        jdbc.update("UPDATE tutor_request_runtime SET lease_until=? WHERE request_id=?",Timestamp.from(Instant.now().minusSeconds(1)),queued.id().toString());
        try(var recovered=new WorkerClose(new TutorWorker(service(settings,provider),search,provider,settings))) { assertTrue(recovered.worker.runOne()); }
        var done=tutors.get(owner,queued.id());assertEquals("COMPLETED",done.status());assertTrue(done.epoch()>old.epoch());
        tutors.accept(old,new Answer("Old epoch must never win",List.of(done.evidenceRefs().get(0).chunkId()),"fixture",new Usage(0,0,0)));
        assertEquals(done.answer(),tutors.get(owner,queued.id()).answer());
    }
    @Test void deadlinesAndRetryBoundsConvergeInsteadOfKeepingAQueueHeadForever() {
        var expired=submit();jdbc.update("UPDATE tutor_request_runtime SET deadline_at=? WHERE request_id=?",Timestamp.from(Instant.now().minusSeconds(1)),expired.id().toString());
        var retry=submit();jdbc.update("UPDATE tutor_request_runtime SET attempt_count=3 WHERE request_id=?",retry.id().toString());
        var valid=submit();
        // Millisecond timestamps can tie; random UUID order is not submission order.
        // Fix the queue order so this test specifically proves invalid heads cannot block valid work.
        Instant queued=Instant.now().minusSeconds(5);
        jdbc.update("UPDATE tutor_request SET created_at=? WHERE id=?",Timestamp.from(queued),expired.id().toString());
        jdbc.update("UPDATE tutor_request SET created_at=? WHERE id=?",Timestamp.from(queued.plusSeconds(1)),retry.id().toString());
        jdbc.update("UPDATE tutor_request SET created_at=? WHERE id=?",Timestamp.from(queued.plusSeconds(2)),valid.id().toString());
        assertTrue(worker.runOne());
        assertEquals("TUTOR_DEADLINE_EXCEEDED",tutors.get(owner,expired.id()).errorCode());
        assertEquals("TUTOR_RETRY_EXHAUSTED",tutors.get(owner,retry.id()).errorCode());
        assertEquals("COMPLETED",tutors.get(owner,valid.id()).status());
    }
    @Test void revokedContextStopsQueuedWorkAndHidesPreviouslyCompletedAnswers() {
        var memory=workspaces.stateMemory(owner,workspace,"Prefer explicit examples",key());snapshot=workspaces.createContext(owner,workspace);
        var completed=submit();assertTrue(worker.runOne());assertNotNull(tutors.get(owner,completed.id()).answer());
        var queued=submit();workspaces.revokeMemory(owner,workspace,memory.id(),memory.revision());
        assertFalse(worker.runOne());
        assertEquals("TUTOR_CONTEXT_CHANGED",tutors.get(owner,queued.id()).errorCode());
        var hidden=tutors.get(owner,completed.id());assertEquals("STALE",hidden.sourceState());assertNull(hidden.answer());assertTrue(hidden.evidenceRefs().isEmpty());
        assertTrue(tutors.conversation(owner,conversation.id(),null,20).messages().stream().allMatch(result->result.answer()==null));
    }
    @Test void quotaCountsArePerOwnerAcrossWorkspacesAndCancellationDoesNotRefundRequestRate() {
        TutorSettings limited=limits(2,1);var service=service(limited,new HttpTutorModelProvider(limited,json));
        var first=service.submit(owner,input("PECS"),key());
        assertEquals("TUTOR_PENDING_LIMIT",assertThrows(DomainException.class,()->service.submit(owner,input("Generics"),key())).code());
        service.cancel(owner,first.id(),1);
        UUID second=workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"Same owner","QUIET",key()).id();
        var nextConversation=service.createConversation(owner,new TutorService.ConversationInput(second,DemoContentInitializer.RELEASE_ID,"Other workspace"),key());
        var nextContext=workspaces.createContext(owner,second);
        var request=new TutorService.TutorInput(nextConversation.id(),nextContext.id(),Mode.EVIDENCE_ONLY,"PECS",0,null);
        var secondRequest=service.submit(owner,request,key());service.cancel(owner,secondRequest.id(),1);
        assertEquals("TUTOR_QUOTA_EXCEEDED",assertThrows(DomainException.class,()->service.submit(owner,request,key())).code());
        assertEquals(2,jdbc.queryForObject("SELECT request_count FROM tutor_daily_budget WHERE user_id=?",Integer.class,owner.toString()));
    }
    @Test void duplicateCompletionWithDifferentTextIsNotSilentlyAccepted() {
        var queued=submit();var ticket=tutors.claimNext();assertNotNull(ticket);
        var evidence=search.search(DemoContentInitializer.RELEASE_ID,"PECS",null,1).items();tutors.prepare(ticket,evidence);
        var answer=new Answer("Reviewed source excerpt",List.of(evidence.get(0).chunkId()),"fixture",new Usage(0,0,0));
        tutors.accept(ticket,answer);tutors.accept(ticket,answer);
        assertEquals(409,assertThrows(DomainException.class,()->tutors.accept(ticket,new Answer("Conflicting text",answer.citedChunkIds(),"fixture",answer.usage()))).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tutor_message WHERE request_id=?",Integer.class,queued.id().toString()));
    }
    @Test void answerToDocumentRetainsAllSourcesIsIdempotentAndBecomesStaleOnRevocation() {
        var memory=workspaces.stateMemory(owner,workspace,"Use a concrete PECS example",key());snapshot=workspaces.createContext(owner,workspace);
        var request=submit();worker.runOne();String documentKey=key();
        var saved=documents.create(owner,request.id(),"PECS study document",documentKey);schemas.validate("TutorDocumentResult",saved);
        assertEquals("DRAFT",saved.reviewStatus());assertEquals(saved,documents.create(owner,request.id(),"PECS study document",documentKey));
        var version=artifacts.version(owner,saved.artifactId(),saved.revision());
        assertTrue(version.inputRefs().containsAll(snapshot.references()));assertTrue(version.inputRefs().stream().anyMatch(ref->ref.kind().equals("MEMORY") && ref.id().equals(memory.id())));
        assertTrue(version.content().toString().contains("EVIDENCE_ONLY"));assertTrue(version.content().toString().contains(request.id().toString()));
        assertEquals(409,assertThrows(DomainException.class,()->documents.create(owner,request.id(),"Changed key payload",documentKey)).status());
        workspaces.revokeMemory(owner,workspace,memory.id(),memory.revision());
        assertEquals("STALE",artifacts.version(owner,saved.artifactId(),saved.revision()).reviewStatus());
        assertNull(tutors.get(owner,request.id()).answer());
        assertEquals(409,assertThrows(DomainException.class,()->documents.create(owner,request.id(),"Cannot copy revoked answer",key())).status());
    }
    @Test void documentNeverDropsExcessDependenciesToFitTheManifestLimit() {
        var refs=new ArrayList<io.typeforge.workspace.WorkspaceContextStore.Reference>();
        for(int i=0;i<64;i++) {
            var goal=workspaces.createGoal(owner,workspace,"Distinct source "+i,List.of("Explicit source"),key());
            refs.add(new io.typeforge.workspace.WorkspaceContextStore.Reference("GOAL",goal.id(),goal.revision(),json.hash(Map.of("text",goal.text(),"acceptance",goal.acceptance()))));
        }
        var scope=workspaces.get(owner,workspace);UUID snapshotId=UUID.randomUUID();
        String hash=json.hash(Map.of("workspaceId",workspace,"revision",scope.revision(),"sequence",scope.eventSequence(),"references",refs));
        jdbc.update("INSERT INTO context_snapshot(id,workspace_id,workspace_revision,event_sequence,references_json,content_hash) VALUES(?,?,?,?,?,?)",snapshotId.toString(),workspace.toString(),scope.revision(),scope.eventSequence(),json.write(refs),hash);
        var request=tutors.submit(owner,new TutorService.TutorInput(conversation.id(),snapshotId,Mode.EVIDENCE_ONLY,"PECS",1,null),key());worker.runOne();
        assertEquals("COMPLETED",tutors.get(owner,request.id()).status());
        assertEquals(422,assertThrows(DomainException.class,()->documents.create(owner,request.id(),"No dropped source",key())).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learning_artifact WHERE workspace_id=?",Integer.class,workspace.toString()));
    }
    @Test void historyPaginationUsesOwnerScopedStableCursors() {
        var first=submit();worker.runOne();var second=submit();worker.runOne();
        var page=tutors.conversation(owner,conversation.id(),null,1);assertEquals(1,page.messages().size());assertNotNull(page.nextCursor());
        var remaining=tutors.conversation(owner,conversation.id(),page.nextCursor(),1);
        assertEquals(1,remaining.messages().size());assertNotEquals(page.messages().get(0).id(),remaining.messages().get(0).id());assertNull(remaining.nextCursor());
        assertTrue(List.of(first.id(),second.id()).contains(remaining.messages().get(0).id()));
        var list=tutors.conversations(owner,workspace,null,1);schemas.validate("ConversationList",list);assertEquals(conversation.id(),list.items().get(0).id());
        assertEquals(400,assertThrows(DomainException.class,()->tutors.conversations(owner,null,null,101)).status());
    }
    @Test void disabledAndArchivedQueueHeadsCloseWithoutStarvingAHealthyLearner() {
        var disabled=submit();UUID archivedOwner=user();
        UUID archivedWorkspace=workspaces.create(archivedOwner,DemoContentInitializer.RELEASE_ID,"Archived fixture","QUIET",key()).id();
        var archivedConversation=tutors.createConversation(archivedOwner,new TutorService.ConversationInput(archivedWorkspace,DemoContentInitializer.RELEASE_ID,"Archived conversation"),key());
        var archived=tutors.submit(archivedOwner,new TutorService.TutorInput(archivedConversation.id(),workspaces.createContext(archivedOwner,archivedWorkspace).id(),Mode.EVIDENCE_ONLY,"PECS",1,null),key());
        UUID healthyOwner=user();UUID healthyWorkspace=workspaces.create(healthyOwner,DemoContentInitializer.RELEASE_ID,"Healthy fixture","QUIET",key()).id();
        var healthyConversation=tutors.createConversation(healthyOwner,new TutorService.ConversationInput(healthyWorkspace,DemoContentInitializer.RELEASE_ID,"Healthy conversation"),key());
        var healthy=tutors.submit(healthyOwner,new TutorService.TutorInput(healthyConversation.id(),workspaces.createContext(healthyOwner,healthyWorkspace).id(),Mode.EVIDENCE_ONLY,"PECS",1,null),key());
        jdbc.update("UPDATE tutor_request SET created_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(3)),disabled.id().toString());
        jdbc.update("UPDATE tutor_request SET created_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(2)),archived.id().toString());
        jdbc.update("UPDATE tutor_request SET created_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),healthy.id().toString());
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?",owner.toString());
        jdbc.update("UPDATE learning_workspace SET status='ARCHIVED' WHERE id=?",archivedWorkspace.toString());
        try {
            assertTrue(worker.runOne());
            assertEquals("TUTOR_SCOPE_UNAVAILABLE",jdbc.queryForObject("SELECT error_code FROM tutor_request WHERE id=?",String.class,disabled.id().toString()));
            assertEquals("TUTOR_SCOPE_UNAVAILABLE",jdbc.queryForObject("SELECT error_code FROM tutor_request WHERE id=?",String.class,archived.id().toString()));
            assertEquals("COMPLETED",tutors.get(healthyOwner,healthy.id()).status());
            assertEquals(404,assertThrows(DomainException.class,()->tutors.get(owner,disabled.id())).status());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='TUTOR_REQUEST_STOPPED'",Integer.class,workspace.toString()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='TUTOR_REQUEST_STOPPED'",Integer.class,archivedWorkspace.toString()));
        } finally {
            jdbc.update("UPDATE app_user SET status='ACTIVE' WHERE id=?",owner.toString());
            jdbc.update("UPDATE learning_workspace SET status='ACTIVE' WHERE id=?",archivedWorkspace.toString());
        }
    }
    @Test void expiredDeadlineAndLeaseAreDistinguishedFromChangedContext() {
        var request=submit();var ticket=tutors.claimNext();assertNotNull(ticket);
        jdbc.update("UPDATE tutor_request_runtime SET deadline_at=? WHERE request_id=?",Timestamp.from(Instant.now().minusSeconds(1)),request.id().toString());
        var deadline=assertThrows(DomainException.class,()->tutors.prepare(ticket,List.of()));assertEquals("TUTOR_DEADLINE_EXCEEDED",deadline.code());
        tutors.fail(ticket,deadline.code(),null);assertEquals(deadline.code(),tutors.get(owner,request.id()).errorCode());
        var second=submit();var lease=tutors.claimNext();assertNotNull(lease);
        jdbc.update("UPDATE tutor_request_runtime SET lease_until=? WHERE request_id=?",Timestamp.from(Instant.now().minusSeconds(1)),second.id().toString());
        var expiry=assertThrows(DomainException.class,()->tutors.prepare(lease,List.of()));assertEquals("TUTOR_LEASE_EXPIRED",expiry.code());
        tutors.fail(lease,expiry.code(),null);assertEquals(expiry.code(),tutors.get(owner,second.id()).errorCode());
    }
    private static final class WorkerClose implements AutoCloseable {
        private final TutorWorker worker;
        WorkerClose(TutorWorker worker) { this.worker=worker; }
        @Override public void close() { worker.close(); }
    }
}
