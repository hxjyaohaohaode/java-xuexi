package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.compiler.CodeRubric;
import io.typeforge.compiler.CompileProtocol;
import io.typeforge.compiler.CompileService;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.exercise.LearningService;
import io.typeforge.identity.IdentityService;
import io.typeforge.identity.AccountClosureService;
import io.typeforge.notebook.NotebookService;
import io.typeforge.notebook.NotebookActions;
import io.typeforge.lab.LabService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.operations.events.OutboxDispatcher;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real SQL queue and event integration; manually supplied outcomes are protocol fixtures, not isolation evidence. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:compiler-queue;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH",
    "typeforge.compiler.enabled=true","typeforge.compiler.worker-url=http://127.0.0.1:9/v1/compile",
    "typeforge.compiler.worker-token=fixture-compiler-secret-at-least-32-characters","typeforge.compiler.dispatch-enabled=false"
})
@ActiveProfiles({"test","demo"})
@AutoConfigureMockMvc
class CompileQueueTest {
    @Autowired MockMvc mvc;
    @Autowired CompileService compiler;
    @Autowired IdentityService identities;
    @Autowired AccountClosureService closing;
    @Autowired WorkspaceService workspaces;
    @Autowired CurriculumService curriculum;
    @Autowired LearningService learning;
    @Autowired NotebookService notebooks;
    @Autowired NotebookActions actions;
    @Autowired ArtifactService artifacts;
    @Autowired LabService labs;
    @Autowired OutboxDispatcher outbox;
    @Autowired JsonCodec json;
    @Autowired SchemaCatalog schemas;
    @Autowired JdbcTemplate jdbc;
    private UUID owner,workspace;
    private static final UUID RELEASE=DemoContentInitializer.RELEASE_ID;
    private static final String SOURCE="public class Main { public static <T> T first(java.util.List<T> values) { return values.get(0); } }";
    private static final List<CodeRubric.Rule> RULES=List.of(
        new CodeRubric.Rule("static void verify(){ Integer x=Main.first(java.util.List.of(1)); }",true,List.of()),
        new CodeRubric.Rule("static void verify(){ String x=Main.first(java.util.List.of(1)); }",false,List.of("compiler.err.prob.found.req")));
    @BeforeEach void fixture(){owner=account();workspace=workspaces.create(owner,RELEASE,"Compile contract","ON_DEMAND",key()).id();}
    @AfterEach void cleanup(){if(!"ACTIVE".equals(jdbc.queryForObject("SELECT status FROM app_user WHERE id=?",String.class,owner.toString())))return;for(var job:compiler.list(owner,workspace))if(List.of("QUEUED","RUNNING").contains(job.status()))compiler.cancel(owner,job.id(),key());drain();}
    private UUID account(){return identities.register("compile-"+key(),"fixture-strong-password-123").userId();}
    private String key(){return UUID.randomUUID().toString();}
    private JsonNode tree(Object value){return json.tree(json.write(value));}
    private CompileService.Job submit(String source,String key){return compiler.create(owner,workspace,RELEASE,source,"COMPILE_ONLY",key);}
    private void drain(){for(int i=0;i<20;i++)if(outbox.dispatchBatch(100)==0)break;}
    private UUID exercise(){
        UUID id=UUID.randomUUID(),family=UUID.randomUUID(),concept=curriculum.concepts(RELEASE).get(0).id();
        var spec=Map.of("options",List.of(),"optionIds",List.of(),"compileChecks",RULES,"explanation","Types must stay linked.","misconceptions",List.of("RETURN_TYPE"),"hints",List.of("Name the type variable.","Link input and output.","Try opposite assignments."));
        jdbc.update("INSERT INTO exercise(id,revision,family_id,kind,difficulty,prompt,answer_spec,rubric_version,status,author_id) VALUES(?,1,?,'SIGNATURE',1,?,?,?,'REVIEWED',?)",id.toString(),family.toString(),"Write Main.first with a type-safe signature",json.write(spec),CodeRubric.VERSION,owner.toString());
        jdbc.update("INSERT INTO exercise_concept(exercise_id,exercise_revision,concept_id,weight) VALUES(?,1,?,1)",id.toString(),concept.toString());
        jdbc.update("INSERT INTO release_exercise(release_id,exercise_id,exercise_revision) VALUES(?,?,1)",RELEASE.toString(),id.toString());return id;
    }
    private CompileProtocol.Result result(CompileService.Ticket ticket,List<CompileProtocol.CheckResult> checks){return new CompileProtocol.Result("SUCCEEDED",ticket.request().sourceHash(),"javac 17.0.21",List.of(),false,checks);}
    private List<CompileProtocol.CheckResult> passing(){return List.of(new CompileProtocol.CheckResult("check0","SUCCEEDED",List.of(),false),new CompileProtocol.CheckResult("check1","COMPILE_ERROR",List.of("compiler.err.prob.found.req"),true));}

    @Test void boundedQueueReplaysExactlyAndIsPrivate(){
        String idem=key();var job=submit(SOURCE,idem);schemas.validate("CompileJob",tree(job));
        assertEquals(job.id(),submit(SOURCE,idem).id());assertEquals(409,assertThrows(DomainException.class,()->submit(SOURCE+" ",idem)).status());
        for(int i=0;i<3;i++)submit(SOURCE,key());
        assertEquals(429,assertThrows(DomainException.class,()->submit(SOURCE,key())).status());
        assertEquals(4,compiler.list(owner,workspace).size());
        assertEquals(404,assertThrows(DomainException.class,()->compiler.get(account(),job.id())).status());
        assertEquals(404,assertThrows(DomainException.class,()->compiler.cancel(account(),job.id(),key())).status());
        assertFalse(json.write(compiler.get(owner,job.id())).contains("source_code"));
    }
    @Test void cancellingAnActiveJobRejectsLateLeaseAndDoesNotInventAnAttempt(){
        var job=submit(SOURCE,key());var ticket=compiler.claimNext();assertNotNull(ticket);assertEquals(job.id(),ticket.id());
        assertTrue(compiler.active(ticket));assertEquals("CANCELLED",compiler.cancel(owner,job.id(),key()).status());
        assertFalse(compiler.active(ticket));assertFalse(compiler.accept(ticket,result(ticket,List.of())));
        assertEquals("CANCELLED",compiler.get(owner,job.id()).status());assertTrue(learning.list(owner,workspace).isEmpty());
        assertEquals("CANCELLED",compiler.cancel(owner,job.id(),key()).status());
    }
    @Test void expiredLeaseAndQueueDeadlineArePersistedAsInfrastructureFailures(){
        var job=submit(SOURCE,key());var ticket=compiler.claimNext();assertNotNull(ticket);
        jdbc.update("UPDATE compile_job SET lease_until=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),job.id().toString());
        compiler.recoverExpired();assertEquals("COMPILER_LEASE_EXPIRED",compiler.get(owner,job.id()).errorCode());
        assertFalse(compiler.accept(ticket,result(ticket,List.of())));
        var waiting=submit(SOURCE,key());jdbc.update("UPDATE compile_job SET created_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(601)),waiting.id().toString());
        compiler.recoverExpired();assertEquals("TIMED_OUT",compiler.get(owner,waiting.id()).status());
    }
    @Test void unsupportedExecutionOversizedUtf8AndCrossReleaseNeverEnterQueue(){
        assertEquals(503,assertThrows(DomainException.class,()->compiler.create(owner,workspace,RELEASE,SOURCE,"COMPILE_AND_RUN",key())).status());
        assertEquals(400,assertThrows(DomainException.class,()->submit("字".repeat(11000),key())).status());
        assertEquals(400,assertThrows(DomainException.class,()->submit("\0",key())).status());
        assertEquals(400,assertThrows(DomainException.class,()->submit("\uD800",key())).status());
        assertEquals(400,assertThrows(DomainException.class,()->compiler.create(owner,workspace,UUID.randomUUID(),SOURCE,"COMPILE_ONLY",key())).status());
        assertTrue(compiler.list(owner,workspace).isEmpty());
    }
    @Test void codeAttemptWaitsForAllChecksAndOnlyThenProducesLearningEvidence(){
        UUID exercise=exercise();var attempt=learning.submit(owner,workspace,RELEASE,exercise,1,tree(Map.of("kind","CODE","source",SOURCE)),key());
        assertEquals("PENDING",attempt.status());assertNull(attempt.score());assertNull(attempt.evidenceRef());assertNotNull(attempt.jobId());schemas.validate("Attempt",tree(attempt));
        assertEquals(409,assertThrows(DomainException.class,()->notebooks.build(owner,workspace,List.of(attempt.id()),"Pending notebook",key())).status());
        var ticket=compiler.claimNext();assertNotNull(ticket);assertEquals("GRADING",learning.get(owner,attempt.id()).status());
        assertTrue(compiler.accept(ticket,result(ticket,passing())));assertEquals("GRADING",learning.get(owner,attempt.id()).status());
        drain();var graded=learning.get(owner,attempt.id());assertEquals("GRADED",graded.status());assertEquals(100,graded.score().intValue());assertNotNull(graded.evidenceRef());schemas.validate("Attempt",tree(graded));
        assertEquals(1,learning.progress(owner).items().get(0).independentSamples());
        String exposed=json.write(compiler.get(owner,attempt.jobId()));assertFalse(exposed.contains("Probe"));assertFalse(exposed.contains("check0"));assertFalse(exposed.contains("static void verify"));
    }
    @Test void compilingAProgramDoesNotPassAnExerciseWithFailingPositiveChecks(){
        UUID exercise=exercise();var attempt=learning.submit(owner,workspace,RELEASE,exercise,1,tree(Map.of("kind","CODE","source","public class Main {}")),key());
        var ticket=compiler.claimNext();assertNotNull(ticket);
        var failed=List.of(new CompileProtocol.CheckResult("check0","COMPILE_ERROR",List.of("compiler.err.cant.resolve.location"),true),new CompileProtocol.CheckResult("check1","COMPILE_ERROR",List.of("compiler.err.cant.resolve.location"),true));
        compiler.accept(ticket,result(ticket,failed));drain();assertEquals("GRADED",learning.get(owner,attempt.id()).status());assertEquals(0,learning.get(owner,attempt.id()).score().intValue());
        assertEquals(1,learning.reviews(owner).size());
    }
    @Test void cancellationAndResourceFailureNeverCountAsWrongAnswers(){
        UUID exercise=exercise();var attempt=learning.submit(owner,workspace,RELEASE,exercise,1,tree(Map.of("kind","CODE","source",SOURCE)),key());
        compiler.cancel(owner,attempt.jobId(),key());drain();var failed=learning.get(owner,attempt.id());
        assertEquals("FAILED",failed.status());assertNull(failed.score());assertTrue(learning.progress(owner).items().isEmpty());assertTrue(learning.reviews(owner).isEmpty());
        var another=learning.submit(owner,workspace,RELEASE,exercise,1,tree(Map.of("kind","CODE","source",SOURCE)),key());var ticket=compiler.claimNext();assertNotNull(ticket);
        compiler.fail(ticket,"COMPILER_TIMEOUT");drain();assertEquals("FAILED",learning.get(owner,another.id()).status());assertTrue(learning.progress(owner).items().isEmpty());
    }
    @Test void hintsAreDurableIdempotentAndCannotFarmIndependentMastery(){
        UUID exercise=exercise();String idem=key();var hint=learning.hint(owner,workspace,RELEASE,exercise,1,1,idem);
        assertEquals(hint,learning.hint(owner,workspace,RELEASE,exercise,1,1,idem));assertEquals("Name the type variable.",hint.text());schemas.validate("ExerciseHint",tree(hint));
        var attempt=learning.submit(owner,workspace,RELEASE,exercise,1,tree(Map.of("kind","CODE","source",SOURCE)),key());assertEquals(1,attempt.hintLevel());
        var ticket=compiler.claimNext();assertNotNull(ticket);compiler.accept(ticket,result(ticket,passing()));drain();
        assertEquals(100,learning.get(owner,attempt.id()).score().intValue());assertTrue(learning.progress(owner).items().stream().allMatch(item->item.independentSamples()==0));
    }
    @Test void concurrentPendingAnswersReserveIndependentFamilyOnce(){
        UUID exercise=exercise();var first=learning.submit(owner,workspace,RELEASE,exercise,1,tree(Map.of("kind","CODE","source",SOURCE)),key());
        var second=learning.submit(owner,workspace,RELEASE,exercise,1,tree(Map.of("kind","CODE","source",SOURCE)),key());assertEquals(0,first.hintLevel());assertEquals(4,second.hintLevel());
        for(int i=0;i<2;i++){var ticket=compiler.claimNext();assertNotNull(ticket);compiler.accept(ticket,result(ticket,passing()));}
        drain();assertEquals(1,learning.progress(owner).items().get(0).independentSamples());
    }
    @Test void genericSuccessOnlyRubricIsRejectedWithoutPersistingAnAttempt(){
        UUID exercise=exercise();jdbc.update("UPDATE exercise SET answer_spec=? WHERE id=?",json.write(Map.of("compileChecks",List.of(RULES.get(0)),"misconceptions",List.of(),"explanation","invalid")),exercise.toString());
        assertEquals(503,assertThrows(DomainException.class,()->learning.submit(owner,workspace,RELEASE,exercise,1,tree(Map.of("kind","CODE","source",SOURCE)),key())).status());
        assertTrue(learning.list(owner,workspace).isEmpty());assertTrue(compiler.list(owner,workspace).isEmpty());
    }
    @Test void actualAuthenticatedHttpRejectsCsrfForgeryAndPrivateAttemptLinkage() throws Exception {
        String username=jdbc.queryForObject("SELECT username FROM app_user WHERE id=?",String.class,owner.toString());
        var session=HttpSessionFixture.login(mvc,json,username,"fixture-strong-password-123");
        String body=json.write(Map.of("workspaceId",workspace,"releaseId",RELEASE,"source",SOURCE,"mode","COMPILE_ONLY"));
        assertEquals(403,mvc.perform(post("/api/v1/compile-jobs").session(session).header("Idempotency-Key",key()).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
        var response=mvc.perform(post("/api/v1/compile-jobs").session(session).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(body)).andReturn().getResponse();
        assertEquals(202,response.getStatus(),response.getContentAsString());JsonNode job=json.tree(response.getContentAsString());schemas.validate("CompileJob",job);
        var forged=(com.fasterxml.jackson.databind.node.ObjectNode)json.tree(body);forged.put("attemptId",UUID.randomUUID().toString());
        assertEquals(400,mvc.perform(post("/api/v1/compile-jobs").session(session).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(forged.toString())).andReturn().getResponse().getStatus());
        UUID other=account();String otherName=jdbc.queryForObject("SELECT username FROM app_user WHERE id=?",String.class,other.toString());var otherSession=HttpSessionFixture.login(mvc,json,otherName,"fixture-strong-password-123");
        assertEquals(404,mvc.perform(get("/api/v1/compile-jobs/"+job.path("id").asText()).session(otherSession)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(post("/api/v1/compile-jobs/"+job.path("id").asText()+"/cancel").session(session).with(csrf()).header("Idempotency-Key",key())).andReturn().getResponse().getStatus());
    }
    @Test void codeLabsExposeActualQueueStateWithoutCreatingMastery(){
        var input=tree(Map.of("workspaceId",workspace,"releaseId",RELEASE,"labType","CAPTURE","source",SOURCE,"prediction","ALLOWED"));
        var lab=labs.create(owner,input,key());assertEquals("QUEUED",lab.verdict());assertNotNull(lab.jobId());assertNull(lab.predictionCorrect());schemas.validate("LabSession",tree(lab));
        var ticket=compiler.claimNext();assertNotNull(ticket);assertEquals("RUNNING",labs.get(owner,lab.id()).verdict());
        compiler.accept(ticket,result(ticket,List.of()));var complete=labs.get(owner,lab.id());assertEquals("ALLOWED",complete.verdict());assertEquals(Boolean.TRUE,complete.predictionCorrect());
        drain();assertTrue(learning.progress(owner).items().isEmpty());
    }
    @Test void largeCodeCorrectionNotebookPreservesSourceAndRoutesHintAndCompileToRealServices(){
        UUID exercise=exercise();String source=SOURCE+"\n/*"+"teaching fixture ".repeat(700)+"*/";
        var attempt=learning.submit(owner,workspace,RELEASE,exercise,1,tree(Map.of("kind","CODE","source",source)),key());
        var ticket=compiler.claimNext();assertNotNull(ticket);var failing=List.of(new CompileProtocol.CheckResult("check0","COMPILE_ERROR",List.of("compiler.err.cant.resolve.location"),true),new CompileProtocol.CheckResult("check1","COMPILE_ERROR",List.of("compiler.err.cant.resolve.location"),true));
        compiler.accept(ticket,result(ticket,failing));drain();
        var notebook=notebooks.build(owner,workspace,List.of(attempt.id()),"Code correction",key());
        var version=artifacts.version(owner,notebook.id(),1);JsonNode block=version.content().path("blocks").get(0);
        assertEquals(source,block.path("config").path("originalSource").asText());assertTrue(block.path("config").path("studentReason").asText().length()<8000);
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)tree(Map.of("artifactRevision",1,"blockId",block.path("id").asText(),"action","requestCompile","expectedBlockRevision",1,"answer",Map.of("kind","CODE","source",SOURCE),"selection",""));
        var compile=actions.dispatch(owner,notebook.id(),input,key());assertEquals("ACCEPTED",compile.status());assertNotNull(compile.compileJobId());assertNull(compile.attemptId());
        input.put("action","requestHint");input.putNull("answer");input.put("hintLevel",2);
        var hint=actions.dispatch(owner,notebook.id(),input,key());assertEquals("Link input and output.",hint.message());assertEquals("COMPLETED",hint.status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_hint_receipt WHERE user_id=?",Integer.class,owner.toString()));
    }
    @Test void closingAccountFencesPendingCompilationAndRetainsCompletedHistory(){
        var historical=submit(SOURCE,key());var finished=compiler.claimNext();assertNotNull(finished);compiler.accept(finished,result(finished,List.of()));
        UUID exercise=exercise();learning.hint(owner,workspace,RELEASE,exercise,1,1,key());
        var attempt=learning.submit(owner,workspace,RELEASE,exercise,1,tree(Map.of("kind","CODE","source",SOURCE)),key());var pending=compiler.claimNext();assertNotNull(pending);
        String name=jdbc.queryForObject("SELECT username FROM app_user WHERE id=?",String.class,owner.toString());
        assertEquals("CLOSED",closing.close(owner,"fixture-strong-password-123",name).status());
        var job=jdbc.queryForMap("SELECT status,source_code,result,lease_token,lease_until FROM compile_job WHERE id=?",attempt.jobId().toString());
        assertEquals("CANCELLED",job.get("status"));assertEquals("",job.get("source_code"));assertNull(job.get("result"));assertNull(job.get("lease_token"));assertNull(job.get("lease_until"));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM exercise_attempt WHERE id=?",String.class,attempt.id().toString()));
        var manifest=jdbc.queryForMap("SELECT request_json,request_hash FROM workspace_compile WHERE job_id=?",attempt.jobId().toString());
        assertEquals(json.tree("[]"),json.tree(manifest.get("request_json")));assertEquals(json.hash(List.of()),manifest.get("request_hash"));
        assertEquals("",jdbc.queryForObject("SELECT hint_text FROM exercise_hint_receipt WHERE user_id=?",String.class,owner.toString()));
        assertEquals(SOURCE,jdbc.queryForObject("SELECT source_code FROM compile_job WHERE id=?",String.class,historical.id().toString()));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM compile_job WHERE id=?",String.class,historical.id().toString()));
        assertFalse(compiler.accept(pending,result(pending,passing())));assertEquals(404,assertThrows(DomainException.class,()->compiler.get(owner,attempt.jobId())).status());
        drain();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learner_concept WHERE user_id=?",Integer.class,owner.toString()));
    }
}
