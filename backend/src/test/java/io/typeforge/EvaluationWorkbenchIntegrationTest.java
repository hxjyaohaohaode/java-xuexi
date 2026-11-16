package io.typeforge;

import static io.typeforge.evidence.EvaluationModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.evidence.EvaluationService;
import io.typeforge.evidence.EvaluationWorker;
import io.typeforge.identity.AccountClosureService;
import io.typeforge.ingestion.BatchImportModels;
import io.typeforge.ingestion.BatchImportService;
import io.typeforge.ingestion.IngestionModels;
import io.typeforge.ingestion.ManualIngestionService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties={"typeforge.evaluation.worker-enabled=false","typeforge.ingestion.cleanup-delay-millis=3600000"})
@AutoConfigureMockMvc @ActiveProfiles({"test","demo"}) @Timeout(60)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvaluationWorkbenchIntegrationTest {
    private static final String PASSWORD="evaluation-fixture-password-123";
    private static final UUID RELEASE=DemoContentInitializer.RELEASE_ID;
    @Autowired EvaluationService evaluations; @Autowired EvaluationWorker worker;
    @Autowired WorkspaceService workspaces; @Autowired CurriculumService curriculum;
    @Autowired LocalKnowledgeSearch search; @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json; @Autowired SchemaCatalog schemas; @Autowired MockMvc mvc;
    @Autowired AccountClosureService closure; @Autowired PasswordEncoder passwords;
    @Autowired BatchImportService batches; @Autowired ManualIngestionService ingestion;
    UUID owner,workspace,expected;String username,encoded;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){PrivateModuleDatabaseFixture.configure(properties,"evaluation-workbench");}
    @BeforeAll void databaseProof(){PrivateModuleDatabaseFixture.prove(jdbc,json,getClass().getSimpleName());encoded=passwords.encode(PASSWORD);}
    @BeforeEach void setup(){owner=account();username="evaluation-"+owner;workspace=workspaces.create(owner,RELEASE,"真实检索评测","ON_DEMAND",key()).id();expected=search.search(RELEASE,"PECS",null,3).items().get(0).chunkId();}
    @AfterEach void cleanup(){jdbc.update("UPDATE evaluation_run SET status='CANCELLED',error_code='TEST_CLEANUP',lease_token=NULL,lease_until=NULL,finished_at=? WHERE owner_id=? AND status IN ('QUEUED','RUNNING')",Timestamp.from(Instant.now()),owner.toString());}
    private UUID account(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",id.toString(),"evaluation-"+id,encoded);return id;}
    private String key(){return UUID.randomUUID().toString();}
    private MockHttpSession session() throws Exception{return HttpSessionFixture.login(mvc,json,username,PASSWORD);}
    private CaseInput match(){return new CaseInput("pecs","PECS",null,"MATCH",List.of(expected),"本人预期证据，非独立人工评审");}
    private SuiteInput input(){return new SuiteInput("泛型证据评测","检验固定课程检索",List.of(match()));}
    private Suite suite(){return evaluations.create(owner,workspace,input(),key());}
    private Run start(Suite suite){return evaluations.createRun(owner,suite.id(),new RunInput(suite.revision(),3),key());}
    private Run finish(Run run){for(int i=0;i<5 && !evaluations.run(owner,run.id()).status().equals("COMPLETED");i++)worker.runOne();return evaluations.run(owner,run.id());}
    private JsonNode call(MockHttpServletRequestBuilder request,int status) throws Exception{var response=mvc.perform(request).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());return response.getContentAsString().isEmpty()?null:json.tree(response.getContentAsString());}
    private DomainException rejected(Runnable call,int status){DomainException error=assertThrows(DomainException.class,call::run);assertEquals(status,error.status());return error;}

    @Test void suitePinsPublishedContentVersionsWithoutWritingLearningGrades(){
        var suite=suite();schemas.validate("EvaluationSuite",suite);assertEquals(RELEASE,suite.releaseId());assertEquals(1,suite.revision());assertEquals(1,suite.cases().size());assertEquals(expected,suite.cases().get(0).expectedChunks().get(0).chunkId());assertEquals(64,suite.contentHash().length());assertEquals(64,suite.corpusHash().length());assertEquals(LocalKnowledgeSearch.INDEX_VERSION,suite.indexVersion());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_attempt WHERE user_id=?",Integer.class,owner.toString()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event WHERE workspace_id=? AND event_type='ATTEMPT_GRADED'",Integer.class,workspace.toString()));
    }
    @Test void realHttpCompletesExportsAndValidatesEveryResponseWithoutEditorPrivilege() throws Exception{
        var session=session();JsonNode capability=call(get("/api/v1/capabilities").session(session),200);assertTrue(capability.path("enabled").toString().contains("EVALUATION_WORKBENCH"));
        JsonNode created=call(post("/api/v1/workspaces/"+workspace+"/evaluation-suites").session(session).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(json.write(input())),201);schemas.validate("EvaluationSuite",created);String id=created.path("id").asText();
        schemas.validate("EvaluationSuitePage",call(get("/api/v1/workspaces/"+workspace+"/evaluation-suites").session(session),200));schemas.validate("EvaluationSuite",call(get("/api/v1/evaluation-suites/"+id).session(session),200));schemas.validate("EvaluationSuite",call(get("/api/v1/evaluation-suites/"+id+"/revisions/1").session(session),200));
        JsonNode queued=call(post("/api/v1/evaluation-suites/"+id+"/runs").session(session).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"suiteRevision\":1,\"topK\":3}"),202);schemas.validate("EvaluationRun",queued);assertEquals("QUEUED",queued.path("status").asText());String runId=queued.path("id").asText();
        assertTrue(worker.runOne());JsonNode done=call(get("/api/v1/evaluation-runs/"+runId).session(session),200);schemas.validate("EvaluationRun",done);assertEquals("COMPLETED",done.path("status").asText());schemas.validate("EvaluationRunPage",call(get("/api/v1/evaluation-suites/"+id+"/runs").session(session),200));
        var response=mvc.perform(get(done.path("reportPath").asText()).session(session)).andReturn().getResponse();assertEquals(200,response.getStatus());assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));assertEquals("private, no-store",response.getHeader("Cache-Control"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));assertEquals(json.sha256(response.getContentAsString()),response.getHeader("X-Content-SHA256"));schemas.validate("EvaluationReport",json.tree(response.getContentAsString()));assertArrayEquals(response.getContentAsByteArray(),evaluations.report(owner,UUID.fromString(runId)).bytes());
        assertEquals(id,json.tree(response.getContentAsString()).path("suiteId").asText());assertEquals(1,json.tree(response.getContentAsString()).path("suiteRevision").asInt());
        assertFalse(response.getContentAsString().contains("password"));assertFalse(response.getContentAsString().contains("answerSpec"));assertFalse(response.getContentAsString().contains("lease_token"));
    }
    @Test void authenticationCsrfUnknownFieldsAndCrossOwnerNeverBypassPrivateScope() throws Exception{
        var suite=suite();var run=finish(start(suite));var session=session();UUID other=account();var otherSession=HttpSessionFixture.login(mvc,json,"evaluation-"+other,PASSWORD);
        call(get("/api/v1/evaluation-suites/"+suite.id()),401);
        call(post("/api/v1/workspaces/"+workspace+"/evaluation-suites").session(session).header("Idempotency-Key",key()).contentType("application/json").content(json.write(input())),403);
        var extra=(com.fasterxml.jackson.databind.node.ObjectNode)json.tree(json.write(input()));extra.put("ownerId",other.toString());call(post("/api/v1/workspaces/"+workspace+"/evaluation-suites").session(session).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(extra.toString()),400);
        for(String path:List.of("/workspaces/"+workspace+"/evaluation-suites","/evaluation-suites/"+suite.id(),"/evaluation-suites/"+suite.id()+"/revisions/1","/evaluation-suites/"+suite.id()+"/runs","/evaluation-runs/"+run.id(),"/evaluation-runs/"+run.id()+"/report"))call(get("/api/v1"+path).session(otherSession),404);
        call(post("/api/v1/evaluation-runs/"+run.id()+"/cancel").session(otherSession).with(csrf()),404);
        call(post("/api/v1/evaluation-suites/"+suite.id()+"/archive").session(otherSession).with(csrf()).contentType("application/json").content("{\"expectedRevision\":1}"),404);
        rejected(()->evaluations.revise(other,suite.id(),new RevisionInput(1,"foreign","",List.of(match())),key()),404);rejected(()->evaluations.createRun(other,suite.id(),new RunInput(1,1),key()),404);
    }
    @Test void actualBm25RanksProduceExactlyDefinedMatchAndNoResultMetrics(){
        var definition=new SuiteInput("四种真实结果","明确分组分母",List.of(match(),new CaseInput("missing","zzuniquemissingterm123",null,"MATCH",List.of(expected),""),new CaseInput("empty","zzuniqueemptyterm456",null,"NO_RESULTS",List.of(),""),new CaseInput("wrongempty","List",null,"NO_RESULTS",List.of(),"")));
        var run=finish(start(evaluations.create(owner,workspace,definition,key())));assertEquals("COMPLETED",run.status());var m=run.metrics();assertEquals(4,m.totalCases());assertEquals(2,m.matchCases());assertEquals(2,m.noResultCases());assertEquals(2,m.passedCases());assertEquals(2,m.failedCases());assertEquals(1.0/6,m.meanPrecisionAtK(),1e-12);assertEquals(.5,m.meanRecallAtK(),1e-12);assertEquals(.5,m.meanReciprocalRank(),1e-12);assertEquals(.5,m.hitRateAtK(),1e-12);assertEquals(.5,m.noResultAccuracy(),1e-12);
        var result=run.results().get(0);var actual=search.search(RELEASE,"PECS",null,3).items();assertEquals(actual.stream().map(CurriculumService.EvidenceView::chunkId).toList(),result.hits().stream().map(Hit::chunkId).toList());assertEquals(actual.get(0).rankScore(),result.hits().get(0).rankScore());assertEquals(1,result.firstRelevantRank());assertTrue(run.results().get(2).hits().isEmpty());assertNull(run.results().get(2).recallAtK());
    }
    @Test void absentMetricGroupsStayNullRatherThanInventingZeroScores(){
        var suite=evaluations.create(owner,workspace,new SuiteInput("空结果组","",List.of(new CaseInput("none","zzabsent734891",null,"NO_RESULTS",List.of(),""))),key());var run=finish(start(suite));assertEquals("COMPLETED",run.status());assertNull(run.metrics().meanRecallAtK());assertNull(run.metrics().meanPrecisionAtK());assertNull(run.metrics().meanReciprocalRank());assertNull(run.metrics().hitRateAtK());assertEquals(1.0,run.metrics().noResultAccuracy());
    }
    @Test void versionCasAndRetryKeepOldDefinitionAndRunsImmutable(){
        String firstKey=key();var initial=evaluations.create(owner,workspace,input(),firstKey);assertEquals(initial.id(),evaluations.create(owner,workspace,input(),firstKey).id());var oldRun=finish(start(initial));byte[] report=evaluations.report(owner,oldRun.id()).bytes();
        var replacement=new RevisionInput(1,"人工修改后的评测目的","新定义",List.of(new CaseInput("changed","newlymissingquery451",null,"MATCH",List.of(expected),"保留旧结果")));String saveKey=key();var next=evaluations.revise(owner,initial.id(),replacement,saveKey);assertEquals(2,next.revision());assertEquals(next.contentHash(),evaluations.revise(owner,initial.id(),replacement,saveKey).contentHash());assertEquals(initial.contentHash(),evaluations.revision(owner,initial.id(),1).contentHash());
        rejected(()->evaluations.revise(owner,initial.id(),replacement,key()),409);assertEquals(2,evaluations.get(owner,initial.id()).revision());var newer=finish(start(next));assertEquals(0,newer.metrics().passedCases());assertArrayEquals(report,evaluations.report(owner,oldRun.id()).bytes());
        var rerun=evaluations.createRun(owner,initial.id(),new RunInput(1,3),key());assertEquals(oldRun.resultHash(),finish(rerun).resultHash());
    }
    @Test void reusedIdempotencyKeysCannotChangeSuiteOrRunInputs(){
        String key=key();var s=evaluations.create(owner,workspace,input(),key);assertEquals("IDEMPOTENCY_CONFLICT",rejected(()->evaluations.create(owner,workspace,new SuiteInput("另一目的","",List.of(match())),key),409).code());
        String runKey=key();var run=evaluations.createRun(owner,s.id(),new RunInput(1,3),runKey);assertEquals(run.id(),evaluations.createRun(owner,s.id(),new RunInput(1,3),runKey).id());assertEquals("IDEMPOTENCY_CONFLICT",rejected(()->evaluations.createRun(owner,s.id(),new RunInput(1,8),runKey),409).code());assertEquals(1,evaluations.runs(owner,s.id(),30,null).items().size());
    }
    @Test void concurrentEditorsHaveOneWinnerAndNeverOverwriteHistoricalCases() throws Exception{
        var s=suite();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{var first=pool.submit(()->{start.await();try{return evaluations.revise(owner,s.id(),new RevisionInput(1,"first","",List.of(match())),key()).title();}catch(DomainException e){return e.code();}});var second=pool.submit(()->{start.await();try{return evaluations.revise(owner,s.id(),new RevisionInput(1,"second","",List.of(match())),key()).title();}catch(DomainException e){return e.code();}});start.countDown();var outcomes=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));assertEquals(1,outcomes.stream().filter("VERSION_CONFLICT"::equals).count());assertEquals(2,evaluations.get(owner,s.id()).revision());assertEquals(s.contentHash(),evaluations.revision(owner,s.id(),1).contentHash());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_suite_revision WHERE suite_id=?",Integer.class,s.id().toString()));}
        finally{pool.shutdownNow();}
    }
    @Test void concurrentSameKeyRunAdmissionCommitsOnlyOneActualJob() throws Exception{
        var s=suite();String idem=key();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{var first=pool.submit(()->{start.await();return evaluations.createRun(owner,s.id(),new RunInput(1,3),idem);});var second=pool.submit(()->{start.await();return evaluations.createRun(owner,s.id(),new RunInput(1,3),idem);});start.countDown();assertEquals(first.get(15,TimeUnit.SECONDS).id(),second.get(15,TimeUnit.SECONDS).id());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_run WHERE owner_id=?",Integer.class,owner.toString()));}
        finally{pool.shutdownNow();}
    }
    @Test void cancellationPreservesPartialEvidenceWithoutMetricsAndFencesLateWorker(){
        var s=evaluations.create(owner,workspace,new SuiteInput("两例取消","",List.of(match(),new CaseInput("second","zznotfound7733",null,"NO_RESULTS",List.of(),""))),key());var run=start(s);var ticket=evaluations.claimNext();assertNotNull(ticket);assertEquals(run.id(),ticket.id());assertTrue(evaluations.acceptCase(ticket,0,evaluations.evaluate(ticket,ticket.snapshot().cases().get(0))));
        var cancelled=evaluations.cancel(owner,run.id());assertEquals("CANCELLED",cancelled.status());assertEquals(1,cancelled.completedCases());assertEquals(1,cancelled.results().size());assertNull(cancelled.metrics());assertNull(cancelled.reportPath());assertEquals(cancelled.id(),evaluations.cancel(owner,run.id()).id());rejected(()->evaluations.report(owner,run.id()),409);
        int retainedBytes=json.write(cancelled.results().get(0)).getBytes(StandardCharsets.UTF_8).length;assertEquals(retainedBytes,jdbc.queryForObject("SELECT result_bytes FROM evaluation_run WHERE id=?",Integer.class,run.id().toString()));assertEquals(retainedBytes,jdbc.queryForObject("SELECT byte_size FROM evaluation_case_result WHERE run_id=?",Integer.class,run.id().toString()));
        assertFalse(evaluations.acceptCase(ticket,1,evaluations.evaluate(ticket,ticket.snapshot().cases().get(1))));evaluations.complete(ticket,1);assertEquals("CANCELLED",evaluations.run(owner,run.id()).status());assertNull(jdbc.queryForObject("SELECT report_json FROM evaluation_run WHERE id=?",String.class,run.id().toString()));
    }
    @Test void archiveCancelsPendingButRetainsFrozenCompletedReports(){
        var s=suite();var done=finish(start(s));byte[] evidence=evaluations.report(owner,done.id()).bytes();var pending=start(s);var archived=evaluations.archive(owner,s.id(),1);assertEquals("ARCHIVED",archived.status());assertEquals("CANCELLED",evaluations.run(owner,pending.id()).status());assertEquals("SUITE_ARCHIVED",evaluations.run(owner,pending.id()).errorCode());assertEquals(0,evaluations.suites(owner,workspace,30,null,false).items().size());assertEquals(1,evaluations.suites(owner,workspace,30,null,true).items().size());assertArrayEquals(evidence,evaluations.report(owner,done.id()).bytes());rejected(()->start(s),409);rejected(()->evaluations.revise(owner,s.id(),new RevisionInput(1,"late","",List.of(match())),key()),409);assertEquals("ARCHIVED",evaluations.archive(owner,s.id(),1).status());
    }
    @Test void expiredLeaseCannotWriteAndRecoveryRecordsFailureRatherThanAFalseScore(){
        var run=start(suite());var ticket=evaluations.claimNext();assertNotNull(ticket);jdbc.update("UPDATE evaluation_run SET lease_until=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),run.id().toString());assertFalse(evaluations.active(ticket));assertFalse(evaluations.acceptCase(ticket,0,evaluations.evaluate(ticket,ticket.snapshot().cases().get(0))));assertNull(evaluations.claimNext());var failed=evaluations.run(owner,run.id());assertEquals("FAILED",failed.status());assertEquals("EVALUATION_LEASE_EXPIRED",failed.errorCode());assertNull(failed.metrics());evaluations.complete(ticket,1);assertEquals("FAILED",evaluations.run(owner,run.id()).status());
    }
    @Test void queuedDeadlineAndChangedEvaluatorNeverBecomeCompleted(){
        var s=suite();var expired=start(s);jdbc.update("UPDATE evaluation_run SET created_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(601)),expired.id().toString());assertNull(evaluations.claimNext());assertEquals("EVALUATION_QUEUE_EXPIRED",evaluations.run(owner,expired.id()).errorCode());
        var incompatible=start(s);jdbc.update("UPDATE evaluation_run SET index_version='not-the-admitted-index' WHERE id=?",incompatible.id().toString());assertNull(evaluations.claimNext());assertEquals("EVALUATION_VERSION_CHANGED",evaluations.run(owner,incompatible.id()).errorCode());
    }
    @Test void closedAccountRevokesQueuedAndLeasedWorkWithoutTouchingAnotherOwner(){
        var s=suite();var done=finish(start(s));var queued=start(s);var ticket=evaluations.claimNext();assertNotNull(ticket);UUID other=account();UUID otherSpace=workspaces.create(other,RELEASE,"Other owner","ON_DEMAND",key()).id();var otherSuite=evaluations.create(other,otherSpace,input(),key());
        closure.close(owner,PASSWORD,username);assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM evaluation_run WHERE id=?",String.class,queued.id().toString()));assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM evaluation_run WHERE id=?",String.class,done.id().toString()));assertEquals("ARCHIVED",jdbc.queryForObject("SELECT status FROM evaluation_suite WHERE id=?",String.class,s.id().toString()));assertFalse(evaluations.acceptCase(ticket,0,evaluations.evaluate(ticket,ticket.snapshot().cases().get(0))));rejected(()->evaluations.report(owner,done.id()),404);assertEquals("ACTIVE",evaluations.get(other,otherSuite.id()).status());assertEquals(otherSpace,evaluations.get(other,otherSuite.id()).workspaceId());
    }
    @Test void archivedWorkspaceIsCancelledByWorkerInsteadOfBlockingOtherWork(){
        var run=start(suite());jdbc.update("UPDATE learning_workspace SET status='ARCHIVED' WHERE id=?",workspace.toString());assertNull(evaluations.claimNext());var result=evaluations.run(owner,run.id());assertEquals("CANCELLED",result.status());assertEquals("EVALUATION_SCOPE_INACTIVE",result.errorCode());assertNull(result.metrics());
    }
    @Test void corpusTitleMutationIsDetectedEvenWhenBodyHashStillMatches(){
        var s=suite();var run=start(s);String original=jdbc.queryForObject("SELECT title FROM knowledge_chunk WHERE id=? AND revision=1",String.class,expected.toString());
        try{jdbc.update("UPDATE knowledge_chunk SET title=? WHERE id=? AND revision=1",original+" changed",expected.toString());assertEquals("EVALUATION_CORPUS_CHANGED",rejected(()->start(s),409).code());assertTrue(worker.runOne());var failed=evaluations.run(owner,run.id());assertEquals("FAILED",failed.status());assertEquals("EVALUATION_CORPUS_CHANGED",failed.errorCode());assertEquals(0,failed.completedCases());assertNull(failed.metrics());}
        finally{jdbc.update("UPDATE knowledge_chunk SET title=? WHERE id=? AND revision=1",original,expected.toString());}
    }
    @Test void corruptionOfBodyOrSavedSuiteIsRejectedBeforeReportingSuccess(){
        var s=suite();var run=start(s);String original=jdbc.queryForObject("SELECT body FROM knowledge_chunk WHERE id=? AND revision=1",String.class,expected.toString());
        try{jdbc.update("UPDATE knowledge_chunk SET body=? WHERE id=? AND revision=1",original+" invalid hash",expected.toString());assertTrue(worker.runOne());assertEquals("CONTENT_INTEGRITY_FAILURE",evaluations.run(owner,run.id()).errorCode());}
        finally{jdbc.update("UPDATE knowledge_chunk SET body=? WHERE id=? AND revision=1",original,expected.toString());}
        var corrupted=start(s);jdbc.update("UPDATE evaluation_suite_revision SET content_hash=? WHERE suite_id=? AND revision=1","0".repeat(64),s.id().toString());assertNull(evaluations.claimNext());assertEquals("FAILED",evaluations.run(owner,corrupted.id()).status());assertEquals("EVALUATION_INTEGRITY_FAILURE",evaluations.run(owner,corrupted.id()).errorCode());rejected(()->evaluations.get(owner,s.id()),409);
    }
    @Test void reportAndCaseBytesAreVerifiedOnPrivateReads(){
        var run=finish(start(suite()));String report=jdbc.queryForObject("SELECT report_json FROM evaluation_run WHERE id=?",String.class,run.id().toString());
        try{jdbc.update("UPDATE evaluation_run SET report_json=? WHERE id=?","{\"changed\":true}",run.id().toString());assertEquals("EVALUATION_INTEGRITY_FAILURE",rejected(()->evaluations.report(owner,run.id()),409).code());}
        finally{jdbc.update("UPDATE evaluation_run SET report_json=? WHERE id=?",json.write(json.tree(report)),run.id().toString());}
        int storedBytes=jdbc.queryForObject("SELECT result_bytes FROM evaluation_run WHERE id=?",Integer.class,run.id().toString());
        try{jdbc.update("UPDATE evaluation_run SET result_bytes=result_bytes+1 WHERE id=?",run.id().toString());assertEquals("EVALUATION_INTEGRITY_FAILURE",rejected(()->evaluations.run(owner,run.id()),409).code());}
        finally{jdbc.update("UPDATE evaluation_run SET result_bytes=? WHERE id=?",storedBytes,run.id().toString());}
        try{jdbc.update("UPDATE evaluation_case_result SET byte_size=byte_size+1 WHERE run_id=?",run.id().toString());assertEquals("EVALUATION_INTEGRITY_FAILURE",rejected(()->evaluations.run(owner,run.id()),409).code());}
        finally{jdbc.update("UPDATE evaluation_case_result SET byte_size=byte_size-1 WHERE run_id=?",run.id().toString());}
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)json.tree(json.write(run.results().get(0)));data.put("passed",false);jdbc.update("UPDATE evaluation_case_result SET result_json=? WHERE run_id=?",json.write(data),run.id().toString());assertEquals("EVALUATION_INTEGRITY_FAILURE",rejected(()->evaluations.run(owner,run.id()),409).code());
    }
    @Test void identicalFrozenInputsHaveStableSemanticHashAcrossActualRuns(){
        var s=suite();var first=finish(start(s));var second=finish(start(s));assertNotEquals(first.id(),second.id());assertEquals(first.suiteHash(),second.suiteHash());assertEquals(first.corpusHash(),second.corpusHash());assertEquals(first.resultHash(),second.resultHash());assertNotEquals(evaluations.report(owner,first.id()).contentHash(),evaluations.report(owner,second.id()).contentHash());assertEquals(first.results().get(0).hits(),second.results().get(0).hits());
    }
    @Test void ownerPendingAndDailyQuotasCountActualAdmissionsAndAllowReplay(){
        var s=suite();String firstKey=key();var first=evaluations.createRun(owner,s.id(),new RunInput(1,3),firstKey);var second=start(s);assertEquals("EVALUATION_LIMIT_EXCEEDED",rejected(()->start(s),429).code());assertEquals(first.id(),evaluations.createRun(owner,s.id(),new RunInput(1,3),firstKey).id());evaluations.cancel(owner,first.id());evaluations.cancel(owner,second.id());
        for(int i=0;i<18;i++){var run=start(s);evaluations.cancel(owner,run.id());}assertEquals("EVALUATION_LIMIT_EXCEEDED",rejected(()->start(s),429).code());assertEquals(20,jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_run WHERE owner_id=?",Integer.class,owner.toString()));
    }
    @Test void caseBoundsAmbiguityForeignMembershipAndConceptMismatchRejectAtomically(){
        var invalid=new ArrayList<SuiteInput>();invalid.add(new SuiteInput("empty","",List.of()));invalid.add(new SuiteInput("duplicate keys","",List.of(match(),match())));invalid.add(new SuiteInput("duplicate query","",List.of(match(),new CaseInput("other","  pecs  ",null,"NO_RESULTS",List.of(),""))));
        invalid.add(new SuiteInput("wrong labels","",List.of(new CaseInput("bad","PECS",null,"NO_RESULTS",List.of(expected),""))));invalid.add(new SuiteInput("missing labels","",List.of(new CaseInput("bad","PECS",null,"MATCH",List.of(),""))));invalid.add(new SuiteInput("unknown chunk","",List.of(new CaseInput("bad","PECS",null,"MATCH",List.of(UUID.randomUUID()),""))));
        UUID wrongConcept=curriculum.chunks(RELEASE,List.of()).stream().filter(c->!c.chunkId().equals(expected)&&!c.conceptId().equals(search.search(RELEASE,"PECS",null,1).items().get(0).conceptId())).findFirst().orElseThrow().conceptId();invalid.add(new SuiteInput("wrong filter","",List.of(new CaseInput("bad","PECS",wrongConcept,"MATCH",List.of(expected),""))));
        invalid.add(new SuiteInput("oversized query","",List.of(new CaseInput("bad","x".repeat(501),null,"MATCH",List.of(expected),""))));invalid.add(new SuiteInput("oversized count","",java.util.stream.IntStream.range(0,41).mapToObj(i->new CaseInput("case"+i,"query"+i,null,"NO_RESULTS",List.of(),"")).toList()));
        for(var input:invalid)rejected(()->evaluations.create(owner,workspace,input,key()),400);assertEquals(0,evaluations.suites(owner,workspace,30,null,true).items().size());
    }
    @Test void cursorPagesNeverDuplicateAndBadBoundsDoNotScanHistory(){
        var ids=new HashSet<UUID>();for(int i=0;i<3;i++)ids.add(evaluations.create(owner,workspace,new SuiteInput("Set "+i,"",List.of(match())),key()).id());var first=evaluations.suites(owner,workspace,2,null,false);assertEquals(2,first.items().size());assertNotNull(first.nextCursor());var second=evaluations.suites(owner,workspace,2,first.nextCursor(),false);assertEquals(1,second.items().size());assertNull(second.nextCursor());var seen=new HashSet<UUID>();first.items().forEach(s->seen.add(s.id()));second.items().forEach(s->seen.add(s.id()));assertEquals(ids,seen);rejected(()->evaluations.suites(owner,workspace,101,null,false),400);rejected(()->evaluations.suites(owner,workspace,30,"invalid",false),400);
    }
    @Test void databasePreventsFalseCompletedRunsAndCrossOwnerWorkspaceReferences(){
        var run=start(suite());rejectedConstraint(()->jdbc.update("UPDATE evaluation_run SET status='COMPLETED' WHERE id=?",run.id().toString()),3819,"HY000",23513);
        assertEquals("QUEUED",evaluations.run(owner,run.id()).status());
        UUID other=account(),foreignSuite=UUID.randomUUID();rejectedConstraint(()->jdbc.update("INSERT INTO evaluation_suite(id,owner_id,workspace_id,release_id,created_at,updated_at) VALUES(?,?,?,?,?,?)",foreignSuite.toString(),other.toString(),workspace.toString(),RELEASE.toString(),Timestamp.from(Instant.now()),Timestamp.from(Instant.now())),1452,"23000",23506);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_suite WHERE id=?",Integer.class,foreignSuite.toString()));
    }
    /** MySQL 3819 uses HY000 and Spring leaves it uncategorized; verify the actual constraint code, never an arbitrary SQL failure. */
    private void rejectedConstraint(Runnable action,int mysqlCode,String mysqlState,int h2Code) {
        DataAccessException failure=assertThrows(DataAccessException.class,action::run);
        SQLException cause=assertInstanceOf(SQLException.class,failure.getMostSpecificCause());
        assertTrue(cause.getErrorCode()==mysqlCode && mysqlState.equals(cause.getSQLState())
            || cause.getErrorCode()==h2Code && Integer.toString(h2Code).equals(cause.getSQLState()),
            ()->"Expected a concrete CHECK/FK rejection, received SQLState="+cause.getSQLState()+", code="+cause.getErrorCode());
    }
    @Test void completedRunCannotBeCancelledAndItsDownloadedBytesRemainStable(){
        var run=finish(start(suite()));assertEquals("COMPLETED",run.status());byte[] first=evaluations.report(owner,run.id()).bytes();assertTrue(first.length>1000);assertEquals(json.sha256(new String(first,StandardCharsets.UTF_8)),evaluations.report(owner,run.id()).contentHash());rejected(()->evaluations.cancel(owner,run.id()),409);assertArrayEquals(first,evaluations.report(owner,run.id()).bytes());assertTrue(json.tree(new String(first,StandardCharsets.UTF_8)).path("limitations").toString().contains("no independent human review"));
    }
    @Test void operationsRequireCurrentAdministratorAndRejectARevokedCachedAdminSession() throws Exception {
        call(get("/api/v1/admin/operations"),401);call(get("/api/v1/admin/operations").session(session()),403);
        jdbc.update("UPDATE app_user SET roles='[\"LEARNER\",\"EDITOR\"]' WHERE id=?",owner.toString());call(get("/api/v1/admin/operations").session(session()),403);
        var administrator=administrator();var report=operations(administrator);schemas.validate("OperationsStatus",report);assertEquals(11,report.path("queues").size());
        jdbc.update("UPDATE app_user SET roles='[\"LEARNER\",\"EDITOR\"]' WHERE id=?",owner.toString());
        var denied=call(get("/api/v1/admin/operations").session(administrator),403);assertEquals("ADMIN_REQUIRED",denied.path("code").asText());
    }
    @Test void operationsCountActualEvaluationStatesAndStoredUtf8PayloadWithoutDisclosingPrivateLabels() throws Exception {
        var admin=administrator();JsonNode before=operations(admin).path("queues");
        var suite=evaluations.create(owner,workspace,new SuiteInput("private-operations-suite","个人评测说明",List.of(match())),key());
        assertEquals("COMPLETED",finish(start(suite)).status());
        var failed=start(suite);var lease=evaluations.claimNext();assertEquals(failed.id(),lease.id());
        jdbc.update("UPDATE evaluation_run SET lease_until=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),failed.id().toString());assertNull(evaluations.claimNext());assertEquals("FAILED",evaluations.run(owner,failed.id()).status());
        var partial=start(suite);var ticket=evaluations.claimNext();assertTrue(evaluations.acceptCase(ticket,0,evaluations.evaluate(ticket,ticket.snapshot().cases().get(0))));assertEquals("CANCELLED",evaluations.cancel(owner,partial.id()).status());
        var running=start(suite);assertEquals(running.id(),evaluations.claimNext().id());start(suite);
        var report=operations(admin);schemas.validate("OperationsStatus",report);JsonNode queues=report.path("queues");
        assertEquals(before.path("pendingEvaluations").asLong()+2,queues.path("pendingEvaluations").asLong());assertEquals(before.path("failedEvaluations").asLong()+1,queues.path("failedEvaluations").asLong());
        long bytes=storedStrings("SELECT v.snapshot_json,v.corpus_json FROM evaluation_suite_revision v JOIN evaluation_suite s ON s.id=v.suite_id WHERE s.owner_id=?",2)
            +storedStrings("SELECT report_json FROM evaluation_run WHERE owner_id=? AND report_json IS NOT NULL",1)
            +storedStrings("SELECT c.result_json FROM evaluation_case_result c JOIN evaluation_run r ON r.id=c.run_id WHERE r.owner_id=?",1);
        assertTrue(bytes>0);assertEquals(before.path("storedEvaluationBytes").asLong()+bytes,queues.path("storedEvaluationBytes").asLong());
        assertFalse(report.toString().contains("private-operations-suite"));assertFalse(report.toString().contains("PECS"));assertFalse(report.toString().contains(owner.toString()));assertFalse(report.toString().contains(username));assertFalse(report.toString().contains(PASSWORD));
    }
    @Test void operationsSeparateOverduePreviewBacklogAndMeasureActualRetainedChineseText() throws Exception {
        var admin=administrator();JsonNode before=operations(admin).path("queues");
        var source=ingestion.register(owner,new IngestionModels.SourceInput("private-operations-source","https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html",key(),"运营统计隔离夹具，人工上传，不访问外部网站。"),key());
        var active=importPreview(source.id(),"保留的中文原文");var overdue=importPreview(source.id(),"等待过期清理的正文");var committed=importPreview(source.id(),"已提交内容不是预览容量");
        batches.commit(owner,committed.id(),new BatchImportModels.CommitInput(1,List.of(new BatchImportModels.Selection(committed.items().get(0).id(),"人工确认后的正文","私有来源"))),key());
        jdbc.update("UPDATE import_batch SET created_at=?,expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(90000)),Timestamp.from(Instant.now().minusSeconds(60)),overdue.id().toString());
        var report=operations(admin);schemas.validate("OperationsStatus",report);JsonNode queues=report.path("queues");
        assertEquals(before.path("activeImportPreviews").asLong()+1,queues.path("activeImportPreviews").asLong());assertEquals(before.path("expiredImportPreviews").asLong()+1,queues.path("expiredImportPreviews").asLong());
        long bytes=storedStrings("SELECT i.original_markdown,i.cleaned_markdown FROM import_batch_item i JOIN import_batch b ON b.id=i.batch_id WHERE b.owner_id=?",2);
        assertEquals(("保留的中文原文".getBytes(StandardCharsets.UTF_8).length+"等待过期清理的正文".getBytes(StandardCharsets.UTF_8).length)*2,bytes);assertEquals(before.path("importPreviewBytes").asLong()+bytes,queues.path("importPreviewBytes").asLong());
        assertFalse(report.toString().contains("保留的中文原文"));assertFalse(report.toString().contains("private-operations-source"));assertFalse(report.toString().contains("docs.oracle.com"));
        assertEquals("EXPIRED",batches.get(owner,overdue.id()).status());batches.cancel(owner,active.id(),new BatchImportModels.CancelInput(1),key());
        JsonNode cleaned=operations(admin).path("queues");assertEquals(before.path("expiredImportPreviews").asLong(),cleaned.path("expiredImportPreviews").asLong());assertEquals(before.path("activeImportPreviews").asLong(),cleaned.path("activeImportPreviews").asLong());assertEquals(before.path("importPreviewBytes").asLong(),cleaned.path("importPreviewBytes").asLong());
    }
    private MockHttpSession administrator() throws Exception {jdbc.update("UPDATE app_user SET roles='[\"LEARNER\",\"ADMIN\"]' WHERE id=?",owner.toString());return session();}
    private JsonNode operations(MockHttpSession session) throws Exception {return call(get("/api/v1/admin/operations").session(session),200);}
    private long storedStrings(String sql,int columns) {return jdbc.query(sql,(rs,n)->{long bytes=0;for(int i=1;i<=columns;i++)bytes+=rs.getString(i).getBytes(StandardCharsets.UTF_8).length;return bytes;},owner.toString()).stream().mapToLong(Long::longValue).sum();}
    private BatchImportModels.Batch importPreview(UUID source,String text) {return batches.preview(owner,source,"实际上传来源核对",List.of(new MockMultipartFile("files","private.txt","text/plain",text.getBytes(StandardCharsets.UTF_8))),key());}
}
