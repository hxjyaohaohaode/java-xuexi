package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.operations.events.OutboxDispatcher;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.tutor.EvidenceWorkflow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual HTTP, CSRF, identity, SQL, projections and worker receipts in one learning journey. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:typeforge-http-loop;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH")
@AutoConfigureMockMvc
@ActiveProfiles({"test","demo"})
class FullLearningLoopIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired SchemaCatalog schemas;
    @Autowired OutboxDispatcher outbox;
    @Autowired EvidenceWorkflow workflow;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    private static final String RELEASE=DemoContentInitializer.RELEASE_ID.toString();
    private static final String PASSWORD="fixture-password-strong-123";
    private static String key() { return UUID.randomUUID().toString(); }

    private final class Client {
        private MockHttpSession session;
        private String csrf;
        private final String username="http-"+key();
        private UUID owner;
        Client() throws Exception {
            renewCsrf();
            JsonNode account=call("POST","/auth/register",Map.of("username",username,"password",PASSWORD),201,"Session",null);
            owner=UUID.fromString(account.path("userId").asText());
            String oldId=session.getId();
            call("POST","/auth/session",Map.of("username",username,"password",PASSWORD),200,"Session",null);
            assertNotEquals(oldId,session.getId(),"login rotates the session identifier");
            String oldCsrf=csrf;
            var staleToken=mvc.perform(post("/api/v1/workspaces").session(session).header("X-CSRF-TOKEN",oldCsrf)
                .header("Idempotency-Key",key()).contentType("application/json")
                .content(json.write(Map.of("releaseId",RELEASE,"title","Old token","guidanceMode","QUIET")))).andReturn();
            assertEquals(403,staleToken.getResponse().getStatus(),"login invalidates the old raw CSRF token, not merely its mask");
            renewCsrf();
            assertNotEquals(oldCsrf,csrf);
        }
        private void renewCsrf() throws Exception {
            var request=get("/api/v1/auth/csrf");
            if(session!=null) request.session(session);
            MvcResult result=mvc.perform(request).andReturn();
            assertEquals(200,result.getResponse().getStatus());
            session=(MockHttpSession)result.getRequest().getSession(false);
            JsonNode value=json.tree(result.getResponse().getContentAsString());
            schemas.validate("Csrf",value);csrf=value.path("token").asText();
        }
        JsonNode call(String method,String path,Object body,int expected,String schema,String idempotency) throws Exception {
            var request=request(HttpMethod.valueOf(method),"/api/v1"+path).session(session).header("X-CSRF-TOKEN",csrf);
            if(idempotency!=null) request.header("Idempotency-Key",idempotency);
            if(body!=null) request.contentType("application/json").content(json.write(body));
            MvcResult result=mvc.perform(request).andReturn();
            String text=result.getResponse().getContentAsString();
            assertEquals(expected,result.getResponse().getStatus(),method+" "+path+": "+text);
            if(text.isEmpty()) return null;
            JsonNode value=json.tree(text);
            if(schema!=null) schemas.validate(schema,value);
            if(expected>=400) schemas.validate("Error",value);
            return value;
        }
        JsonNode workspace(String idempotency) throws Exception {
            return call("POST","/workspaces",Map.of("releaseId",RELEASE,"title","Generics learning","guidanceMode","ON_DEMAND"),201,"Workspace",idempotency);
        }
    }
    private JsonNode exercise(Client client) throws Exception {
        return client.call("GET","/exercises?releaseId="+RELEASE+"&limit=100",null,200,"ExerciseList",null).path("items").get(0);
    }
    private Map<String,Object> attemptBody(String workspace,JsonNode exercise,boolean correct) {
        JsonNode privateSpec=json.tree(jdbc.queryForObject("SELECT answer_spec FROM exercise WHERE id=? AND revision=?",String.class,exercise.path("id").asText(),exercise.path("revision").asInt()));
        var expected=new HashSet<String>();privateSpec.path("optionIds").forEach(n->expected.add(n.asText()));
        List<String> chosen;
        if(correct) chosen=new ArrayList<>(expected);
        else chosen=List.of(java.util.stream.StreamSupport.stream(exercise.path("options").spliterator(),false)
            .map(n->n.path("id").asText()).filter(n->!expected.contains(n)).findFirst().orElseThrow());
        return Map.of("workspaceId",workspace,"releaseId",RELEASE,"exerciseId",exercise.path("id").asText(),"exerciseRevision",exercise.path("revision").asInt(),"answer",Map.of("kind","CHOICE","optionIds",chosen));
    }
    private void drain() { for(int i=0;i<20;i++) if(outbox.dispatchBatch(100)==0) break; }

    @Test void realLearningJourneyKeepsGradesEvidenceArtifactsAndRunsConnected() throws Exception {
        Client c=new Client();
        var capabilities=c.call("GET","/capabilities",null,200,"Capabilities",null);
        assertFalse(capabilities.path("businessReady").asBoolean());
        assertTrue(capabilities.path("unavailable").toString().contains("LLM_TUTOR"));
        c.call("GET","/releases/active",null,200,"ActiveRelease",null);
        var concepts=c.call("GET","/concepts?releaseId="+RELEASE,null,200,"ConceptList",null);
        assertEquals(16,concepts.path("items").size());
        JsonNode e=exercise(c);assertFalse(e.has("answerSpec"));assertFalse(e.has("answer_spec"));
        c.call("GET","/exercises/"+e.path("id").asText()+"?releaseId="+RELEASE,null,200,"Exercise",null);
        String workspaceKey=key();var space=c.workspace(workspaceKey);String workspace=space.path("id").asText();
        assertEquals(space.path("id"),c.workspace(workspaceKey).path("id"));
        c.call("GET","/workspaces",null,200,"WorkspaceList",null);
        String submitKey=key();var input=attemptBody(workspace,e,false);
        JsonNode wrong=c.call("POST","/attempts",input,201,"Attempt",submitKey);
        assertEquals("GRADED",wrong.path("status").asText());assertEquals(0,wrong.path("score").asInt());
        assertEquals(0,wrong.path("hintLevel").asInt());
        assertEquals(wrong.path("id"),c.call("POST","/attempts",input,201,"Attempt",submitKey).path("id"));
        c.call("POST","/attempts",attemptBody(workspace,e,true),409,null,submitKey);
        drain();
        var progress=c.call("GET","/me/progress",null,200,"Progress",null);
        assertFalse(progress.path("rebuilding").asBoolean());
        assertTrue(progress.path("items").size()>0);
        for(JsonNode item:progress.path("items")) {assertEquals(1,item.path("independentSamples").asInt());assertEquals("INSUFFICIENT",item.path("evidenceStatus").asText());}
        var reviews=c.call("GET","/me/reviews",null,200,"ReviewList",null);assertFalse(reviews.isEmpty());
        var practice=c.call("POST","/attempts",attemptBody(workspace,e,true),201,"Attempt",key());
        assertEquals(100,practice.path("score").asInt());assertEquals(4,practice.path("hintLevel").asInt());
        drain();
        assertEquals(progress.path("items"),c.call("GET","/me/progress",null,200,"Progress",null).path("items"));
        var notebook=c.call("POST","/workspaces/"+workspace+"/notebook-drafts",Map.of("attemptIds",List.of(wrong.path("id").asText()),"title","My actual corrections"),201,"Artifact",key());
        var version=c.call("GET","/artifacts/"+notebook.path("id").asText()+"/revisions?revision=1",null,200,"ArtifactRevision",null);
        assertEquals("DRAFT",version.path("reviewStatus").asText());
        assertEquals(wrong.path("id"),version.path("content").path("blocks").get(0).path("attemptId"));
        assertTrue(version.path("inputRefs").size()>=2);
        var goal=c.call("POST","/workspaces/"+workspace+"/goals",Map.of("text","Understand wildcard bounds","acceptance",List.of("Use versioned primary evidence")),201,"Goal",key());
        var context=c.call("POST","/workspaces/"+workspace+"/context",null,201,"ContextSnapshot",null);
        assertTrue(context.path("references").toString().contains(wrong.path("id").asText()));
        var run=c.call("POST","/workspaces/"+workspace+"/evidence-runs",Map.of("goalId",goal.path("id").asText(),"snapshotId",context.path("id").asText(),"query","PECS 通配符"),202,"AgentRun",key());
        assertTrue(run.path("plan").path("budget").has("maxWallMillis"));
        workflow.advance(c.owner,UUID.fromString(workspace),UUID.fromString(run.path("id").asText()));
        var completed=c.call("GET","/agent-runs/"+run.path("id").asText(),null,200,"AgentRun",null);
        assertEquals("COMPLETED",completed.path("status").asText());
        assertEquals(0,completed.path("spentTokens").asInt());assertEquals(0,completed.path("spentMicros").asInt());
        assertTrue(completed.path("steps").toString().contains("KNOWLEDGE_CHUNK"));
        c.call("GET","/agent-runs/"+run.path("id").asText()+"/events",null,200,"RunEventList",null);
        drain();
        var stream=c.call("GET","/workspaces/"+workspace+"/events?limit=100",null,200,"WorkspaceEventList",null);
        for(int i=0;i<stream.size();i++) assertEquals(i+1,stream.get(i).path("sequence").asLong());
        var projection=c.call("GET","/workspaces/"+workspace+"/projection",null,200,"WorkspaceProjection",null);
        assertTrue(projection.path("caughtUp").asBoolean());assertEquals(0,projection.path("deadLetters").asInt());
    }

    @Test void absentEvidenceCannotBecomeAFabricatedAnswerOrCompletedRun() throws Exception {
        Client c=new Client();String workspace=c.workspace(key()).path("id").asText();
        var goal=c.call("POST","/workspaces/"+workspace+"/goals",Map.of("text","Find supported evidence","acceptance",List.of("At least one actual source")),201,"Goal",key());
        var snapshot=c.call("POST","/workspaces/"+workspace+"/context",null,201,"ContextSnapshot",null);
        String query="unfindabletoken"+UUID.randomUUID().toString().replace("-","");
        var search=c.call("GET","/knowledge/search?releaseId="+RELEASE+"&q="+query,null,200,"SearchResult",null);
        assertTrue(search.path("insufficientEvidence").asBoolean());assertTrue(search.path("items").isEmpty());
        var run=c.call("POST","/workspaces/"+workspace+"/evidence-runs",Map.of("goalId",goal.path("id").asText(),"snapshotId",snapshot.path("id").asText(),"query",query),202,"AgentRun",key());
        workflow.advance(c.owner,UUID.fromString(workspace),UUID.fromString(run.path("id").asText()));
        var result=c.call("GET","/agent-runs/"+run.path("id").asText(),null,200,"AgentRun",null);
        assertEquals("PARTIAL",result.path("status").asText());
        assertFalse(result.has("answer"));
        assertTrue(result.path("steps").toString().contains("FAILED"));
    }

    @Test void ownershipCsrfAndServerFactsAreEnforcedAtHttpBoundary() throws Exception {
        Client owner=new Client(),other=new Client();String workspace=owner.workspace(key()).path("id").asText();
        other.call("GET","/workspaces/"+workspace,null,404,null,null);
        other.call("POST","/workspaces/"+workspace+"/context",null,404,null,null);
        owner.call("POST","/workspaces/"+workspace+"/events",Map.of("id",key(),"type","ANSWER_CORRECT","references",List.of(),"expectedRevision",2),400,null,null);
        var noCsrf=mvc.perform(post("/api/v1/workspaces").session(owner.session).contentType("application/json").content("{}")).andReturn();
        assertEquals(403,noCsrf.getResponse().getStatus());
        var unauthorized=mvc.perform(get("/api/v1/workspaces/"+workspace)).andReturn();assertEquals(401,unauthorized.getResponse().getStatus());
        owner.call("POST","/admin/releases",Map.of(),403,null,null);
        owner.call("POST","/internal/worker/complete",Map.of(),403,null,null);
        var e=exercise(owner);var input=attemptBody(workspace,e,false);
        JsonNode submitted=owner.call("POST","/attempts",input,201,"Attempt",key());
        other.call("GET","/attempts/"+submitted.path("id").asText(),null,404,null,null);
        owner.call("PATCH","/me/reviews/"+key(),Map.of("status","DONE"),400,null,null);
    }

    @Test void codeAnswerForASelectionExerciseCreatesNoLearningFactOrOutboxEvent() throws Exception {
        Client c=new Client();String workspace=c.workspace(key()).path("id").asText();JsonNode e=exercise(c);
        long before=jdbc.queryForObject("SELECT event_sequence FROM learning_workspace WHERE id=?",Long.class,workspace);
        // Code grading is now supported for reviewed SIGNATURE/REFACTOR/FILL exercises;
        // submitting CODE to this selection exercise is a client-shape mismatch, not an unavailable grader.
        c.call("POST","/attempts",Map.of("workspaceId",workspace,"releaseId",RELEASE,"exerciseId",e.path("id").asText(),"exerciseRevision",1,"answer",Map.of("kind","CODE","source","class UserCode {}")),400,null,key());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_attempt WHERE user_id=?",Integer.class,c.owner.toString()));
        assertEquals(before,jdbc.queryForObject("SELECT event_sequence FROM learning_workspace WHERE id=?",Long.class,workspace));
    }

    @Test void persistedEventsReplayBySequenceAndRevisionConflictsDoNotLoseTheDraft() throws Exception {
        Client c=new Client();var workspace=c.workspace(key());String id=workspace.path("id").asText();
        Map<String,Object> event=Map.of("id",key(),"type","SELECTION_COMMITTED","references",List.of(),"expectedRevision",workspace.path("revision").asInt());
        var created=c.call("POST","/workspaces/"+id+"/events",event,201,"WorkspaceEvent",null);
        assertEquals(created,c.call("POST","/workspaces/"+id+"/events",event,201,"WorkspaceEvent",null));
        var tail=c.call("GET","/workspaces/"+id+"/events?after="+(created.path("sequence").asInt()-1),null,200,"WorkspaceEventList",null);
        assertEquals(1,tail.size());assertEquals(created,tail.get(0));
        c.call("PATCH","/workspaces/"+id,Map.of("title","Old stale draft","guidanceMode","QUIET","expectedRevision",workspace.path("revision").asInt()),409,null,null);
        c.call("PATCH","/workspaces/"+id,Map.of("title","Current draft","guidanceMode","QUIET","expectedRevision",created.path("revision").asInt()),200,"Workspace",null);
        c.call("GET","/workspaces/"+id+"/events?after=999999",null,400,null,null);
    }

    @Test void pauseResumeCancelUseEpochFencingAndSteeringRequiresAnActualPlanner() throws Exception {
        Client c=new Client();String workspace=c.workspace(key()).path("id").asText();
        var goal=c.call("POST","/workspaces/"+workspace+"/goals",Map.of("text","Plan evidence","acceptance",List.of("Versioned evidence")),201,"Goal",key());
        var snapshot=c.call("POST","/workspaces/"+workspace+"/context",null,201,"ContextSnapshot",null);
        var run=c.call("POST","/workspaces/"+workspace+"/evidence-runs",Map.of("goalId",goal.path("id").asText(),"snapshotId",snapshot.path("id").asText(),"query","PECS"),202,"AgentRun",key());
        String path="/agent-runs/"+run.path("id").asText()+"/commands";
        c.call("POST",path,Map.of("kind","PAUSE","expectedPlanRevision",1,"expectedRunEpoch",new java.math.BigInteger("18446744073709551617")),400,null,null);
        c.call("POST",path,Map.of("kind","STEER","expectedPlanRevision",1,"expectedRunEpoch",1,"instruction","Create a video"),503,null,null);
        var paused=c.call("POST",path,Map.of("kind","PAUSE","expectedPlanRevision",1,"expectedRunEpoch",1),202,"AgentRun",null);
        assertEquals("PAUSED",paused.path("status").asText());
        c.call("POST",path,Map.of("kind","RESUME","expectedPlanRevision",1,"expectedRunEpoch",1),409,null,null);
        var resumed=c.call("POST",path,Map.of("kind","RESUME","expectedPlanRevision",1,"expectedRunEpoch",paused.path("runEpoch").asLong()),202,"AgentRun",null);
        var cancelled=c.call("POST",path,Map.of("kind","CANCEL","expectedPlanRevision",1,"expectedRunEpoch",resumed.path("runEpoch").asLong()),202,"AgentRun",null);
        assertEquals("CANCELLED",cancelled.path("status").asText());
    }


    @Test void jsonSizeAndIntegerBoundsApplyBeforeNarrowingOrObjectMaterialization() throws Exception {
        Client c=new Client();var workspace=c.workspace(key());String id=workspace.path("id").asText();
        var tooLargeRevision=new java.math.BigInteger("4294967296").add(java.math.BigInteger.valueOf(workspace.path("revision").asInt()));
        c.call("PATCH","/workspaces/"+id,Map.of("title","Wrapped revision","guidanceMode","QUIET","expectedRevision",tooLargeRevision),400,null,null);
        var large=mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("x".repeat(262145))).andReturn();
        assertEquals(413,large.getResponse().getStatus());schemas.validate("Error",json.tree(large.getResponse().getContentAsString()));
        var missing=mvc.perform(post("/api/v1/workspaces").session(c.session).header("X-CSRF-TOKEN",c.csrf).contentType("application/json").content(json.write(Map.of("releaseId",RELEASE,"title","Missing key","guidanceMode","QUIET")))).andReturn();
        assertEquals(400,missing.getResponse().getStatus());schemas.validate("Error",json.tree(missing.getResponse().getContentAsString()));
        String nested="[".repeat(65)+"0"+"]".repeat(65);
        var depth=mvc.perform(post("/api/v1/workspaces").session(c.session).header("X-CSRF-TOKEN",c.csrf).header("Idempotency-Key",key()).contentType("application/json").content(nested)).andReturn();
        assertEquals(400,depth.getResponse().getStatus());
    }

    @Test void bodyLimitAlsoRejectsUnknownLengthStreamingRequests() throws Exception {
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/workspaces") {
            @Override public long getContentLengthLong() {return -1;}
            @Override public int getContentLength() {return -1;}
        };
        request.setContentType("application/json");request.setContent(new byte[262145]);
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        new io.typeforge.operations.RequestBodyLimit().doFilter(request,response,(req,res)->reached.set(true));
        assertEquals(413,response.getStatus());assertFalse(reached.get());
    }

    @Test void actualSpringMappingsMatchImplementedContractExactly() throws Exception {
        Set<String> actual=new HashSet<>(),expected=new HashSet<>();
        mappings.getHandlerMethods().forEach((mapping,handler)->{
            for(String path:mapping.getPatternValues()) if(path.startsWith("/api/v1/"))
                mapping.getMethodsCondition().getMethods().forEach(method->actual.add(method.name()+" "+path.substring(7)));
        });
        JsonNode contract;
        try(var stream=new ClassPathResource("contracts/openapi.json").getInputStream()) { contract=json.tree(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
        contract.path("paths").properties().forEach(path->path.getValue().properties().forEach(method->{
            if(method.getValue().path("x-status").asText().equals("implemented")) expected.add(method.getKey().toUpperCase(java.util.Locale.ROOT)+" "+path.getKey());
        }));
        assertEquals(expected,actual,"Every implemented route and only implemented routes must exist in Spring");
    }
}
