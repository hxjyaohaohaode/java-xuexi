package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.notebook.NotebookService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.orchestration.DurableAgentRuntime;
import io.typeforge.orchestration.TaskPlan;
import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.tutor.HttpTutorModelProvider;
import io.typeforge.tutor.StudyWorkflowService;
import io.typeforge.tutor.TutorDocumentService;
import io.typeforge.tutor.TutorService;
import io.typeforge.tutor.TutorSettings;
import io.typeforge.tutor.TutorWorker;
import io.typeforge.workspace.WorkspaceService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP + durable MODEL workflow integration, NOT external model or factual-quality evaluation. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:study-model-workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH",
    "typeforge.tutor.provider-url=","typeforge.tutor.api-key=","typeforge.tutor.model=",
    "typeforge.tutor.worker-enabled=false","typeforge.study.dispatch-enabled=false"
})
@ActiveProfiles({"test","demo"})
@Timeout(40)
class StudyModelWorkflowTest {
    private static final String TOKEN="study-model-loopback-protocol-fixture-secret";
    private static final long USED_TOKENS=28;
    private static final Set<String> STOPPED=Set.of("COMPLETED","PARTIAL","FAILED","CANCELLED","WAITING_USER","PAUSED");
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired WorkspaceService workspaces;
    @Autowired DurableAgentRuntime runtime;
    @Autowired ArtifactService artifacts;
    @Autowired NotebookService notebooks;
    @Autowired EventJournal events;
    @Autowired JsonCodec json;
    @Autowired SchemaCatalog schemas;
    @Autowired LocalKnowledgeSearch search;
    @Autowired Clock clock;
    private HttpServer server;
    private ExecutorService serverThreads;
    private TutorSettings settings;
    private TutorService tutors;
    private TutorWorker worker;
    private StudyWorkflowService studies;
    private UUID owner,workspace,goal,snapshot;
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicReference<ReplyFactory> reply=new AtomicReference<>();
    private final List<JsonNode> captured=new CopyOnWriteArrayList<>();
    private final List<String> credentials=new CopyOnWriteArrayList<>();
    private final List<Throwable> fixtureFailures=new CopyOnWriteArrayList<>();
    @FunctionalInterface private interface ReplyFactory {String response(JsonNode input) throws Exception;}
    private static String key(){return UUID.randomUUID().toString();}

    @BeforeEach void fixture() throws IOException {
        owner=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",owner.toString(),"study-model-"+owner,"not-a-login-credential");
        workspace=workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"MODEL protocol integration","COLLABORATIVE",key()).id();
        // The long question used to consume the review's whole draft excerpt before its answer block.
        String text="理解 Java 泛型 PECS extends super List 通配符；"+"请分别解释读取和写入的类型边界。".repeat(60);
        goal=workspaces.createGoal(owner,workspace,text,List.of("区分代码类型证据与运行时正确性"),key()).id();
        snapshot=workspaces.createContext(owner,workspace).id();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        serverThreads=Executors.newCachedThreadPool(task->{Thread thread=new Thread(task,"study-model-http-fixture");thread.setDaemon(true);return thread;});
        server.setExecutor(serverThreads);
        reply.set(this::validResponse);
        server.createContext("/chat/completions",exchange->{
            calls.incrementAndGet();credentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                JsonNode request=json.tree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                captured.add(request);byte[] bytes=reply.get().response(request).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(Throwable error) {fixtureFailures.add(error);}
            finally {exchange.close();}
        });
        server.start();
        settings=new TutorSettings("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions",TOKEN,"study-protocol-fixture",5000,2048,1_000_000,100,4,false);
        var provider=new HttpTutorModelProvider(settings,json);
        tutors=new TutorService(jdbc,tx,workspaces,events,json,clock,settings,provider);
        worker=new TutorWorker(tutors,search,provider,settings);
        var documents=new TutorDocumentService(tutors,artifacts,workspaces,events,jdbc,tx,json);
        studies=new StudyWorkflowService(jdbc,tx,workspaces,runtime,tutors,documents,settings,artifacts,notebooks,json,schemas,clock,false);
    }
    @AfterEach void closeFixture() {
        if(studies!=null)for(String value:jdbc.queryForList("SELECT id FROM agent_run WHERE workspace_id=?",String.class,workspace.toString())) {
            UUID id=UUID.fromString(value);var run=runtime.get(owner,workspace,id);
            if(!Set.of("COMPLETED","PARTIAL","FAILED","CANCELLED").contains(run.status()))runtime.command(owner,workspace,id,run.runEpoch(),DurableAgentRuntime.Command.CANCEL);
            studies.cancelAssociated(owner,id);
        }
        if(worker!=null)worker.close();
        if(server!=null)server.stop(0);
        if(serverThreads!=null)serverThreads.shutdownNow();
        assertTrue(fixtureFailures.isEmpty(),()->"Loopback fixture failed: "+fixtureFailures);
    }
    private StudyWorkflowService.Input input(long tokens){return new StudyWorkflowService.Input(goal,snapshot,List.of("DOCUMENT","SLIDES"),new TaskPlan.Budget(tokens,600000,1000000,3),TutorService.Mode.MODEL);}
    private DurableAgentRuntime.RunView start(){return studies.create(owner,workspace,input(100000),key());}
    private DurableAgentRuntime.RunView drain(UUID id) {
        for(int i=0;i<24;i++) {
            studies.advance(owner,workspace,id);
            while(worker.runOne()) { /* Each call executes the actual HTTP adapter and persists its receipt. */ }
            var run=runtime.get(owner,workspace,id);if(STOPPED.contains(run.status()))return run;
        }
        return runtime.get(owner,workspace,id);
    }
    private JsonNode user(JsonNode request){return json.tree(request.path("messages").get(1).path("content").asText());}
    private String question(JsonNode request){return user(request).path("question").asText();}
    private String plan(){return json.write(Map.of("strategy","repair-first","sections",List.of(
        Map.of("role","CONCEPT","title","读取边界","focus","Java 泛型 PECS extends List 的读取约束"),
        Map.of("role","REPAIR","title","写入边界","focus","Java 泛型 PECS super List 的写入约束"))));}
    private String validResponse(JsonNode request) {
        String q=question(request),answer;
        if(q.contains("制定教学计划"))answer=plan();
        else if(q.contains("独立于初稿"))answer="REVIEW_BODY：这些是协议夹具中的教学草稿，只核对给定引用，不代表人工事实核验。";
        else if(q.contains("教学分工：REPAIR"))answer="EXPERT_REPAIR_BODY：PECS super 的写入边界需要保留类型关系；编译结果不保证运行时行为。";
        else answer="EXPERT_CONCEPT_BODY：PECS extends 的读取边界必须由已发布的 Java 泛型知识来源支持。";
        return wire(request,answer,true);
    }
    private String wire(JsonNode request,String answer,boolean usage) {
        String citation=user(request).path("publishedEvidence").get(0).path("chunkId").asText();
        String content=json.write(Map.of("answer",answer,"citedChunkIds",List.of(citation),"limitations","Deterministic loopback HTTP fixture; not real-model quality evidence."));
        var body=new LinkedHashMap<String,Object>();
        body.put("model","study-fixture-model-revision");body.put("choices",List.of(Map.of("finish_reason","stop","message",Map.of("role","assistant","content",content))));
        if(usage)body.put("usage",Map.of("prompt_tokens",17,"completion_tokens",11,"total_tokens",USED_TOKENS));
        return json.write(body);
    }
    private void assertLedger(long used,long reserved,UUID runId) {
        var run=runtime.get(owner,workspace,runId);assertEquals(used,run.spentTokens());assertEquals(reserved,run.reservedTokens());
        var daily=jdbc.queryForMap("SELECT used_tokens,reserved_tokens FROM tutor_daily_budget WHERE user_id=?",owner.toString());
        assertEquals(used,((Number)daily.get("used_tokens")).longValue());assertEquals(reserved,((Number)daily.get("reserved_tokens")).longValue());
    }

    @Test void realModelProtocolTraversesPlannerSpecialistsReviewAndEditableOutputs() {
        var initial=start();var run=drain(initial.id());assertEquals("COMPLETED",run.status(),json.write(run));
        assertEquals(2,run.planRevision());assertEquals(1,run.plan().budget().maxParallel());
        assertEquals(4,calls.get());assertEquals(4,studies.details(owner,run.id()).tutorRequestIds().size());
        assertEquals(6,studies.details(owner,run.id()).artifactIds().size());assertLedger(4*USED_TOKENS,0,run.id());
        schemas.validate("AgentRun",run);schemas.validate("StudyWorkflowDetails",studies.details(owner,run.id()));
        String review=captured.stream().map(this::question).filter(q->q.contains("独立于初稿")).findFirst().orElseThrow();
        assertTrue(review.contains("EXPERT_CONCEPT_BODY"));assertTrue(review.contains("EXPERT_REPAIR_BODY"));
        assertFalse(review.contains("导师请求 "),"Review must receive expert prose, not spend its excerpt budget on metadata");
        for(UUID id:studies.details(owner,run.id()).artifactIds()) {
            var artifact=artifacts.get(owner,id);assertEquals(1,artifact.currentRevision());
            var version=artifacts.validateCurrentInputs(owner,id,1);assertEquals("DRAFT",version.reviewStatus());assertFalse(version.content().toString().contains(TOKEN));
        }
        var document=run.steps().stream().filter(step->step.key().equals("output-document")).findFirst().orElseThrow().outputRefs().get(0);
        String content=artifacts.version(owner,document.id(),document.revision()).content().toString();
        assertTrue(content.contains("EXPERT_CONCEPT_BODY"));assertTrue(content.contains("EXPERT_REPAIR_BODY"));assertTrue(content.contains("REVIEW_BODY"));
        assertTrue(credentials.stream().allMatch(value->value.equals("Bearer "+TOKEN)));
        assertTrue(captured.stream().noneMatch(request->request.toString().contains(TOKEN) || request.has("tools")));
    }
    @ParameterizedTest @ValueSource(strings={"not-json","unadmitted-role","extra-property"})
    void invalidTeachingPlanSettlesKnownProviderUsageWithoutCreatingExperts(String invalid) {
        String answer=switch(invalid) {
            case "not-json"->"This is prose, not a teaching-plan JSON object.";
            case "unadmitted-role"->"{\"strategy\":\"concept-first\",\"sections\":[{\"role\":\"SHELL_EXECUTOR\",\"title\":\"bad\",\"focus\":\"unadmitted\"}]}";
            default->"{\"strategy\":\"concept-first\",\"sections\":[{\"role\":\"CONCEPT\",\"title\":\"bad\",\"focus\":\"PECS\"}],\"tool\":\"arbitrary-command\"}";
        };
        reply.set(request->wire(request,answer,true));var initial=start();var run=drain(initial.id());
        assertEquals("FAILED",run.status(),json.write(run));assertEquals(1,calls.get());assertEquals(1,run.planRevision());
        assertTrue(artifacts.list(owner,workspace).isEmpty());assertLedger(USED_TOKENS,0,run.id());
        assertTrue(runtime.events(owner,workspace,run.id(),0,100).stream().anyMatch(event->json.write(event).contains("STUDY_PLAN_FORMAT_INVALID")));
    }
    @Test void unknownModelUsageCommitsItsHoldAndNeverAutomaticallyRetries() {
        reply.set(request->wire(request,plan(),false));var initial=start();var run=drain(initial.id());
        assertEquals("WAITING_USER",run.status(),json.write(run));assertEquals(1,calls.get());assertLedger(0,settings.reservationTokens(),run.id());
        UUID request=studies.details(owner,run.id()).tutorRequestIds().get(0);assertEquals("UNKNOWN",tutors.get(owner,request).usage().billingState());
        assertEquals("RECONCILIATION_REQUIRED",assertThrows(DomainException.class,()->runtime.command(owner,workspace,run.id(),run.runEpoch(),DurableAgentRuntime.Command.RESUME)).code());
        for(int i=0;i<3;i++)studies.advance(owner,workspace,run.id());
        assertEquals(1,calls.get());assertLedger(0,settings.reservationTokens(),run.id());assertTrue(artifacts.list(owner,workspace).isEmpty());
    }
    @Test void insufficientRemainingBudgetStopsBeforeTheNextModelDispatch() {
        var initial=studies.create(owner,workspace,input(settings.reservationTokens()+10),key());var run=drain(initial.id());
        assertEquals("PARTIAL",run.status(),json.write(run));assertEquals(1,calls.get());assertLedger(USED_TOKENS,0,run.id());
        assertTrue(run.steps().stream().filter(step->step.key().startsWith("output-")).allMatch(step->step.outputRefs().isEmpty()));
        assertTrue(runtime.events(owner,workspace,run.id(),0,100).stream().anyMatch(event->json.write(event).contains("BUDGET_EXHAUSTED")));
    }
    @Test void pauseDuringActualHttpFencesLateContentButSettlesUsageBeforeResuming() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        reply.set(request->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Fixture was not released");return validResponse(request);});
        var executor=Executors.newSingleThreadExecutor();
        try {
            var initial=start();studies.advance(owner,workspace,initial.id());var response=executor.submit(worker::runOne);
            assertTrue(entered.await(5,TimeUnit.SECONDS));var run=runtime.get(owner,workspace,initial.id());
            UUID original=studies.details(owner,run.id()).tutorRequestIds().get(0);
            var paused=runtime.command(owner,workspace,run.id(),run.runEpoch(),DurableAgentRuntime.Command.PAUSE);studies.cancelAssociated(owner,run.id());
            assertEquals("CANCELLED",tutors.get(owner,original).status());assertLedger(0,settings.reservationTokens(),run.id());
            release.countDown();assertTrue(response.get(5,TimeUnit.SECONDS));studies.cancelAssociated(owner,run.id());
            assertNull(tutors.get(owner,original).answer());assertTrue(artifacts.list(owner,workspace).isEmpty());assertLedger(USED_TOKENS,0,run.id());
            reply.set(this::validResponse);runtime.command(owner,workspace,run.id(),paused.runEpoch(),DurableAgentRuntime.Command.RESUME);
            var completed=drain(run.id());assertEquals("COMPLETED",completed.status(),json.write(completed));assertEquals(5,calls.get());
            assertEquals("CANCELLED",tutors.get(owner,original).status());assertLedger(5*USED_TOKENS,0,run.id());
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void editedPlannerAtDurableCheckpointCannotBeSilentlyReplannedOrOverwritten() {
        var initial=start();studies.advance(owner,workspace,initial.id());assertTrue(worker.runOne());
        // Stop exactly between the two committed phases of advance: reconcile and applyPlan.
        // This makes the real learner-edit race deterministic without substituting provider or ledger outcomes.
        ReflectionTestUtils.invokeMethod(studies,"reconcile",owner,workspace,runtime.get(owner,workspace,initial.id()));
        var planned=runtime.get(owner,workspace,initial.id());assertEquals("SUCCEEDED",planned.steps().stream().filter(step->step.key().equals("planner")).findFirst().orElseThrow().status());
        UUID planner=studies.details(owner,planned.id()).artifactIds().get(0);var version=artifacts.version(owner,planner,1);
        JsonNode changed=version.content().deepCopy();for(JsonNode block:changed.path("blocks"))if(block.path("id").asText().equals("answer"))((ObjectNode)block).put("text","USER_PLANNER_EDIT_PRESERVED：学习者重新思考了教学计划。");
        var manual=artifacts.save(owner,planner,1,changed,version.inputRefs());
        studies.advance(owner,workspace,planned.id());var stopped=runtime.get(owner,workspace,planned.id());
        assertTrue(Set.of("WAITING_USER","PAUSED").contains(stopped.status()),json.write(stopped));assertEquals(1,stopped.planRevision());
        for(int i=0;i<2;i++)studies.advance(owner,workspace,planned.id());
        assertEquals(1,calls.get());assertEquals(1,studies.details(owner,planned.id()).tutorRequestIds().size());
        assertEquals(2,artifacts.get(owner,planner).currentRevision());assertEquals(manual.contentHash(),artifacts.version(owner,planner,2).contentHash());
        assertTrue(artifacts.version(owner,planner,2).content().toString().contains("USER_PLANNER_EDIT_PRESERVED"));assertLedger(USED_TOKENS,0,planned.id());
    }
}
