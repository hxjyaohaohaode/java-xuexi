package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.compiler.CodeRubric;
import io.typeforge.compiler.CompileProtocol;
import io.typeforge.compiler.CompileService;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.exercise.LearningService;
import io.typeforge.identity.IdentityService;
import io.typeforge.ingestion.ManualIngestionService;
import io.typeforge.notebook.NotebookActions;
import io.typeforge.notebook.NotebookInteractionService;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** Actual services and transactional SQL. Supplied compiler outcomes below are protocol fixtures, not Docker isolation evidence. */
@SpringBootTest(properties={"typeforge.events.dispatch-enabled=false","typeforge.compiler.enabled=true","typeforge.compiler.worker-url=http://127.0.0.1:9/v1/compile","typeforge.compiler.worker-token=fixture-compiler-secret-at-least-32-characters","typeforge.compiler.dispatch-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotebookInteractionIntegrationTest {
    @Autowired IdentityService identities;@Autowired ContentDraftService drafts;@Autowired ReleaseWorkbenchService releases;@Autowired ManualIngestionService ingestion;
    @Autowired JdbcTemplate jdbc;@Autowired TransactionTemplate tx;@Autowired JsonCodec json;@Autowired SchemaCatalog schemas;@Autowired MockMvc mvc;
    @Autowired WorkspaceService workspaces;@Autowired LearningService learning;@Autowired OutboxDispatcher outbox;@Autowired NotebookService notebooks;
    @Autowired NotebookActions actions;@Autowired NotebookInteractionService interactions;@Autowired ArtifactService artifacts;@Autowired CompileService compiler;
    private LearningEditorialFixture f;private Exercise question,variant,variantTwo,outsider,code;private Release release;
    private IdentityService.Session user;private WorkspaceService.Workspace workspace;private LearningService.Attempt original;
    private ArtifactService.Artifact notebook;private ArtifactService.Version version;private String origin;
    private static final String ORIGINAL="public class Main {}",REPAIRED="public class Main { public static <T> T first(java.util.List<T> values) { return values.get(0); } }";
    private static final List<CodeRubric.Rule> RULES=List.of(new CodeRubric.Rule("static void verify(){ Integer x=Main.first(java.util.List.of(1)); }",true,List.of()),new CodeRubric.Rule("static void verify(){ String x=Main.first(java.util.List.of(1)); }",false,List.of("compiler.err.prob.found.req")));
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){PrivateModuleDatabaseFixture.configure(properties,"notebook-interactions-v06");}
    @BeforeAll void course(){PrivateModuleDatabaseFixture.prove(jdbc,json,getClass().getSimpleName());f=new LearningEditorialFixture(identities,drafts,releases,ingestion,jdbc,tx);UUID family=UUID.randomUUID();question=f.approved("SINGLE",family,LearningEditorialFixture.choice("A"));variant=f.approved("SINGLE",family,LearningEditorialFixture.choice("B"));variantTwo=f.approved("SINGLE",family,LearningEditorialFixture.choice("A"));outsider=f.approved("SINGLE",UUID.randomUUID(),LearningEditorialFixture.choice("A"));code=f.approved("SIGNATURE",UUID.randomUUID(),new ChoiceAnswer(List.of(),List.of(),"Type relationship, compile only.",List.of("RETURN_TYPE"),RULES,List.of("Name the type variable.")));release=f.publish(question,variant,variantTwo,outsider,code);}
    @AfterAll void restore(){if(f!=null)f.close();}
    @BeforeEach void source(){user=f.learner();workspace=workspaces.create(user.userId(),release.id(),"Typed notebook interactions","ON_DEMAND",key());original=learning.submit(user.userId(),workspace.id(),release.id(),question.id(),1,tree(Map.of("kind","CHOICE","optionIds",List.of("B"))),key());drain();notebook=notebooks.build(user.userId(),workspace.id(),List.of(original.id()),"Actual error notebook",key());version=artifacts.version(user.userId(),notebook.id(),1);origin=version.content().path("blocks").get(0).path("id").asText();}
    @AfterEach void queues(){if(user==null)return;for(var job:compiler.list(user.userId(),workspace.id()))if(Set.of("QUEUED","RUNNING").contains(job.status()))compiler.cancel(user.userId(),job.id(),key());drain();}
    private static String key(){return LearningEditorialFixture.key();}private JsonNode tree(Object value){return json.tree(json.write(value));}
    private void drain(){for(int i=0;i<30;i++)if(outbox.dispatchBatch(100)==0)return;fail("Outbox did not settle");}
    private JsonNode append(String kind,JsonNode config){ObjectNode input=(ObjectNode)tree(Map.of("artifactRevision",version.revision(),"originBlockId",origin,"kind",kind));if(config!=null)input.set("config",config);version=interactions.append(user.userId(),notebook.id(),input,key());return version.content().path("blocks").get(version.content().path("blocks").size()-1);}
    private ObjectNode input(JsonNode block,String action,int state){var value=(ObjectNode)tree(Map.of("artifactRevision",version.revision(),"blockId",block.path("id").asText(),"expectedBlockRevision",block.path("revision").asInt(),"expectedStateRevision",state,"action",action,"selection",""));value.putNull("answer");return value;}
    private NotebookActions.Result act(JsonNode block,String action,int state){return actions.dispatch(user.userId(),notebook.id(),input(block,action,state),key());}
    private ObjectNode flowInput(JsonNode block,int state,String target,String prediction){var input=input(block,"selectType",state);input.set("typeInput",tree(Map.of("sourceType","List<Integer>","targetType",target,"operation","ASSIGN","prediction",prediction)));return input;}
    private Map<String,Object> ref(Exercise exercise){return Map.of("id",exercise.id(),"revision",exercise.revision());}
    private JsonNode comparison(){return tree(Map.of("baseExerciseRef",ref(question),"changeDimension","WILDCARD","candidateExerciseRefs",List.of(ref(variant),ref(variantTwo))));}
    private NotebookInteractionService.BlockState state(JsonNode block){return interactions.state(user.userId(),notebook.id(),version.revision()).blocks().stream().filter(s->s.blockId().equals(block.path("id").asText())).findFirst().orElseThrow();}
    private ArtifactService.Artifact document(UUID owner,UUID workspace,String text){var value=artifacts.create(owner,workspace,"DOCUMENT","Source document",null,key());artifacts.save(owner,value.id(),0,tree(Map.of("kind","DOCUMENT","schemaVersion","1.0","title","Source document","blocks",List.of(Map.of("id","body","kind","PARAGRAPH","text",text,"evidenceRefs",List.of())))),List.of());return artifacts.get(owner,value.id());}
    private JsonNode repair(){var attempt=learning.submit(user.userId(),workspace.id(),release.id(),code.id(),1,tree(Map.of("kind","CODE","source",ORIGINAL)),key());var ticket=compiler.claimNext();assertNotNull(ticket);assertEquals(attempt.jobId(),ticket.id());var failed=List.of(new CompileProtocol.CheckResult("check0","COMPILE_ERROR",List.of("compiler.err.cant.resolve.location"),true),new CompileProtocol.CheckResult("check1","COMPILE_ERROR",List.of("compiler.err.cant.resolve.location"),true));assertTrue(compiler.accept(ticket,new CompileProtocol.Result("SUCCEEDED",ticket.request().sourceHash(),"javac 17.0.21",List.of(),false,failed)));drain();assertEquals(0,learning.get(user.userId(),attempt.id()).score().intValue());notebook=notebooks.build(user.userId(),workspace.id(),List.of(attempt.id()),"Code repair source",key());version=artifacts.version(user.userId(),notebook.id(),1);origin=version.content().path("blocks").get(0).path("id").asText();return append("REPAIR_DIFF",null);}

    @Test void optionsExposeOnlyPublishedFamilyVariantsAndActualReviewSources() {
        var options=interactions.options(user.userId(),notebook.id(),1,origin);schemas.validate("NotebookBlockOptions",options);assertEquals(Set.of(variant.id(),variantTwo.id()),Set.copyOf(options.counterfactualCandidates().stream().map(CurriculumService.ExerciseView::id).toList()));assertTrue(options.availableKinds().containsAll(List.of("TYPE_FLOW","EXPERT_HELP","COUNTERFACTUAL","REVIEW_LINKS")));assertFalse(options.availableKinds().contains("REPAIR_DIFF"));assertEquals(1,options.reviewIds().size());
        for(String secret:List.of("optionIds","answerSpec","familyId","EDITOR_PRIVATE_EXPLANATION"))assertFalse(json.write(options).contains(secret));assertEquals(404,assertThrows(DomainException.class,()->interactions.options(f.learner().userId(),notebook.id(),1,origin)).status());
    }
    @Test void typeFlowUsesRealLabResultsAndDurableCasWithoutMasteryInflation() {
        var block=append("TYPE_FLOW",null);var request=flowInput(block,0,"List<Number>","ALLOWED");String idem=key();var result=actions.dispatch(user.userId(),notebook.id(),request,idem);schemas.validate("NotebookActionResult",result);assertEquals("DENIED",result.labSession().verdict());assertEquals(false,result.labSession().predictionCorrect());assertEquals(1,result.stateRevision());assertEquals(result,actions.dispatch(user.userId(),notebook.id(),request,idem));
        assertEquals(409,assertThrows(DomainException.class,()->actions.dispatch(user.userId(),notebook.id(),request,key())).status());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM workspace_lab WHERE workspace_id=?",Integer.class,workspace.id().toString()));
        var parameter=input(block,"setParameter",1);parameter.set("parameterInput",tree(Map.of("name","targetType","value","List<? extends Number>","operation","ASSIGN","prediction","ALLOWED")));var changed=actions.dispatch(user.userId(),notebook.id(),parameter,key());assertEquals("ALLOWED",changed.labSession().verdict());assertEquals(true,changed.labSession().predictionCorrect());
        var state=state(block);assertEquals(2,state.stateRevision());assertEquals("List<? extends Number>",state.typeInput().path("targetType").asText());assertEquals(changed,state.lastResult());schemas.validate("NotebookState",interactions.state(user.userId(),notebook.id(),version.revision()));drain();assertEquals(1,learning.progress(user.userId()).items().get(0).independentSamples());
    }
    @Test void concurrentStateChangesAdmitExactlyOneRealLabSession() throws Exception {
        var block=append("TYPE_FLOW",null);CountDownLatch start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {var tasks=java.util.stream.IntStream.range(0,2).mapToObj(i->pool.submit(()->{start.await(5,TimeUnit.SECONDS);try{actions.dispatch(user.userId(),notebook.id(),flowInput(block,0,i==0?"List<Number>":"List<? extends Number>","ALLOWED"),key());return 200;}catch(DomainException ex){return ex.status();}})).toList();start.countDown();var results=List.of(tasks.get(0).get(10,TimeUnit.SECONDS),tasks.get(1).get(10,TimeUnit.SECONDS));assertEquals(Set.of(200,409),Set.copyOf(results));assertEquals(1,state(block).stateRevision());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM workspace_lab WHERE workspace_id=?",Integer.class,workspace.id().toString()));}
        finally {pool.shutdownNow();}
    }
    @Test void counterfactualSelectionIsVersionedAndActuallyGradesSelectedReviewedQuestion() {
        var block=append("COUNTERFACTUAL",comparison());assertEquals(409,assertThrows(DomainException.class,()->act(block,"requestHint",0)).status());
        var selected=input(block,"setParameter",0);selected.set("exerciseRef",tree(ref(variant)));var result=actions.dispatch(user.userId(),notebook.id(),selected,key());assertEquals(variant.id(),result.selectedExercise().id());assertFalse(json.write(result).contains("optionIds"));assertEquals(variant.id(),state(block).selectedExerciseRef().id());
        var answer=input(block,"submitAnswer",1);answer.set("answer",tree(Map.of("kind","CHOICE","optionIds",List.of("B"))));var graded=actions.dispatch(user.userId(),notebook.id(),answer,key());var attempt=learning.get(user.userId(),graded.attemptId());assertEquals(variant.id(),attempt.exerciseId());assertEquals(100,attempt.score().intValue());assertEquals(4,attempt.hintLevel());
        assertEquals(409,assertThrows(DomainException.class,()->actions.dispatch(user.userId(),notebook.id(),answer,key())).status());var illegal=input(block,"setParameter",2);illegal.set("exerciseRef",tree(ref(outsider)));assertEquals(422,assertThrows(DomainException.class,()->actions.dispatch(user.userId(),notebook.id(),illegal,key())).status());
        var bad=tree(Map.of("baseExerciseRef",ref(question),"changeDimension","BOUND","candidateExerciseRefs",List.of(ref(outsider))));assertEquals(400,assertThrows(DomainException.class,()->append("COUNTERFACTUAL",bad)).status());assertEquals(2,state(block).stateRevision());drain();assertEquals(1,learning.progress(user.userId()).items().get(0).independentSamples());
    }
    @Test void switchingComparisonRestoresEachQuestionHintReceiptWithoutErasingFamilyAssistance() {
        var block=append("COUNTERFACTUAL",comparison());var first=input(block,"setParameter",0);first.set("exerciseRef",tree(ref(variant)));actions.dispatch(user.userId(),notebook.id(),first,key());
        var hint=input(block,"requestHint",1);hint.put("hintLevel",2);actions.dispatch(user.userId(),notebook.id(),hint,key());assertEquals(2,state(block).hintLevel());
        var other=input(block,"setParameter",2);other.set("exerciseRef",tree(ref(variantTwo)));actions.dispatch(user.userId(),notebook.id(),other,key());assertEquals(0,state(block).hintLevel());assertEquals(variantTwo.id(),state(block).selectedExerciseRef().id());
        var back=input(block,"setParameter",3);back.set("exerciseRef",tree(ref(variant)));actions.dispatch(user.userId(),notebook.id(),back,key());assertEquals(2,state(block).hintLevel());
        var answer=input(block,"submitAnswer",4);answer.set("answer",tree(Map.of("kind","CHOICE","optionIds",List.of("B"))));assertEquals(4,learning.get(user.userId(),actions.dispatch(user.userId(),notebook.id(),answer,key()).attemptId()).hintLevel());
    }
    @Test void expertHelpUsesThreeDurableHintsAndCannotClaimModelExecution() {
        var block=append("EXPERT_HELP",null);for(int level=1;level<=3;level++){var request=input(block,"requestHint",level-1);request.put("hintLevel",level);var result=actions.dispatch(user.userId(),notebook.id(),request,key());assertEquals(question.answerSpec().hints().get(level-1),result.message());assertNull(result.tutorRequestId());assertNull(result.attemptId());}
        assertEquals(3,state(block).hintLevel());assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_hint_receipt WHERE user_id=? AND exercise_id=?",Integer.class,user.userId().toString(),question.id().toString()));assertEquals(422,assertThrows(DomainException.class,()->act(block,"submitAnswer",3)).status());
        var tampered=(ObjectNode)version.content().deepCopy();((ObjectNode)tampered.path("blocks").get(1)).set("exerciseRef",tree(ref(outsider)));assertEquals(400,assertThrows(DomainException.class,()->artifacts.save(user.userId(),notebook.id(),version.revision(),tampered,version.inputRefs())).status());
    }
    @Test void reviewNavigationUsesAdmittedOwnerSourcesAndRejectsRevokedOrChangedVersions() {
        var doc=document(user.userId(),workspace.id(),"Read this source");var config=tree(Map.of("reviewIds",List.of(),"artifactRefs",List.of(Map.of("id",doc.id(),"revision",1)),"conceptIds",List.of(f.concept.id())));var block=append("REVIEW_LINKS",config);var request=input(block,"navigateArtifact",0);request.set("artifactRef",tree(Map.of("id",doc.id(),"revision",1)));var result=actions.dispatch(user.userId(),notebook.id(),request,key());assertEquals(doc.id(),result.navigation().artifactId());assertEquals(1,result.navigation().revision());schemas.validate("NotebookActionResult",result);
        var illicit=input(block,"navigateArtifact",1);illicit.set("artifactRef",tree(Map.of("id",UUID.randomUUID(),"revision",1)));assertEquals(422,assertThrows(DomainException.class,()->actions.dispatch(user.userId(),notebook.id(),illicit,key())).status());
        var value=artifacts.version(user.userId(),doc.id(),1);artifacts.save(user.userId(),doc.id(),1,value.content(),value.inputRefs());assertEquals(409,assertThrows(DomainException.class,()->interactions.state(user.userId(),notebook.id(),version.revision())).status());assertEquals(409,assertThrows(DomainException.class,()->actions.dispatch(user.userId(),notebook.id(),request,key())).status());
    }
    @Test void appendHasSourceCasIdempotencyAndRejectsForeignArtifactClosure() {
        var request=tree(Map.of("artifactRevision",1,"originBlockId",origin,"kind","TYPE_FLOW"));String idem=key();var appended=interactions.append(user.userId(),notebook.id(),request,idem);assertEquals(2,appended.revision());assertEquals(appended,interactions.append(user.userId(),notebook.id(),request,idem));assertEquals(409,assertThrows(DomainException.class,()->interactions.append(user.userId(),notebook.id(),request,key())).status());version=appended;
        var stranger=f.learner();var elsewhere=workspaces.create(stranger.userId(),release.id(),"Private","ON_DEMAND",key());var foreign=document(stranger.userId(),elsewhere.id(),"private body");var config=tree(Map.of("reviewIds",List.of(),"artifactRefs",List.of(Map.of("id",foreign.id(),"revision",1)),"conceptIds",List.of()));assertEquals(404,assertThrows(DomainException.class,()->append("REVIEW_LINKS",config)).status());
        assertEquals(2,artifacts.get(user.userId(),notebook.id()).currentRevision());assertEquals(409,assertThrows(DomainException.class,()->interactions.state(user.userId(),notebook.id(),1)).status());
    }
    @Test void repairDiffQueuesTwoIndependentSourceJobsAndNeverTurnsCompileSuccessIntoCorrectness() {
        var block=repair();var input=input(block,"requestCompile",0);input.set("answer",tree(Map.of("kind","CODE","source",REPAIRED)));String idem=key();var result=actions.dispatch(user.userId(),notebook.id(),input,idem);assertNotNull(result.compileJobId());assertNotNull(result.baselineCompileJobId());assertNotEquals(result.compileJobId(),result.baselineCompileJobId());assertEquals(result,actions.dispatch(user.userId(),notebook.id(),input,idem));
        assertEquals(json.sha256(ORIGINAL),compiler.get(user.userId(),result.baselineCompileJobId()).sourceHash());assertEquals(json.sha256(REPAIRED),compiler.get(user.userId(),result.compileJobId()).sourceHash());assertEquals(2,learning.list(user.userId(),workspace.id()).size());
        for(int i=0;i<2;i++){var ticket=compiler.claimNext();assertTrue(Set.of(result.compileJobId(),result.baselineCompileJobId()).contains(ticket.id()));assertTrue(ticket.request().checks().isEmpty());assertTrue(compiler.accept(ticket,new CompileProtocol.Result("SUCCEEDED",ticket.request().sourceHash(),"javac 17.0.21",List.of(),false,List.of())));}
        drain();assertEquals(2,learning.list(user.userId(),workspace.id()).size());assertTrue(learning.list(user.userId(),workspace.id()).stream().allMatch(a->a.score().intValue()==0));assertEquals(1,state(block).stateRevision());
    }
    @Test void repairPairRollsBackFirstAdmissionWhenOnlyOneQueueSlotRemains() {
        var block=repair();for(int i=0;i<3;i++)compiler.create(user.userId(),workspace.id(),release.id(),ORIGINAL,"COMPILE_ONLY",key());
        var input=input(block,"requestCompile",0);input.set("answer",tree(Map.of("kind","CODE","source",REPAIRED)));String idem=key();assertEquals(429,assertThrows(DomainException.class,()->actions.dispatch(user.userId(),notebook.id(),input,idem)).status());
        assertEquals(3,compiler.list(user.userId(),workspace.id()).stream().filter(job->job.status().equals("QUEUED")).count());assertEquals(0,state(block).stateRevision());assertNull(state(block).lastResult());
        var queued=compiler.list(user.userId(),workspace.id()).stream().filter(job->job.status().equals("QUEUED")).toList();compiler.cancel(user.userId(),queued.get(0).id(),key());var retry=actions.dispatch(user.userId(),notebook.id(),input,idem);assertNotNull(retry.baselineCompileJobId());assertEquals(4,compiler.list(user.userId(),workspace.id()).stream().filter(job->job.status().equals("QUEUED")).count());
    }
    @Test void realHttpOptionsAppendAndStateEnforceAuthenticationCsrfAndResponseSchemas() throws Exception {
        var session=HttpSessionFixture.login(mvc,json,user.username(),LearningEditorialFixture.PASSWORD);var path="/api/v1/notebooks/"+notebook.id();
        var options=mvc.perform(get(path+"/block-options").session(session).param("artifactRevision","1").param("originBlockId",origin)).andReturn().getResponse();assertEquals(200,options.getStatus(),options.getContentAsString());schemas.validate("NotebookBlockOptions",json.tree(options.getContentAsString()));
        var input=tree(Map.of("artifactRevision",1,"originBlockId",origin,"kind","TYPE_FLOW"));assertEquals(403,mvc.perform(post(path+"/blocks").session(session).header("Idempotency-Key",key()).contentType("application/json").content(input.toString())).andReturn().getResponse().getStatus());
        var response=mvc.perform(post(path+"/blocks").session(session).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(input.toString())).andReturn().getResponse();assertEquals(201,response.getStatus(),response.getContentAsString());schemas.validate("ArtifactRevision",json.tree(response.getContentAsString()));
        var restored=mvc.perform(get(path+"/state").session(session).param("artifactRevision","2")).andReturn().getResponse();assertEquals(200,restored.getStatus());schemas.validate("NotebookState",json.tree(restored.getContentAsString()));assertEquals(2,json.tree(restored.getContentAsString()).path("blocks").size());
        assertEquals(409,mvc.perform(get(path+"/state").session(session).param("artifactRevision","1")).andReturn().getResponse().getStatus());assertEquals(401,mvc.perform(get(path+"/state").param("artifactRevision","2")).andReturn().getResponse().getStatus());
    }
    @Test void oldTypeFlowSourceRemainsReadableButDoesNotGainUndeclaredParameterSemantics() {
        var content=(ObjectNode)version.content().deepCopy();var block=(ObjectNode)content.path("blocks").get(0);block.put("kind","TYPE_FLOW");block.set("allowedActions",tree(List.of("predict","requestHint","requestCompile")));block.set("config",tree(Map.of("sourceType","List<Integer>","targetType","List<Number>","parameters",List.of(Map.of("name","T","candidates",List.of("Integer","Number"))),"operations",List.of("ASSIGN"))));
        version=artifacts.save(user.userId(),notebook.id(),1,content,version.inputRefs());schemas.validate("ArtifactRevision",version);var hint=act(block,"requestHint",0);assertEquals(question.answerSpec().hints().get(0),hint.message());assertEquals(422,assertThrows(DomainException.class,()->actions.dispatch(user.userId(),notebook.id(),flowInput(block,1,"List<Number>","DENIED"),key())).status());
        block.set("allowedActions",tree(List.of("setParameter")));assertEquals(400,assertThrows(DomainException.class,()->artifacts.save(user.userId(),notebook.id(),version.revision(),content,version.inputRefs())).status());
    }
    @Test void contextualFirstAnswerCannotFabricateIndependentEvidenceThroughAuthoredBlock() {
        var content=(ObjectNode)version.content().deepCopy();var block=(ObjectNode)content.path("blocks").get(0);block.set("exerciseRef",tree(ref(outsider)));block.putNull("attemptId");version=artifacts.save(user.userId(),notebook.id(),1,content,version.inputRefs());
        var input=input(block,"submitAnswer",0);input.set("answer",tree(Map.of("kind","CHOICE","optionIds",List.of("A"))));var result=actions.dispatch(user.userId(),notebook.id(),input,key());assertEquals(4,learning.get(user.userId(),result.attemptId()).hintLevel());assertEquals(100,learning.get(user.userId(),result.attemptId()).score().intValue());drain();assertEquals(1,learning.progress(user.userId()).items().get(0).independentSamples());
    }
    @Test void invalidActionFieldsAndDisallowedTypesDoNotWriteStateOrRealLabEvents() {
        var block=append("TYPE_FLOW",null);assertEquals(422,assertThrows(DomainException.class,()->actions.dispatch(user.userId(),notebook.id(),flowInput(block,0,"Runtime.getRuntime()","UNKNOWN"),key())).status());
        var wrong=input(block,"setParameter",0);wrong.set("parameterInput",tree(Map.of("name","targetType","value","Map<String,Integer>","operation","ASSIGN","prediction","UNKNOWN")));assertEquals(400,assertThrows(DomainException.class,()->actions.dispatch(user.userId(),notebook.id(),wrong,key())).status());
        var extra=flowInput(block,0,"List<Number>","DENIED");extra.set("exerciseRef",tree(ref(variant)));assertEquals(400,assertThrows(DomainException.class,()->actions.dispatch(user.userId(),notebook.id(),extra,key())).status());assertEquals(0,state(block).stateRevision());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM workspace_lab WHERE workspace_id=?",Integer.class,workspace.id().toString()));
    }
    @Test void sixteenConceptsUseExactAuthoredEvidenceWithoutOverflowOrUnrelatedChunks() {
        var concepts=new java.util.ArrayList<UUID>();var intended=new java.util.ArrayList<ContentRef>();var allChunks=new java.util.ArrayList<ContentRef>();
        for(int i=0;i<16;i++) {
            var concept=drafts.createConcept(f.author.userId(),new ConceptInput(UUID.randomUUID(),"N"+key().replace("-","").substring(0,12).toUpperCase(java.util.Locale.ROOT),"Notebook source concept "+i,List.of()),key());concepts.add(concept.id());
            for(int variant=0;variant<2;variant++) {
                var chunk=drafts.createChunk(f.author.userId(),new ChunkInput(UUID.randomUUID(),1,0,concept.id(),"Evidence "+i+" / "+variant,"Actual supplied fixture explanation "+i+" variant "+variant,f.chunk.sources()),key());f.approve("CHUNK",chunk.id(),1,chunk.reviewHash());var ref=new ContentRef(chunk.id(),1);allChunks.add(ref);if(variant==0)intended.add(ref);
            }
        }
        var draft=drafts.createExercise(f.author.userId(),new ExerciseInput(UUID.randomUUID(),1,0,UUID.randomUUID(),"SINGLE",1,"Sixteen explicitly supported concepts",concepts,LearningEditorialFixture.choice("A"),intended),key());f.approve("EXERCISE",draft.id(),1,draft.reviewHash());
        var course=releases.create(f.author.userId(),new ReleaseInput("sixteen-"+key().substring(0,20),allChunks,List.of(new ContentRef(draft.id(),1))),key());course=releases.publish(f.author.userId(),course.id(),new PublishInput(course.manifestHash(),course.activeRevision()),key());
        var own=workspaces.create(user.userId(),course.id(),"All supported concepts","ON_DEMAND",key());var attempt=learning.submit(user.userId(),own.id(),course.id(),draft.id(),1,tree(Map.of("kind","CHOICE","optionIds",List.of("B"))),key());
        var notebook=notebooks.build(user.userId(),own.id(),List.of(attempt.id()),"Bounded exact citations",key());var saved=artifacts.version(user.userId(),notebook.id(),1);schemas.validate("ArtifactRevision",saved);
        var citations=saved.content().path("blocks").get(0).path("evidenceRefs");assertEquals(16,citations.size());var expected=Set.copyOf(intended.stream().map(ref->ref.id().toString()).toList());var actual=new java.util.HashSet<String>();citations.forEach(ref->actual.add(ref.path("id").asText()));assertEquals(expected,actual);assertEquals(17,saved.inputRefs().size());
    }
}
