package io.typeforge.publishing;

import static io.typeforge.publishing.ContentWorkbenchModels.*;
import static io.typeforge.ingestion.IngestionModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.compiler.CodeRubric;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.exercise.LearningService;
import io.typeforge.identity.IdentityService;
import io.typeforge.ingestion.ManualIngestionService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.retrieval.LocalKnowledgeSearch;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceService;
import java.net.URI;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only named accounts and actual migrated SQL/session authentication; no fixture is claimed as human production review. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ContentWorkbenchIntegrationTest {
    private static final boolean MYSQL="true".equals(System.getenv("TYPEFORGE_MYSQL_CONTENT_TEST"));
    private static final String PASSWORD="Isolated-test-password-41!";
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JsonCodec json;
    @Autowired Clock clock;
    @Autowired MockMvc mvc;
    @Autowired IdentityService identities;
    @Autowired ManualIngestionService ingestion;
    @Autowired ContentDraftService drafts;
    @Autowired ReleaseWorkbenchService releases;
    @Autowired CurriculumService curriculum;
    @Autowired LocalKnowledgeSearch search;
    @Autowired WorkspaceService workspaces;
    @Autowired LearningService learning;
    private IdentityService.Session author;
    private IdentityService.Session reviewer;
    private IdentityService.Session learner;
    private UUID originalActiveRelease;
    private final Set<String> fixtureActors=new HashSet<>();

    @DynamicPropertySource
    static void explicitTestDatabase(DynamicPropertyRegistry properties) {
        properties.add("typeforge.events.dispatch-enabled",()->false);
        if(MYSQL) {
            String url=requiredEnvironment("TYPEFORGE_DB_URL");
            if(!url.startsWith("jdbc:mysql://")) throw new IllegalStateException("Explicit content gate requires MySQL");
            URI target=URI.create(url.substring("jdbc:".length()));
            if(!Set.of("127.0.0.1","localhost","::1","[::1]").contains(target.getHost())
                || target.getPort()<1 || target.getPort()>65535 || target.getUserInfo()!=null
                || !target.getPath().matches("/typeforge_(runtime|smoke_[a-z0-9_]{1,48})")) {
                throw new IllegalStateException("Content regression is restricted to a named loopback test database");
            }
            String username=requiredEnvironment("TYPEFORGE_DB_USER"),password=requiredEnvironment("TYPEFORGE_DB_PASSWORD");
            properties.add("spring.datasource.url",()->url);
            properties.add("spring.datasource.username",()->username);
            properties.add("spring.datasource.password",()->password);
            properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
            properties.add("spring.flyway.locations",()->"classpath:database");
        } else {
            properties.add("spring.datasource.url",()->"jdbc:h2:mem:typeforge-editorial;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            properties.add("spring.datasource.username",()->"sa");
            properties.add("spring.datasource.password",()->"");
            properties.add("spring.datasource.driver-class-name",()->"org.h2.Driver");
            properties.add("spring.flyway.locations",()->"classpath:io/typeforge/operations/migrations/h2");
        }
    }
    private static String requiredEnvironment(String name) {
        String value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalStateException("Missing explicit content-test setting: "+name);
        return value;
    }

    @BeforeAll void rememberPrivateDatabasePointer() {
        if(!MYSQL) return;
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT singleton_id FROM content_workbench_lock WHERE singleton_id=1 FOR UPDATE",Integer.class);
            var current=jdbc.queryForList("SELECT release_id FROM active_release WHERE singleton_id=1 FOR UPDATE");
            if(!current.isEmpty()) originalActiveRelease=UUID.fromString(current.get(0).get("release_id").toString());
        });
    }

    @AfterAll void restoreOnlyThisFixturesActivePointer() {
        if(!MYSQL) return;
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT singleton_id FROM content_workbench_lock WHERE singleton_id=1 FOR UPDATE",Integer.class);
            var current=jdbc.queryForList("SELECT release_id,revision FROM active_release WHERE singleton_id=1 FOR UPDATE");
            if(current.isEmpty()) { assertNull(originalActiveRelease);return; }
            String active=current.get(0).get("release_id").toString();
            if(originalActiveRelease!=null && originalActiveRelease.toString().equals(active)) return;
            String creator=jdbc.queryForObject("SELECT created_by FROM content_release WHERE id=?",String.class,active);
            assertTrue(fixtureActors.contains(creator) || DemoContentInitializer.RELEASE_ID.toString().equals(active),"Do not overwrite an unrelated concurrent publication");
            int revision=((Number)current.get(0).get("revision")).intValue();
            if(originalActiveRelease==null) {
                // This explicit, reserved test database had no pointer before the fixture.
                // Remove only the fixture pointer; never delete its releases or user data.
                assertEquals(1,jdbc.update("DELETE FROM active_release WHERE singleton_id=1 AND release_id=? AND revision=?",active,revision));
            } else {
                assertTrue(revision<Integer.MAX_VALUE);
                assertEquals(1,jdbc.update("UPDATE active_release SET release_id=?,revision=? WHERE singleton_id=1 AND release_id=? AND revision=?",originalActiveRelease.toString(),revision+1,active,revision));
            }
        });
    }

    @BeforeEach void createExplicitTestOnlyActors() {
        Map<String,String> proof=jdbc.execute((ConnectionCallback<Map<String,String>>)connection->{
            String engine=connection.getMetaData().getDatabaseProductName(),version=connection.getMetaData().getDatabaseProductVersion();
            assertEquals(MYSQL?"MySQL":"H2",engine,"An explicit MySQL run must never silently exercise H2");
            if(MYSQL) assertTrue(version.startsWith("8.4."));
            return Map.of("engine",engine,"version",version);
        });
        System.out.println("TYPEFORGE_CONTENT_DATABASE_EVIDENCE "+json.write(proof));
        author=identities.register("author-"+suffix(),PASSWORD);
        reviewer=identities.register("reviewer-"+suffix(),PASSWORD);
        learner=identities.register("learner-"+suffix(),PASSWORD);
        fixtureActors.add(author.userId().toString());fixtureActors.add(reviewer.userId().toString());
        // Test fixture provisioning only. Production role grants have no HTTP self-service endpoint.
        jdbc.update("UPDATE app_user SET roles='[\"LEARNER\",\"EDITOR\"]' WHERE id=?",author.userId().toString());
        jdbc.update("UPDATE app_user SET roles='[\"LEARNER\",\"EDITOR\"]' WHERE id=?",reviewer.userId().toString());
    }
    private record Prepared(Concept concept,Source source,Capture capture,Chunk chunk,Exercise exercise) {}
    private Prepared prepare(boolean approveExercise) {
        Concept concept=drafts.createConcept(author.userId(),new ConceptInput(UUID.randomUUID(),"T"+suffix().substring(0,10).toUpperCase(java.util.Locale.ROOT),"Test generics concept",List.of()),key());
        Source source=source();
        Capture capture=ingestion.capture(author.userId(),source.id(),new CaptureInput("Actually supplied test-only original\r\n    List<? extends Number> value;", "PECS test-only cleaned source. Preserve indentation.\n    List<? extends Number> value;", "Manual test fixture cleaning; no external fetch or real human attestation."),key());
        Chunk chunk=drafts.createChunk(author.userId(),chunkInput(UUID.randomUUID(),1,0,concept.id(),source,capture,"PECS producer extends consumer super. "+suffix()),key());
        approve("CHUNK",chunk.id(),chunk.revision(),chunk.reviewHash());
        chunk=drafts.chunk(author.userId(),chunk.id(),1);
        Exercise exercise=drafts.createExercise(author.userId(),exerciseInput(UUID.randomUUID(),1,0,UUID.randomUUID(),concept.id(),chunk),key());
        if(approveExercise) {
            approve("EXERCISE",exercise.id(),1,exercise.reviewHash());
            exercise=drafts.exercise(author.userId(),exercise.id(),1);
        }
        return new Prepared(concept,source,capture,chunk,exercise);
    }
    private Source source() {
        return ingestion.register(author.userId(),new SourceInput("Java Language Specification", "https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html#jls-4.5", "test-"+suffix(), "Test-only supplied excerpt; links and original explanation only."),key());
    }
    private ChunkInput chunkInput(UUID id,int revision,int base,UUID concept,Source source,Capture capture,String body) {
        return new ChunkInput(id,revision,base,concept,"PECS and type safety",body,List.of(new SourceReference(source.id(),"JLS 4.5",capture.id())));
    }
    private ExerciseInput exerciseInput(UUID id,int revision,int base,UUID family,UUID concept,Chunk chunk) {
        return new ExerciseInput(id,revision,base,family,"SINGLE",1,"Which declaration preserves generic type safety?",List.of(concept),answer("A"),List.of(new ContentRef(chunk.id(),chunk.revision())));
    }
    private ChoiceAnswer answer(String correct) {
        return new ChoiceAnswer(List.of(new ChoiceOption("A","List<?>"),new ChoiceOption("B","raw List")),List.of(correct),"EDITOR_ONLY_EXPLANATION: an unbounded wildcard preserves the generic type boundary.",List.of("RAW_TYPE"));
    }
    private Review approve(String kind,UUID id,int revision,String hash) {
        return drafts.review(reviewer.userId(),new ReviewInput(kind,id,revision,hash,"APPROVED","Test fixture reviewer checked exact source, rule, boundary and answer; this is not a production human review."),key());
    }
    private Release build(Prepared prepared) {
        return releases.create(author.userId(),new ReleaseInput("formal-test-"+suffix(),List.of(new ContentRef(prepared.chunk().id(),prepared.chunk().revision())),List.of(new ContentRef(prepared.exercise().id(),prepared.exercise().revision()))),key());
    }
    private Release publish(Release release) {
        return releases.publish(author.userId(),release.id(),new PublishInput(release.manifestHash(),release.activeRevision()),key());
    }
    private MockHttpSession login(IdentityService.Session identity) throws Exception {
        return io.typeforge.HttpSessionFixture.login(mvc,json,identity.username(),PASSWORD);
    }
    private JsonNode create(MockHttpSession session,String path,Object body) throws Exception {
        return json.tree(mvc.perform(post("/api/v1/admin"+path).session(session).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(json.write(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private static UUID id(JsonNode value) { return UUID.fromString(value.path("id").asText()); }
    private static String suffix() { return UUID.randomUUID().toString().replace("-","").substring(0,16); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static void conflict(Runnable action) { assertEquals(409,assertThrows(DomainException.class,action::run).status()); }

    @Test void actualSessionsPublishAndLearnersReadSearchAndGradeWithoutAnswerLeak() throws Exception {
        MockHttpSession editorSession=login(author),reviewSession=login(reviewer),studentSession=login(learner);
        UUID conceptId=UUID.randomUUID();
        create(editorSession,"/concepts",new ConceptInput(conceptId,"H"+suffix().substring(0,10).toUpperCase(java.util.Locale.ROOT),"HTTP generics",List.of()));
        JsonNode source=create(editorSession,"/sources",new SourceInput("JLS HTTP fixture","https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html","http-"+suffix(),"Test-only manually supplied source; no full document copied."));
        JsonNode capture=create(editorSession,"/sources/"+id(source)+"/imports",new CaptureInput("Actual supplied source text","PECS producer extends consumer super.","Manually cleaned in isolated test."));
        JsonNode chunk=create(editorSession,"/chunks",new ChunkInput(UUID.randomUUID(),1,0,conceptId,"PECS HTTP", "PECS producer extends consumer super; HTTP marker "+suffix(),List.of(new SourceReference(id(source),"JLS 4.5",id(capture)))));
        ReviewInput chunkReview=new ReviewInput("CHUNK",id(chunk),1,chunk.path("reviewHash").asText(),"APPROVED","Test-only exact content review.");
        mvc.perform(post("/api/v1/admin/reviews").session(editorSession).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(json.write(chunkReview))).andExpect(status().isForbidden());
        create(reviewSession,"/reviews",chunkReview);
        JsonNode question=create(editorSession,"/exercises",new ExerciseInput(UUID.randomUUID(),1,0,UUID.randomUUID(),"SINGLE",1,"Choose a type-safe declaration",List.of(conceptId),answer("A"),List.of(new ContentRef(id(chunk),1))));
        create(reviewSession,"/reviews",new ReviewInput("EXERCISE",id(question),1,question.path("reviewHash").asText(),"APPROVED","Test-only exact answer and evidence review."));
        JsonNode release=create(editorSession,"/releases",new ReleaseInput("http-"+suffix(),List.of(new ContentRef(id(chunk),1)),List.of(new ContentRef(id(question),1))));
        mvc.perform(post("/api/v1/admin/releases/"+id(release)+"/publish").session(editorSession).with(csrf()).header("Idempotency-Key",key()).contentType("application/json")
            .content(json.write(new PublishInput(release.path("manifestHash").asText(),release.path("activeRevision").asInt())))).andExpect(status().isOk());
        String publicQuestion=mvc.perform(get("/api/v1/exercises/"+id(question)).session(studentSession).param("releaseId",id(release).toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for(String secret:List.of("optionIds","answerSpec","misconceptions","EDITOR_ONLY_EXPLANATION","reviewerId")) assertFalse(publicQuestion.contains(secret));
        var result=json.tree(mvc.perform(get("/api/v1/knowledge/search").session(studentSession).param("releaseId",id(release).toString()).param("q","PECS")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(id(chunk).toString(),result.path("items").get(0).path("chunkId").asText());
        var workspace=workspaces.create(learner.userId(),id(release),"Pinned formal course","ON_DEMAND",key());
        var attempt=learning.submit(learner.userId(),workspace.id(),id(release),id(question),1,json.tree("{\"kind\":\"CHOICE\",\"optionIds\":[\"A\"]}"),key());
        assertEquals(0,new java.math.BigDecimal("100.00").compareTo(attempt.score()));
        assertEquals("PUBLISHED",releases.get(author.userId(),id(release)).status());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE target_id IN (?,?)",Integer.class,id(chunk).toString(),id(question).toString()));
    }

    @Test void registrationAndBodyFieldsCannotGrantEditorialPrivilegeAndCsrfIsRequired() throws Exception {
        assertEquals(List.of("LEARNER"),identities.session(learner.username()).roles());
        MockHttpSession student=login(learner),editor=login(author);
        mvc.perform(get("/api/v1/admin/sources").session(student)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/sources").session(editor).contentType("application/json").header("Idempotency-Key",key()).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType("application/json").content(json.write(Map.of("username","forged-"+suffix(),"password",PASSWORD,"roles",List.of("ADMIN"))))).andExpect(status().isBadRequest());
        assertEquals(403,assertThrows(DomainException.class,()->ingestion.sources(learner.userId(),10)).status());
        mvc.perform(get("/api/v1/admin/sources")).andExpect(status().isUnauthorized());
    }

    @Test void codeRubricsRequireIndependentReviewAndNeverLeakFromPublicExercise() throws Exception {
        var prepared=prepare(false);
        var rules=List.of(new CodeRubric.Rule("static void verify(){ Integer x=Main.first(java.util.List.of(1)); }",true,List.of()),
            new CodeRubric.Rule("static void verify(){ String x=Main.first(java.util.List.of(1)); }",false,List.of("compiler.err.prob.found.req")));
        var answer=new ChoiceAnswer(List.of(),List.of(),"Type contract only; runtime behavior is outside this rubric.",List.of("TYPE_ARGUMENT_LINK"),rules,List.of("Check input/output type relationships."));
        var input=new ExerciseInput(UUID.randomUUID(),1,0,UUID.randomUUID(),"SIGNATURE",2,"Write Main.first to retain the element type",List.of(prepared.concept().id()),answer,List.of(new ContentRef(prepared.chunk().id(),1)));
        var editor=login(author);var created=create(editor,"/exercises",input);assertEquals(2,created.path("answerSpec").path("compileChecks").size());
        var question=drafts.exercise(author.userId(),id(created),1);
        assertEquals(403,assertThrows(DomainException.class,()->drafts.review(author.userId(),new ReviewInput("EXERCISE",question.id(),1,question.reviewHash(),"APPROVED","Cannot self-review code rules"),key())).status());
        approve("EXERCISE",question.id(),1,question.reviewHash());
        var release=publish(releases.create(author.userId(),new ReleaseInput("code-"+suffix(),List.of(new ContentRef(prepared.chunk().id(),1)),List.of(new ContentRef(question.id(),1))),key()));
        var student=login(learner);
        String exposed=mvc.perform(get("/api/v1/exercises/"+question.id()).session(student).param("releaseId",release.id().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for(String secret:List.of("compileChecks","expectedCompiled","compiler.err","static void verify","Type contract only","Check input/output"))assertFalse(exposed.contains(secret));
        assertTrue(json.tree(exposed).path("options").isEmpty());
        var singleCheck=new ChoiceAnswer(List.of(),List.of(),"Invalid success-only grading",List.of(),List.of(rules.get(0)),List.of());
        assertEquals(400,assertThrows(DomainException.class,()->drafts.createExercise(author.userId(),new ExerciseInput(UUID.randomUUID(),1,0,UUID.randomUUID(),"SIGNATURE",1,"Invalid rubric",List.of(prepared.concept().id()),singleCheck,List.of(new ContentRef(prepared.chunk().id(),1))),key())).status());
    }

    @Test void staleAuthenticatedEditorSessionCannotIgnoreRoleRevocation() throws Exception {
        MockHttpSession session=login(author);
        mvc.perform(get("/api/v1/admin/sources").session(session)).andExpect(status().isOk());
        jdbc.update("UPDATE app_user SET roles='[\"LEARNER\"]' WHERE id=?",author.userId().toString());
        mvc.perform(get("/api/v1/admin/sources").session(session)).andExpect(status().isForbidden());
        assertEquals(403,assertThrows(DomainException.class,()->ingestion.sources(author.userId(),10)).status());
    }

    @Test void manualCaptureComputesHashesPreservesCodeIndentationAndDeduplicates() {
        Source source=source();
        CaptureInput input=new CaptureInput("Original\r\n    List<T> code;","Cleaned\r\n    List<T> code;","Manually removed navigation; preserved code.");
        String command=key();Capture first=ingestion.capture(author.userId(),source.id(),input,command);
        Capture replay=ingestion.capture(author.userId(),source.id(),input,command);
        Capture duplicate=ingestion.capture(reviewer.userId(),source.id(),input,key());
        assertEquals(first.id(),replay.id());assertEquals(first.id(),duplicate.id());
        assertEquals("Original\n    List<T> code;",first.originalMarkdown());assertEquals(json.sha256(first.originalMarkdown()),first.originalHash());
        assertEquals(1,ingestion.source(author.userId(),source.id()).captureCount());
        assertEquals(first.originalHash(),jdbc.queryForObject("SELECT raw_hash FROM knowledge_source WHERE id=?",String.class,source.id().toString()));
        conflict(()->ingestion.capture(author.userId(),source.id(),new CaptureInput("different","different","different"),command));
        assertThrows(DomainException.class,()->ingestion.register(author.userId(),new SourceInput("bad","javascript:alert(1)","v1","usage"),key()));
        assertThrows(DomainException.class,()->ingestion.register(author.userId(),new SourceInput("bad","https://user:secret@example.org/","v1","usage"),key()));
    }

    @Test void aCapturedReceiptCannotBeReboundToADifferentSource() {
        Prepared prepared=prepare(false);Source other=source();
        ChunkInput input=chunkInput(UUID.randomUUID(),1,0,prepared.concept().id(),other,prepared.capture(),"distinct mismatched text "+suffix());
        assertEquals(400,assertThrows(DomainException.class,()->drafts.createChunk(author.userId(),input,key())).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk WHERE id=?",Integer.class,input.id().toString()));
    }

    @Test void authorCannotApproveOwnExerciseAndRejectedRevisionRequiresNewVersion() {
        Prepared prepared=prepare(false);Exercise exercise=prepared.exercise();
        ReviewInput own=new ReviewInput("EXERCISE",exercise.id(),1,exercise.reviewHash(),"APPROVED","Self approval attempt.");
        assertEquals(403,assertThrows(DomainException.class,()->drafts.review(author.userId(),own,key())).status());
        drafts.review(reviewer.userId(),new ReviewInput("EXERCISE",exercise.id(),1,exercise.reviewHash(),"REJECTED","Needs a better distractor."),key());
        conflict(()->approve("EXERCISE",exercise.id(),1,exercise.reviewHash()));
        ExerciseInput next=exerciseInput(exercise.id(),2,1,exercise.familyId(),prepared.concept().id(),prepared.chunk());
        Exercise revised=drafts.createExercise(author.userId(),next,key());
        assertEquals("DRAFT",revised.status());assertEquals("RETIRED",drafts.exercise(author.userId(),exercise.id(),1).status());
        conflict(()->drafts.createExercise(author.userId(),next,key()));
        ExerciseInput wrongFamily=exerciseInput(exercise.id(),3,2,UUID.randomUUID(),prepared.concept().id(),prepared.chunk());
        conflict(()->drafts.createExercise(author.userId(),wrongFamily,key()));
    }

    @Test void reviewPinsTitleAndPrivateAnswerInsteadOfOnlyTheKnowledgeBody() {
        Prepared prepared=prepare(false);
        var input=chunkInput(UUID.randomUUID(),1,0,prepared.concept().id(),prepared.source(),prepared.capture(),"Unreviewed independent text "+suffix());
        Chunk preview=drafts.createChunk(author.userId(),input,key());
        jdbc.update("UPDATE knowledge_chunk SET title='changed after preview' WHERE id=? AND revision=1",preview.id().toString());
        conflict(()->approve("CHUNK",preview.id(),1,preview.reviewHash()));
        jdbc.update("UPDATE exercise SET answer_spec=? WHERE id=? AND revision=1",json.write(answer("B")),prepared.exercise().id().toString());
        conflict(()->approve("EXERCISE",prepared.exercise().id(),1,prepared.exercise().reviewHash()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE target_id IN (?,?)",Integer.class,preview.id().toString(),prepared.exercise().id().toString()));
    }

    @Test void demoReviewedFlagsDoNotSatisfyFormalIndependentReview() {
        new DemoContentInitializer(jdbc,tx,json,clock).initialize();
        UUID demo=DemoContentInitializer.RELEASE_ID;
        var card=curriculum.chunks(demo,List.of()).get(0);
        var exercise=curriculum.exercises(demo,null,null,1).items().get(0);
        assertEquals(exercise.id(),drafts.exercise(author.userId(),exercise.id(),exercise.revision()).id(),"Known demo provenance may be previewed but never counts as formal review");
        String version="no-demo-"+suffix();
        conflict(()->releases.create(author.userId(),new ReleaseInput(version,List.of(new ContentRef(card.chunkId(),card.revision())),List.of(new ContentRef(exercise.id(),exercise.revision()))),key()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_release WHERE version=?",Integer.class,version));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_review WHERE target_id=?",Integer.class,card.chunkId().toString()));
    }

    @Test void malformedStoredAnswerFailsAsAControlledEditorialConflict() {
        Prepared prepared=prepare(false);
        var malformed=(com.fasterxml.jackson.databind.node.ObjectNode)json.tree(json.write(answer("A")));
        malformed.put("unconfiguredHiddenExecutor","must-not-be-interpreted");
        jdbc.update("UPDATE exercise SET answer_spec=? WHERE id=? AND revision=1",json.write(malformed),prepared.exercise().id().toString());
        DomainException error=assertThrows(DomainException.class,()->drafts.exercise(author.userId(),prepared.exercise().id(),1));
        assertEquals(409,error.status());assertFalse(error.getMessage().contains("must-not-be-interpreted"));
        jdbc.update("UPDATE exercise SET answer_spec='{}' WHERE id=? AND revision=1",prepared.exercise().id().toString());
        assertEquals(409,assertThrows(DomainException.class,()->drafts.exercise(author.userId(),prepared.exercise().id(),1)).status());
    }

    @Test void buildingRequiresApprovedContentAndCompletePrerequisiteKnowledge() {
        Prepared prepared=prepare(false);
        conflict(()->build(prepared));
        approve("EXERCISE",prepared.exercise().id(),1,prepared.exercise().reviewHash());
        Concept child=drafts.createConcept(author.userId(),new ConceptInput(UUID.randomUUID(),"P"+suffix().substring(0,10).toUpperCase(java.util.Locale.ROOT),"Dependent concept",List.of(prepared.concept().id())),key());
        Chunk childChunk=drafts.createChunk(author.userId(),chunkInput(UUID.randomUUID(),1,0,child.id(),prepared.source(),prepared.capture(),"Dependent concept PECS "+suffix()),key());
        approve("CHUNK",childChunk.id(),1,childChunk.reviewHash());
        Exercise childExercise=drafts.createExercise(author.userId(),exerciseInput(UUID.randomUUID(),1,0,UUID.randomUUID(),child.id(),childChunk),key());
        approve("EXERCISE",childExercise.id(),1,childExercise.reviewHash());
        conflict(()->releases.create(author.userId(),new ReleaseInput("missing-prereq-"+suffix(),List.of(new ContentRef(childChunk.id(),1)),List.of(new ContentRef(childExercise.id(),1))),key()));
        Release complete=releases.create(author.userId(),new ReleaseInput("prereq-"+suffix(),List.of(new ContentRef(prepared.chunk().id(),1),new ContentRef(childChunk.id(),1)),List.of(new ContentRef(childExercise.id(),1))),key());
        assertEquals(2,complete.manifest().path("concepts").size());
    }

    @Test void publicationCasHistoryAndRetryPreserveExistingPersonalReleasePins() {
        Prepared prepared=prepare(true);Release first=publish(build(prepared));
        var workspace=workspaces.create(learner.userId(),first.id(),"Pinned first release","QUIET",key());
        Release second=build(prepared);
        conflict(()->releases.publish(author.userId(),second.id(),new PublishInput(second.manifestHash(),second.activeRevision()-1),key()));
        assertEquals(first.id(),curriculum.activeRelease());assertEquals("DRAFT",releases.get(author.userId(),second.id()).status());
        String publishKey=key();PublishInput input=new PublishInput(second.manifestHash(),second.activeRevision());
        Release publishedSecond=releases.publish(author.userId(),second.id(),input,publishKey);
        assertEquals(first.id(),workspaces.get(learner.userId(),workspace.id()).releaseId());
        assertEquals(first.id(),curriculum.chunks(first.id(),List.of()).get(0).releaseId());
        assertFalse(search.search(second.id(),"PECS",null,5).items().isEmpty());
        var restored=releases.activateHistorical(reviewer.userId(),first.id(),new PublishInput(first.manifestHash(),publishedSecond.activeRevision()),key());
        assertTrue(restored.active());assertEquals(first.publishedAt(),restored.publishedAt());
        releases.publish(author.userId(),second.id(),input,publishKey);
        assertEquals(first.id(),curriculum.activeRelease());assertEquals("PUBLISHED",releases.get(author.userId(),second.id()).status());
        assertEquals(first.id(),workspaces.get(learner.userId(),workspace.id()).releaseId());
    }

    @Test void indexAndManifestIntegrityFailuresDoNotPartiallyPublish() {
        Prepared prepared=prepare(true);Release release=build(prepared);
        var before=jdbc.queryForList("SELECT release_id,revision FROM active_release WHERE singleton_id=1");
        jdbc.update("UPDATE index_snapshot SET status='FAILED' WHERE release_id=?",release.id().toString());
        conflict(()->publish(release));
        assertEquals(before,jdbc.queryForList("SELECT release_id,revision FROM active_release WHERE singleton_id=1"));
        assertEquals("DRAFT",releases.get(author.userId(),release.id()).status());
        jdbc.update("UPDATE index_snapshot SET status='READY' WHERE release_id=?",release.id().toString());
        jdbc.update("UPDATE knowledge_source SET usage_note='Changed usage rights' WHERE id=?",prepared.source().id().toString());
        conflict(()->publish(release));
        assertEquals("DRAFT",releases.get(author.userId(),release.id()).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='FORMAL_RELEASE_PUBLISHED' AND resource_id=?",Integer.class,release.id().toString()));
    }

    @Test void concurrentPublishersWithSameActiveRevisionHaveExactlyOneWinner() throws Exception {
        Prepared prepared=prepare(true);Release left=build(prepared),right=build(prepared);
        assertEquals(left.activeRevision(),right.activeRevision());
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->compete(author.userId(),left,ready,start));
            var second=workers.submit(()->compete(reviewer.userId(),right,ready,start));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            assertEquals(1,first.get(15,TimeUnit.SECONDS)+second.get(15,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_release WHERE id IN (?,?) AND status='PUBLISHED'",Integer.class,left.id().toString(),right.id().toString()));
            assertEquals(left.activeRevision()+1,releases.get(author.userId(),left.id()).activeRevision());
        } finally { start.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS)); }
    }
    private int compete(UUID actor,Release release,CountDownLatch ready,CountDownLatch start) throws InterruptedException {
        ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));
        try { releases.publish(actor,release.id(),new PublishInput(release.manifestHash(),release.activeRevision()),key());return 1; }
        catch(DomainException conflict) { assertEquals(409,conflict.status());return 0; }
    }

    @Test void editingPublishedKnowledgeAppendsAndDoesNotChangeAnExistingCourse() {
        Prepared prepared=prepare(true);Release release=publish(build(prepared));
        String original=curriculum.chunks(release.id(),List.of()).get(0).body();
        Chunk revised=drafts.createChunk(reviewer.userId(),chunkInput(prepared.chunk().id(),2,1,prepared.concept().id(),prepared.source(),prepared.capture(),"New PECS revision "+suffix()),key());
        assertEquals("DRAFT",revised.status());assertEquals(original,curriculum.chunks(release.id(),List.of()).get(0).body());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk WHERE id=?",Integer.class,revised.id().toString()));
        conflict(()->releases.create(author.userId(),new ReleaseInput("unreviewed-"+suffix(),List.of(new ContentRef(revised.id(),2)),List.of(new ContentRef(prepared.exercise().id(),1))),key()));
    }
}
