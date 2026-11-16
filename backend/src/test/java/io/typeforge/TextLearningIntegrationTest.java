package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.exercise.LearningService;
import io.typeforge.exercise.TextRubric;
import io.typeforge.identity.IdentityService;
import io.typeforge.ingestion.ManualIngestionService;
import io.typeforge.notebook.NotebookActions;
import io.typeforge.notebook.NotebookService;
import io.typeforge.operations.events.OutboxDispatcher;
import io.typeforge.publishing.ContentDraftService;
import io.typeforge.publishing.ContentWorkbenchModels.*;
import io.typeforge.publishing.ReleaseWorkbenchService;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real formal editorial/review/publish and event storage; the exact matcher makes no semantic claims. */
@SpringBootTest(properties={"typeforge.compiler.enabled=false","typeforge.events.dispatch-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextLearningIntegrationTest {
    @Autowired IdentityService identities;@Autowired ContentDraftService drafts;@Autowired ReleaseWorkbenchService releases;
    @Autowired ManualIngestionService ingestion;@Autowired JdbcTemplate jdbc;@Autowired TransactionTemplate tx;
    @Autowired JsonCodec json;@Autowired SchemaCatalog schemas;@Autowired MockMvc mvc;@Autowired CurriculumService curriculum;
    @Autowired WorkspaceService workspaces;@Autowired LearningService learning;@Autowired OutboxDispatcher outbox;
    @Autowired NotebookService notebooks;@Autowired NotebookActions actions;@Autowired ArtifactService artifacts;
    private LearningEditorialFixture f;private Exercise question,choice;private Release release;
    private IdentityService.Session user;private WorkspaceService.Workspace workspace;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){PrivateModuleDatabaseFixture.configure(properties,"text-learning-v06");}
    @BeforeAll void course() {
        PrivateModuleDatabaseFixture.prove(jdbc,json,getClass().getSimpleName());f=new LearningEditorialFixture(identities,drafts,releases,ingestion,jdbc,tx);
        question=f.approved("READING",UUID.randomUUID(),answer("producer extends",false));choice=f.approved("SINGLE",UUID.randomUUID(),LearningEditorialFixture.choice("A"));release=f.publish(question,choice);
    }
    @AfterAll void restore(){if(f!=null)f.close();}
    @BeforeEach void learner(){user=f.learner();workspace=workspaces.create(user.userId(),release.id(),"Exact short answer","ON_DEMAND",key());}
    private static String key(){return LearningEditorialFixture.key();}
    private JsonNode tree(Object value){return json.tree(json.write(value));}
    private ChoiceAnswer answer(String accepted,boolean sensitive){return new ChoiceAnswer(List.of(),List.of(),"PRIVATE_TEXT_EDITOR_EXPLANATION",List.of("BOUND_DIRECTION"),List.of(),List.of("Name the exact term.","Check the declared normalization.","Keep the answer inside the stated scope."),new TextRubric.Spec(TextRubric.VERSION,"Exact short terminology only; punctuation and word order remain significant.",sensitive,List.of(accepted),List.of("consumer extends")));}
    private LearningService.Attempt submit(String value,String key){return learning.submit(user.userId(),workspace.id(),release.id(),question.id(),1,tree(Map.of("kind","TEXT","value",value)),key);}
    private void drain(){for(int i=0;i<20;i++)if(outbox.dispatchBatch(100)==0)return;fail("Outbox did not settle");}
    private void secretFree(String value){for(String secret:List.of("acceptedAnswers","rejectedAnswers","PRIVATE_TEXT_EDITOR_EXPLANATION","consumer extends","textRubric","reviewerId"))assertFalse(value.contains(secret),secret);}

    @Test void textCapabilityAndPublicPolicyAreAvailableWithoutCompilerAndNeverExposeExamples() throws Exception {
        var session=HttpSessionFixture.login(mvc,json,user.username(),LearningEditorialFixture.PASSWORD);
        var response=mvc.perform(get("/api/v1/exercises/"+question.id()).session(session).param("releaseId",release.id().toString())).andReturn().getResponse();assertEquals(200,response.getStatus(),response.getContentAsString());
        var safe=json.tree(response.getContentAsString());schemas.validate("Exercise",safe);secretFree(response.getContentAsString());assertEquals("TEXT",safe.path("answerKind").asText());assertEquals(TextRubric.VERSION,safe.path("textPolicy").path("version").asText());assertTrue(safe.path("options").isEmpty());
        var capabilityResponse=mvc.perform(get("/api/v1/capabilities").session(session)).andReturn().getResponse();assertEquals(200,capabilityResponse.getStatus());
        var capabilities=json.tree(capabilityResponse.getContentAsString());schemas.validate("Capabilities",capabilities);
        assertTrue(capabilities.path("enabled").toString().contains("TEXT_GRADING"));assertTrue(capabilities.path("enabled").toString().contains("NOTEBOOK_INTERACTIONS"));assertFalse(capabilities.path("enabled").toString().contains("COMPILE_ONLY"));
        assertEquals("CHOICE",curriculum.exercise(release.id(),choice.id(),1).answerKind());assertNull(curriculum.exercise(release.id(),choice.id(),1).textPolicy());
    }
    @Test void realHttpNormalizedAnswerPinsGraderReceiptAndExactlyOneOutboxProjection() throws Exception {
        var session=HttpSessionFixture.login(mvc,json,user.username(),LearningEditorialFixture.PASSWORD);String idem=key();var input=tree(Map.of("workspaceId",workspace.id(),"releaseId",release.id(),"exerciseId",question.id(),"exerciseRevision",1,"answer",Map.of("kind","TEXT","value","\tＰＲＯＤＵＣＥＲ\u00a0extends\n")));
        var response=mvc.perform(post("/api/v1/attempts").session(session).with(csrf()).header("Idempotency-Key",idem).contentType("application/json").content(input.toString())).andReturn().getResponse();assertEquals(201,response.getStatus(),response.getContentAsString());
        var result=json.tree(response.getContentAsString());schemas.validate("Attempt",result);secretFree(response.getContentAsString());assertEquals(100,result.path("score").asInt());assertEquals(0,result.path("hintLevel").asInt());assertEquals(TextRubric.VERSION,result.path("graderVersion").asText());
        UUID attempt=UUID.fromString(result.path("id").asText());assertEquals(attempt,learning.submit(user.userId(),workspace.id(),release.id(),question.id(),1,input.path("answer"),idem).id());
        var privateFeedback=json.tree(jdbc.queryForObject("SELECT feedback FROM exercise_attempt WHERE id=?",String.class,attempt.toString()));assertEquals("NORMALIZED_EXACT",privateFeedback.path("evaluationKind").asText());assertEquals(json.hash(tree(question.answerSpec().textRubric())),privateFeedback.path("rubricHash").asText());
        drain();assertEquals(1,learning.progress(user.userId()).items().get(0).independentSamples());assertEquals(1,learning.list(user.userId(),workspace.id()).size());assertTrue(learning.reviews(user.userId()).isEmpty());drain();assertEquals(1,learning.progress(user.userId()).items().get(0).independentSamples());
    }
    @Test void plausibleLongerMeaningIsNotInventedAsExactCorrectnessAndCreatesRealNotebook() {
        var original=submit("producer extends because of covariance",key());assertEquals(0,original.score().intValue());assertTrue(original.feedback().contains("不进行语义推断"));drain();assertEquals(1,learning.reviews(user.userId()).size());
        var artifact=notebooks.build(user.userId(),workspace.id(),List.of(original.id()),"Short answer repair",key());var version=artifacts.version(user.userId(),artifact.id(),1);schemas.validate("ArtifactRevision",version);var block=version.content().path("blocks").get(0);
        assertEquals("TEXT",block.path("config").path("answerKind").asText());assertEquals(original.answer().path("value").asText(),block.path("config").path("originalTextAnswer").asText());secretFree(version.content().toString());
        var input=tree(Map.of("artifactRevision",1,"blockId",block.path("id").asText(),"expectedBlockRevision",1,"action","submitAnswer","answer",Map.of("kind","TEXT","value","producer extends"),"selection",""));
        var result=actions.dispatch(user.userId(),artifact.id(),input,key());assertEquals(100,learning.get(user.userId(),result.attemptId()).score().intValue());assertEquals(4,learning.get(user.userId(),result.attemptId()).hintLevel());drain();assertEquals(1,learning.progress(user.userId()).items().get(0).independentSamples());
    }
    @Test void threeHintsMarkFirstAnswerAssistedAndSurviveCrossWorkspaceSameFamily() {
        for(int level=1;level<=3;level++){String idem=key();var hint=learning.hint(user.userId(),workspace.id(),release.id(),question.id(),1,level,idem);assertEquals(level,hint.level());assertEquals(hint,learning.hint(user.userId(),workspace.id(),release.id(),question.id(),1,level,idem));secretFree(json.write(hint));}
        var other=workspaces.create(user.userId(),release.id(),"Same learner second space","ON_DEMAND",key());var attempt=learning.submit(user.userId(),other.id(),release.id(),question.id(),1,tree(Map.of("kind","TEXT","value","producer extends")),key());assertEquals(3,attempt.hintLevel());drain();assertTrue(learning.progress(user.userId()).items().stream().allMatch(p->p.independentSamples()==0));
    }
    @Test void malformedAndMismatchedAnswersCreateNeitherAttemptsNorMasteryEvents() {
        for(String value:List.of(" ","A\u200bB","\uD800","字".repeat(4001)))assertEquals(400,assertThrows(DomainException.class,()->submit(value,key())).status());
        assertEquals(400,assertThrows(DomainException.class,()->learning.submit(user.userId(),workspace.id(),release.id(),question.id(),1,tree(Map.of("kind","CHOICE","optionIds",List.of("A"))),key())).status());
        assertEquals(400,assertThrows(DomainException.class,()->learning.submit(user.userId(),workspace.id(),release.id(),choice.id(),1,tree(Map.of("kind","TEXT","value","A")),key())).status());
        assertTrue(learning.list(user.userId(),workspace.id()).isEmpty());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event WHERE workspace_id=? AND event_type='ATTEMPT_GRADED'",Integer.class,workspace.id().toString()));
    }
    @Test void idempotencyRejectsChangedTextAndPrivateAttemptCannotCrossAccounts() {
        String idem=key();var first=submit("producer extends",idem);assertEquals(first,submit("producer extends",idem));assertEquals(409,assertThrows(DomainException.class,()->submit("consumer extends",idem)).status());
        assertEquals(404,assertThrows(DomainException.class,()->learning.get(f.learner().userId(),first.id())).status());assertEquals(1,learning.list(user.userId(),workspace.id()).size());
    }
    @Test void independentApprovalAndCalibrationAreRequiredBeforePublication() {
        var draft=f.draft("DIAGNOSIS",UUID.randomUUID(),answer("invariant",true));assertEquals(403,assertThrows(DomainException.class,()->drafts.review(f.author.userId(),new ReviewInput("EXERCISE",draft.id(),1,draft.reviewHash(),"APPROVED","Author cannot review own rule"),key())).status());
        assertEquals(409,assertThrows(DomainException.class,()->f.publish(draft)).status());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_text_review_receipt WHERE exercise_id=?",Integer.class,draft.id().toString()));
        f.approve("EXERCISE",draft.id(),1,draft.reviewHash());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_text_review_receipt WHERE exercise_id=?",Integer.class,draft.id().toString()));assertEquals("PUBLISHED",f.publish(drafts.exercise(f.author.userId(),draft.id(),1)).status());
        var invalid=new ChoiceAnswer(List.of(),List.of(),"private",List.of(),List.of(),List.of(),new TextRubric.Spec(TextRubric.VERSION,"exact",false,List.of("A"),List.of("ａ")));
        assertEquals(400,assertThrows(DomainException.class,()->f.draft("READING",UUID.randomUUID(),invalid)).status());assertEquals(400,assertThrows(DomainException.class,()->f.draft("SINGLE",UUID.randomUUID(),answer("A",true))).status());
    }
    @Test void changedReviewedRuleCannotGradeOrPublishUnderItsOldAttestation() {
        var extra=f.approved("READING",UUID.randomUUID(),answer("invariant",true));var course=f.publish(extra);var own=workspaces.create(user.userId(),course.id(),"Tamper boundary","ON_DEMAND",key());
        String raw=jdbc.queryForObject("SELECT answer_spec FROM exercise WHERE id=? AND revision=1",String.class,extra.id().toString());var modified=(com.fasterxml.jackson.databind.node.ObjectNode)json.tree(raw);((com.fasterxml.jackson.databind.node.ObjectNode)modified.path("textRubric")).set("acceptedAnswers",tree(List.of("tampered")));
        jdbc.update("UPDATE exercise SET answer_spec=? WHERE id=? AND revision=1",json.write(modified),extra.id().toString());
        try {assertEquals("INVALID_PUBLISHED_EXERCISE",assertThrows(DomainException.class,()->learning.submit(user.userId(),own.id(),course.id(),extra.id(),1,tree(Map.of("kind","TEXT","value","tampered")),key())).code());assertTrue(learning.list(user.userId(),own.id()).isEmpty());assertEquals(409,assertThrows(DomainException.class,()->f.publish(extra)).status());}
        finally {jdbc.update("UPDATE exercise SET answer_spec=? WHERE id=? AND revision=1",raw,extra.id().toString());}
    }
    @Test void rubricRevisionNeverRegradesHistoricalAnswerOrMigratesPinnedWorkspace() {
        var first=submit("producer extends",key());String evidence=first.evidenceRef().contentHash();
        var revision=drafts.createExercise(f.author.userId(),new ExerciseInput(question.id(),2,1,question.familyId(),"READING",1,question.prompt(),question.conceptIds(),answer("producer super",false),question.knowledgeRefs()),key());f.approve("EXERCISE",revision.id(),2,revision.reviewHash());var course=f.publish(drafts.exercise(f.author.userId(),revision.id(),2));
        assertEquals(release.id(),workspaces.get(user.userId(),workspace.id()).releaseId());assertEquals(100,learning.get(user.userId(),first.id()).score().intValue());assertEquals(evidence,learning.get(user.userId(),first.id()).evidenceRef().contentHash());
        assertEquals(404,assertThrows(DomainException.class,()->learning.submit(user.userId(),workspace.id(),release.id(),question.id(),2,tree(Map.of("kind","TEXT","value","producer super")),key())).status());
        var newer=workspaces.create(user.userId(),course.id(),"Pinned revision 2","ON_DEMAND",key());var latest=learning.submit(user.userId(),newer.id(),course.id(),question.id(),2,tree(Map.of("kind","TEXT","value","producer super")),key());assertEquals(100,latest.score().intValue());assertEquals(4,latest.hintLevel());assertEquals(2,latest.exerciseRevision());
    }
    @Test void validLongPublishedPromptRemainsCompleteInTheCorrectionNotebook() {
        String prompt="Exact answer scope. "+"题".repeat(15900);
        var draft=drafts.createExercise(f.author.userId(),new ExerciseInput(UUID.randomUUID(),1,0,UUID.randomUUID(),"READING",1,prompt,List.of(f.concept.id()),answer("invariant",true),List.of(new ContentRef(f.chunk.id(),1))),key());
        f.approve("EXERCISE",draft.id(),1,draft.reviewHash());var course=f.publish(drafts.exercise(f.author.userId(),draft.id(),1));var pinned=workspaces.create(user.userId(),course.id(),"Long source prompt","ON_DEMAND",key());
        var attempt=learning.submit(user.userId(),pinned.id(),course.id(),draft.id(),1,tree(Map.of("kind","TEXT","value","wrong")),key());var artifact=notebooks.build(user.userId(),pinned.id(),List.of(attempt.id()),"Complete original question",key());
        var source=artifacts.version(user.userId(),artifact.id(),1);schemas.validate("ArtifactRevision",source);assertEquals(prompt,source.content().path("blocks").get(0).path("prompt").asText());
    }
}
