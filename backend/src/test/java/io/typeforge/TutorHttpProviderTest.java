package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.typeforge.exercise.LearningService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.tutor.HttpTutorModelProvider;
import io.typeforge.tutor.TutorService;
import io.typeforge.tutor.TutorSettings;
import io.typeforge.tutor.TutorWorker;
import io.typeforge.workspace.WorkspaceService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** A real loopback HTTP protocol fixture, explicitly NOT a real-provider/model-quality evaluation. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:typeforge-tutor-http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH","typeforge.tutor.worker-enabled=false","typeforge.tutor.provider-url=","typeforge.tutor.api-key=","typeforge.tutor.model="})
@ActiveProfiles({"test","demo"})
@Timeout(30)
class TutorHttpProviderTest {
    private static final String KEY="explicit-loopback-protocol-fixture-key";
    @Autowired io.typeforge.curriculum.CourseCompanions course;
    @Autowired io.typeforge.shared.SchemaCatalog schemas;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired WorkspaceService workspaces;
    @Autowired EventJournal events;
    @Autowired JsonCodec json;
    @Autowired Clock clock;
    @Autowired LocalKnowledgeSearch search;
    @Autowired LearningService learning;
    @Autowired TutorService defaultService;
    private HttpServer server;
    private ExecutorService serverExecutor;
    private UUID owner;
    private UUID workspace;
    private UUID conversation;
    private WorkspaceService.Context snapshot;
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicInteger redirected=new AtomicInteger();
    private final AtomicReference<JsonNode> captured=new AtomicReference<>();
    private final AtomicReference<String> authorization=new AtomicReference<>();
    private final AtomicReference<Behavior> behavior=new AtomicReference<>();
    @FunctionalInterface private interface Behavior { Reply answer(JsonNode request) throws Exception; }
    private record Reply(int status,String body,Map<String,String> headers) { Reply(String body) { this(200,body,Map.of()); } }
    private record Harness(TutorSettings settings,HttpTutorModelProvider provider,TutorService service,TutorWorker worker) implements AutoCloseable {
        @Override public void close() { worker.close(); }
    }
    private static String key() { return UUID.randomUUID().toString(); }

    @BeforeEach void fixture() throws IOException {
        owner=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",owner.toString(),"tutor-http-"+owner,"fixture-not-a-login-credential");
        workspace=workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"HTTP tutor fixture","ON_DEMAND",key()).id();
        workspaces.createGoal(owner,workspace,"Understand PECS",List.of("Check the selected sources"),key());
        snapshot=workspaces.createContext(owner,workspace);
        conversation=defaultService.createConversation(owner,new TutorService.ConversationInput(workspace,DemoContentInitializer.RELEASE_ID,"Protocol fixture"),key()).id();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        serverExecutor=Executors.newCachedThreadPool(task->{Thread thread=new Thread(task,"tutor-http-fixture");thread.setDaemon(true);return thread;});server.setExecutor(serverExecutor);
        behavior.set(request->new Reply(validResponse(request)));
        server.createContext("/chat/completions",exchange->{
            calls.incrementAndGet();authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                JsonNode request=json.tree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));captured.set(request);
                Reply reply=behavior.get().answer(request);byte[] bytes=reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");reply.headers().forEach((name,value)->exchange.getResponseHeaders().set(name,value));
                exchange.sendResponseHeaders(reply.status(),bytes.length);exchange.getResponseBody().write(bytes);
            } catch(Exception ignored) { /* Client timeout/cancellation is expected in failure fixtures. */ }
            finally { exchange.close(); }
        });
        server.createContext("/credential-target",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        server.start();
    }
    @AfterEach void closeFixture() {
        if(server!=null) server.stop(0);
        if(serverExecutor!=null) serverExecutor.shutdownNow();
        var rows=jdbc.queryForList("SELECT r.id,x.request_epoch FROM tutor_request r JOIN tutor_request_runtime x ON x.request_id=r.id JOIN conversation c ON c.id=r.conversation_id WHERE c.user_id=? AND r.status IN ('QUEUED','RETRIEVING','GENERATING','VALIDATING')",owner.toString());
        for(var row:rows) defaultService.cancel(owner,UUID.fromString(row.get("id").toString()),((Number)row.get("request_epoch")).longValue());
    }
    private Harness harness(int timeout,long dailyTokens) {
        var settings=new TutorSettings("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions",KEY,"fixture-model",timeout,256,dailyTokens,100,4,false);
        var provider=new HttpTutorModelProvider(settings,json,course,schemas);
        var service=new TutorService(jdbc,tx,workspaces,events,json,clock,settings,provider);
        return new Harness(settings,provider,service,new TutorWorker(service,search,provider,settings));
    }
    private TutorService.TutorInput input(UUID attempt) { return new TutorService.TutorInput(conversation,snapshot.id(),TutorService.Mode.MODEL,"Explain PECS 通配符",2,attempt); }
    private TutorService.TutorResult submit(Harness harness) { return harness.service().submit(owner,input(null),key()); }
    private JsonNode userContent(JsonNode request) { return json.tree(request.path("messages").get(1).path("content").asText()); }
    private String validResponse(JsonNode request) {
        String id=userContent(request).path("publishedEvidence").get(0).path("chunkId").asText();
        return wire(json.write(Map.of("answer","这是 HTTP 协议夹具的测试文本，不是真实模型能力评测。","citedChunkIds",List.of(id),"limitations","Protocol fixture only")),"stop",true);
    }
    private String wire(String answer,String finish,boolean usage) {
        var value=new java.util.LinkedHashMap<String,Object>();
        value.put("model","fixture-model-revision");value.put("choices",List.of(Map.of("finish_reason",finish,"message",Map.of("role","assistant","content",answer))));
        if(usage) value.put("usage",Map.of("prompt_tokens",17,"completion_tokens",11,"total_tokens",28));
        return json.write(value);
    }
    @ParameterizedTest @ValueSource(strings={"M01","M02","M03","M04","M05"})
    void fixedCourseCriteriaSurviveEvenAnIncorrectModelReply(String reviewId) {
        var byId=Map.of("M01","fb93217f-2134-592a-8e1a-29647da4b015",
            "M02","c8d80a01-b752-556d-955c-ea5bd996c886","M03","508edac0-1d84-5f4a-a554-6919bc0d47b2",
            "M04","49cfe0e3-6a1e-589e-b16f-ab49dee0ef30","M05","6f59ac25-274e-5090-9448-93713ac68fd2");
        UUID concept=UUID.fromString(byId.get(reviewId));
        var supplement=course.forConcept(concept);
        var reviewed=supplement.reviews().stream().filter(rule->rule.id().equals(reviewId)).findFirst().orElseThrow();
        String modelText=switch(reviewId) {
            case "M01"->"错误的协议夹具输出：List<Integer> 赋给 List<Number> 后会在写入时抛出 ArrayStoreException。";
            case "M04"->"错误的协议夹具输出：Number 变量可以直接传给 List<? super Integer>.add。";
            default->"这条夹具回答尚未验证。";
        };
        behavior.set(request->{
            var cards=userContent(request).path("publishedEvidence");
            String citation=cards.get(cards.size()-1).path("chunkId").asText();
            return new Reply(wire(json.write(Map.of("answer",modelText,"citedChunkIds",List.of(citation),
                "limitations","x".repeat(1500))),"stop",true));
        });
        try(var harness=harness(5000,1_000_000)) {
            var queued=harness.service().submit(owner,new TutorService.TutorInput(conversation,snapshot.id(),TutorService.Mode.MODEL,
                reviewed.question(),2,null),key());
            var ticket=harness.service().claimNext();assertEquals(queued.id(),ticket.id());
            var admitted=new java.util.ArrayList<>(search.search(ticket.releaseId(),reviewed.question(),concept,1).items());
            assertEquals(1,admitted.size());
            var anchor=admitted.get(0);
            // The model can skip the pertinent card when listing its own citations.
            var other=search.search(ticket.releaseId(),"Java 泛型 通配符 List Number PECS",null,4).items().stream()
                .filter(card->!concept.equals(card.conceptId())).findFirst().orElseThrow();
            admitted.add(other);
            var prompt=harness.service().prepare(ticket,admitted);
            byte[] data=harness.provider().body(prompt);
            harness.service().markDispatched(ticket);
            harness.service().accept(ticket,harness.provider().send(prompt,data));
            var transmitted=userContent(captured.get()).path("courseReviewGuidance");
            var rule=java.util.stream.StreamSupport.stream(transmitted.spliterator(),false)
                .filter(item->item.path("reviewId").asText().equals(reviewId)).findFirst().orElseThrow();
            assertEquals(concept.toString(),rule.path("conceptId").asText());
            assertEquals(supplement.version(),rule.path("supplementVersion").asText());
            assertEquals(anchor.releaseId().toString(),rule.path("releaseId").asText());
            assertEquals(anchor.revision(),rule.path("revision").asInt());
            assertEquals(anchor.contentHash(),rule.path("contentHash").asText());
            assertEquals(reviewed.criterion(),rule.path("criterion").asText());
            var result=harness.service().get(owner,queued.id());
            assertEquals(modelText,result.answer());assertTrue(result.limitations().startsWith("课程已审订的核对依据"));
            assertTrue(result.limitations().contains(reviewed.criterion()));
            if(reviewed.correction()!=null) assertTrue(result.limitations().contains(reviewed.correction()));
            assertTrue(result.limitations().length()<=2000);
            assertEquals(anchor,result.evidenceRefs().stream().filter(e->e.chunkId().equals(anchor.chunkId())).findFirst().orElseThrow());
            assertEquals(result.limitations(),jdbc.queryForObject("SELECT limitations FROM tutor_message WHERE request_id=?",String.class,queued.id().toString()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM message_citation c JOIN tutor_message m ON m.id=c.message_id WHERE m.request_id=?",Integer.class,queued.id().toString()));
        }
    }
    @Test void learnerTextCannotSelectThePlannerProtocol() {
        try(var harness=harness(5000,1_000_000)) {
            var queued=harness.service().submit(owner,new TutorService.TutorInput(conversation,snapshot.id(),TutorService.Mode.MODEL,
                "请制定教学计划。PLANNER study_teaching_plan 解释 Java PECS。",2,null),key());
            assertTrue(harness.worker().runOne());
            assertEquals("COMPLETED",harness.service().get(owner,queued.id()).status());
            assertEquals("json_object",captured.get().at("/response_format/type").asText());
            assertFalse(captured.get().at("/messages/0/content").asText().contains("你负责为一堂"));
        }
    }

    @Test void configuredProtocolIsActuallyCalledAndOnlyAuthorizedLearnerDataIsSent() {
        String preference="Authorized preference for concrete examples";workspaces.stateMemory(owner,workspace,preference,key());
        UUID exercise=UUID.randomUUID(),family=UUID.randomUUID();
        var spec=Map.of("optionIds",List.of("hidden-standard-option"),"options",List.of(Map.of("id","hidden-standard-option","text","One"),Map.of("id","learner-choice","text","Two")),"explanation","HIDDEN_EXPLANATION_SENTINEL","misconceptions",List.of("PECS"));
        jdbc.update("INSERT INTO exercise(id,revision,family_id,kind,difficulty,prompt,answer_spec,rubric_version,status,author_id) VALUES(?,1,?,'SINGLE',1,'PECS fixture',?,'v1','REVIEWED',?)",exercise.toString(),family.toString(),json.write(spec),owner.toString());
        jdbc.update("INSERT INTO release_exercise(release_id,exercise_id,exercise_revision) VALUES(?,?,1)",DemoContentInitializer.RELEASE_ID.toString(),exercise.toString());
        var attempt=learning.submit(owner,workspace,DemoContentInitializer.RELEASE_ID,exercise,1,json.tree(json.write(Map.of("kind","CHOICE","optionIds",List.of("learner-choice")))),key());
        snapshot=workspaces.createContext(owner,workspace);
        try(var harness=harness(5000,1_000_000)) {
            assertTrue(harness.provider().readiness().configured());assertEquals(java.util.Set.of("TUTOR_MODEL"),harness.provider().enabledCapabilities());
            var queued=harness.service().submit(owner,input(attempt.id()),key());assertTrue(harness.worker().runOne());
            var done=harness.service().get(owner,queued.id());assertEquals("COMPLETED",done.status());assertEquals(1,calls.get());
            assertEquals("SETTLED",done.usage().billingState());assertEquals(28,done.usage().usedTokens());assertEquals(0,done.usage().reservedTokens());
            assertEquals("Bearer "+KEY,authorization.get());
            String sent=captured.get().toString();assertTrue(sent.contains(preference));assertTrue(sent.contains("learner-choice"));
            assertFalse(sent.contains("hidden-standard-option"));assertFalse(sent.contains("HIDDEN_EXPLANATION_SENTINEL"));assertFalse(sent.contains("answer_spec"));assertFalse(sent.contains(KEY));
            assertEquals(256,captured.get().path("max_tokens").asInt());assertFalse(captured.get().path("stream").asBoolean());assertFalse(captured.get().has("tools"));
            assertEquals("fixture-model-revision",jdbc.queryForObject("SELECT model_id FROM tutor_request WHERE id=?",String.class,queued.id().toString()));
            assertEquals(28,jdbc.queryForObject("SELECT used_tokens FROM tutor_daily_budget WHERE user_id=?",Long.class,owner.toString()));
        }
    }
    @Test void cancellationDoesNotWaitForHttpAndLateOutputOnlySettlesUsage() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        behavior.set(request->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return new Reply(validResponse(request));});
        var executor=Executors.newSingleThreadExecutor();
        try(var harness=harness(10000,1_000_000)) {
            var queued=submit(harness);var pending=executor.submit(harness.worker()::runOne);assertTrue(entered.await(5,TimeUnit.SECONDS));
            var active=harness.service().get(owner,queued.id());assertEquals("GENERATING",active.status());
            var cancelled=harness.service().cancel(owner,queued.id(),active.epoch());assertEquals("CANCELLED",cancelled.status());assertEquals("UNKNOWN",cancelled.usage().billingState());
            release.countDown();assertTrue(pending.get(5,TimeUnit.SECONDS));
            var finalState=harness.service().get(owner,queued.id());assertEquals("CANCELLED",finalState.status());assertNull(finalState.answer());assertEquals("SETTLED",finalState.usage().billingState());assertEquals(28,finalState.usage().usedTokens());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tutor_message WHERE request_id=?",Integer.class,queued.id().toString()));assertEquals(1,calls.get());
        } finally { release.countDown();executor.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void sourceRevocationDuringHttpWinsOverAValidProviderResponse(boolean expireDeadline) throws Exception {
        var memory=workspaces.stateMemory(owner,workspace,"Revoke while model is running",key());snapshot=workspaces.createContext(owner,workspace);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        behavior.set(request->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return new Reply(validResponse(request));});
        var executor=Executors.newSingleThreadExecutor();
        try(var harness=harness(10000,1_000_000)) {
            var queued=submit(harness);var pending=executor.submit(harness.worker()::runOne);assertTrue(entered.await(5,TimeUnit.SECONDS));
            workspaces.revokeMemory(owner,workspace,memory.id(),memory.revision());
            if(expireDeadline) jdbc.update("UPDATE tutor_request_runtime SET deadline_at=? WHERE request_id=?",Timestamp.from(Instant.now().minusSeconds(1)),queued.id().toString());
            release.countDown();pending.get(5,TimeUnit.SECONDS);
            var result=harness.service().get(owner,queued.id());assertEquals("FAILED",result.status());assertEquals(expireDeadline?"TUTOR_DEADLINE_EXCEEDED":"TUTOR_CONTEXT_CHANGED",result.errorCode());assertEquals("STALE",result.sourceState());assertNull(result.answer());assertEquals("SETTLED",result.usage().billingState());
        } finally { release.countDown();executor.shutdownNow(); }
    }
    @Test void timeoutKeepsUnknownReservationAndDoesNotBlindlyReplay() {
        behavior.set(request->{Thread.sleep(600);return new Reply(validResponse(request));});
        try(var harness=harness(200,1_000_000)) {
            var queued=submit(harness);harness.worker().runOne();var result=harness.service().get(owner,queued.id());
            assertEquals("FAILED",result.status());assertTrue(List.of("TUTOR_PROVIDER_TIMEOUT","TUTOR_PROVIDER_TRANSPORT").contains(result.errorCode()));assertEquals("UNKNOWN",result.usage().billingState());assertTrue(result.usage().reservedTokens()>0);assertNull(result.usage().usedTokens());
            assertFalse(harness.worker().runOne());assertEquals(1,calls.get());
        }
    }
    @Test void dispatchedCrashIsUnknownButLossOfConfigurationBeforeDispatchReleasesQuota() {
        try(var configured=harness(5000,1_000_000)) {
            var dispatched=submit(configured);var ticket=configured.service().claimNext();assertNotNull(ticket);
            configured.service().prepare(ticket,search.search(DemoContentInitializer.RELEASE_ID,"PECS",null,2).items());configured.service().markDispatched(ticket);
            jdbc.update("UPDATE tutor_request_runtime SET lease_until=? WHERE request_id=?",Timestamp.from(Instant.now().minusSeconds(1)),dispatched.id().toString());
            var queued=submit(configured);
            var missing=new TutorSettings("","","",5000,256,1_000_000,100,4,false);var unavailable=new HttpTutorModelProvider(missing,json,course,schemas);
            var recovered=new TutorService(jdbc,tx,workspaces,events,json,clock,missing,unavailable);
            assertNull(recovered.claimNext());
            assertEquals("TUTOR_PROVIDER_RESULT_UNKNOWN",recovered.get(owner,dispatched.id()).errorCode());assertEquals("UNKNOWN",recovered.get(owner,dispatched.id()).usage().billingState());
            assertEquals("TUTOR_MODEL_UNAVAILABLE",recovered.get(owner,queued.id()).errorCode());assertEquals("RELEASED",recovered.get(owner,queued.id()).usage().billingState());assertEquals(0,calls.get());
        }
    }
    @Test void tokenReservationIsAtomicAndQueuedCancellationReleasesOnlyItsOwnAmount() {
        try(var harness=harness(5000,65_536+1024+256)) {
            var first=submit(harness);assertTrue(first.usage().reservedTokens()>0);
            assertEquals("TUTOR_QUOTA_EXCEEDED",assertThrows(DomainException.class,()->submit(harness)).code());
            harness.service().cancel(owner,first.id(),first.epoch());assertEquals(0,harness.service().get(owner,first.id()).usage().reservedTokens());
            var next=submit(harness);assertEquals(first.usage().reservedTokens(),next.usage().reservedTokens());
            assertEquals(next.usage().reservedTokens(),jdbc.queryForObject("SELECT reserved_tokens FROM tutor_daily_budget WHERE user_id=?",Long.class,owner.toString()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"foreign-citation","duplicate-citation","extra-field","duplicate-json-key","truncated","missing-usage","credential-echo"})
    void invalidOutputsCannotCreateAnAnswerAndKnownUsageStillSettles(String invalid) {
        behavior.set(request->{
            String id=userContent(request).path("publishedEvidence").get(0).path("chunkId").asText();
            String content=json.write(Map.of("answer","Protocol output","citedChunkIds",List.of(id),"limitations","fixture"));
            switch(invalid) {
                case "foreign-citation" -> content=json.write(Map.of("answer","Unadmitted citation","citedChunkIds",List.of(UUID.randomUUID()),"limitations","fixture"));
                case "duplicate-citation" -> content=json.write(Map.of("answer","Duplicate citation","citedChunkIds",List.of(id,id),"limitations","fixture"));
                case "extra-field" -> content=json.write(Map.of("answer","Extra output","citedChunkIds",List.of(id),"limitations","fixture","toolCall","not-allowed"));
                case "duplicate-json-key" -> content="{\"answer\":\"first\",\"answer\":\"second\",\"citedChunkIds\":[\""+id+"\"],\"limitations\":\"fixture\"}";
                case "credential-echo" -> content=json.write(Map.of("answer",KEY,"citedChunkIds",List.of(id),"limitations","fixture"));
                default -> { }
            }
            return new Reply(wire(content,invalid.equals("truncated")?"length":"stop",!invalid.equals("missing-usage")));
        });
        try(var harness=harness(5000,1_000_000)) {
            var queued=submit(harness);harness.worker().runOne();var result=harness.service().get(owner,queued.id());
            assertEquals("FAILED",result.status());assertNull(result.answer());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tutor_message WHERE request_id=?",Integer.class,queued.id().toString()));
            assertEquals(invalid.equals("missing-usage")?"UNKNOWN":"SETTLED",result.usage().billingState());
            assertFalse(json.write(result).contains(KEY));assertFalse(jdbc.queryForList("SELECT payload FROM workspace_event WHERE workspace_id=?",String.class,workspace.toString()).stream().anyMatch(payload->payload.contains(KEY)));
        }
    }
    @Test void redirectsNeverForwardTheCredentialAndOversizedResponsesAreBounded() {
        behavior.set(request->new Reply(302,"{}",Map.of("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/credential-target")));
        try(var harness=harness(5000,1_000_000)) {
            var redirect=submit(harness);harness.worker().runOne();assertEquals("TUTOR_PROVIDER_HTTP_ERROR",harness.service().get(owner,redirect.id()).errorCode());assertEquals(0,redirected.get());
            behavior.set(request->new Reply(" ".repeat(200_000)));
            var oversized=submit(harness);harness.worker().runOne();var result=harness.service().get(owner,oversized.id());
            assertEquals("FAILED",result.status());assertNull(result.answer());assertEquals("UNKNOWN",result.usage().billingState());assertEquals(2,calls.get());
        }
    }
    @Test void providerConfigurationRejectsRemotePlaintextAndCredentialBearingUrls() {
        assertThrows(IllegalArgumentException.class,()->new TutorSettings("http://example.com/chat/completions",KEY,"model",1000,256,100000,10,2,false));
        assertThrows(IllegalArgumentException.class,()->new TutorSettings("https://user:secret@example.com/chat/completions",KEY,"model",1000,256,100000,10,2,false));
        assertThrows(IllegalArgumentException.class,()->new TutorSettings("https://example.com/chat/completions?api_key=secret",KEY,"model",1000,256,100000,10,2,false));
    }
    @Test void disabledScopeClosesDispatchedWorkAndKeepsItsUnknownReservation() {
        try(var harness=harness(5000,1_000_000)) {
            var request=submit(harness);var ticket=harness.service().claimNext();assertNotNull(ticket);
            harness.service().prepare(ticket,search.search(DemoContentInitializer.RELEASE_ID,"PECS",null,1).items());harness.service().markDispatched(ticket);
            jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?",owner.toString());
            try {
                assertNull(harness.service().claimNext());
                assertEquals("TUTOR_SCOPE_UNAVAILABLE",jdbc.queryForObject("SELECT error_code FROM tutor_request WHERE id=?",String.class,request.id().toString()));
                assertEquals("UNKNOWN",jdbc.queryForObject("SELECT billing_state FROM tutor_request_runtime WHERE request_id=?",String.class,request.id().toString()));
                assertEquals(request.usage().reservedTokens(),jdbc.queryForObject("SELECT reserved_tokens FROM tutor_request_runtime WHERE request_id=?",Long.class,request.id().toString()));assertEquals(0,calls.get());
            } finally { jdbc.update("UPDATE app_user SET status='ACTIVE' WHERE id=?",owner.toString()); }
        }
    }
}
