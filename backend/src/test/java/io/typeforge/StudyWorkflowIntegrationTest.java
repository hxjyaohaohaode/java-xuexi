package io.typeforge;

import io.typeforge.artifacts.*;
import io.typeforge.exercise.LearningService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.orchestration.*;
import io.typeforge.shared.*;
import io.typeforge.tutor.*;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the persisted DAG, actual local evidence requests and immutable draft/file pipeline. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:study-workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH","typeforge.tutor.provider-url=","typeforge.tutor.api-key=","typeforge.tutor.model="})
@ActiveProfiles({"test","demo"}) @Timeout(60)
class StudyWorkflowIntegrationTest {
    @Autowired StudyWorkflowService studies; @Autowired DurableAgentRuntime runtime; @Autowired TutorWorker worker;
    @Autowired TutorService tutors; @Autowired WorkspaceService workspaces; @Autowired ArtifactService artifacts;
    @Autowired ArtifactExportService exports; @Autowired JdbcTemplate jdbc; @Autowired JsonCodec json; @Autowired SchemaCatalog schemas;
    @Autowired LearningService learning;
    UUID owner,workspace,goal,snapshot;
    static String key(){return UUID.randomUUID().toString();}
    @BeforeEach void scope(){owner=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",owner.toString(),"study-"+owner,"fixture-not-login");workspace=workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"泛型深度学习","COLLABORATIVE",key()).id();goal=workspaces.createGoal(owner,workspace,"理解 Java 泛型 PECS extends super 与 List 通配符",List.of("用已发布证据解释边界"),key()).id();snapshot=workspaces.createContext(owner,workspace).id();}
    StudyWorkflowService.Input input(){return new StudyWorkflowService.Input(goal,snapshot,List.of("DOCUMENT","SLIDES"),new TaskPlan.Budget(100000,600000,1000000,3),TutorService.Mode.EVIDENCE_ONLY);}
    DurableAgentRuntime.RunView start(){return studies.create(owner,workspace,input(),key());}
    DurableAgentRuntime.RunView drain(UUID id){for(int i=0;i<20;i++){studies.advance(owner,workspace,id);while(worker.runOne()){}var r=runtime.get(owner,workspace,id);if(Set.of("COMPLETED","PARTIAL","FAILED","CANCELLED","WAITING_USER").contains(r.status()))return r;}return runtime.get(owner,workspace,id);}
    @AfterEach void cleanup(){for(var row:jdbc.queryForList("SELECT id FROM agent_run WHERE workspace_id=? AND status NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELLED')",workspace.toString())){UUID id=UUID.fromString(row.get("id").toString());var run=runtime.get(owner,workspace,id);runtime.command(owner,workspace,id,run.runEpoch(),DurableAgentRuntime.Command.CANCEL);studies.cancelAssociated(owner,id);}}
    @Test void localWorkflowReallyPlansReviewsAndExportsEditableArtifacts(){
        var first=start();schemas.validate("AgentRun",first);var run=drain(first.id());assertEquals("COMPLETED",run.status(),json.write(run));assertEquals(2,run.planRevision());assertTrue(run.steps().stream().allMatch(s->s.status().equals("SUCCEEDED")));schemas.validate("AgentRun",run);
        var details=studies.details(owner,run.id());schemas.validate("StudyWorkflowDetails",details);assertEquals("EVIDENCE_ONLY",details.mode());assertEquals(2,details.tutorRequestIds().size());assertEquals(4,details.artifactIds().size());assertEquals(0,run.spentTokens());assertEquals(0,run.reservedTokens());
        for(var id:details.artifactIds()){var a=artifacts.get(owner,id);var v=artifacts.validateCurrentInputs(owner,id,a.currentRevision());assertEquals("DRAFT",v.reviewStatus());}
        var output=run.steps().stream().filter(s->s.key().equals("output-document")).findFirst().orElseThrow().outputRefs().get(0);assertEquals("DRAFT_ARTIFACT",output.kind());var job=exports.create(owner,output.id(),output.revision(),"DOCX",key());exports.dispatchOnce();assertEquals("SUCCEEDED",exports.get(owner,job.id()).status());assertTrue(exports.download(owner,job.id()).bytes().length>1000);
    }
    @Test void retryKeyReusesRunAndChangedOutputsConflict(){var key=key();var run=studies.create(owner,workspace,input(),key);assertEquals(run.id(),studies.create(owner,workspace,input(),key).id());var different=new StudyWorkflowService.Input(goal,snapshot,List.of("DOCUMENT"),input().budget(),input().mode());assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(DomainException.class,()->studies.create(owner,workspace,different,key)).code());}
    @Test void modelModeDoesNotPretendToWorkWithoutConfiguredProvider(){var model=new StudyWorkflowService.Input(goal,snapshot,List.of("DOCUMENT"),input().budget(),TutorService.Mode.MODEL);assertEquals("TUTOR_MODEL_UNAVAILABLE",assertThrows(DomainException.class,()->studies.create(owner,workspace,model,key())).code());}
    @Test void notebookNeedsRealGradedAttempt(){var notebook=new StudyWorkflowService.Input(goal,snapshot,List.of("NOTEBOOK"),input().budget(),input().mode());assertEquals(400,assertThrows(DomainException.class,()->studies.create(owner,workspace,notebook,key())).status());}
    @Test void pauseCancelResumeFencesQueuedRequestAndKeepsAuditHistory(){
        var run=start();studies.advance(owner,workspace,run.id());studies.advance(owner,workspace,run.id());run=runtime.get(owner,workspace,run.id());assertFalse(studies.details(owner,run.id()).tutorRequestIds().isEmpty());UUID original=studies.details(owner,run.id()).tutorRequestIds().get(0);
        var paused=runtime.command(owner,workspace,run.id(),run.runEpoch(),DurableAgentRuntime.Command.PAUSE);studies.cancelAssociated(owner,run.id());assertEquals("CANCELLED",tutors.get(owner,original).status());assertEquals(0,runtime.get(owner,workspace,run.id()).reservedTokens());runtime.command(owner,workspace,run.id(),paused.runEpoch(),DurableAgentRuntime.Command.RESUME);
        var completed=drain(run.id());assertEquals("COMPLETED",completed.status(),json.write(completed));assertTrue(studies.details(owner,run.id()).tutorRequestIds().size()>2,"cancelled request remains visible beside resumed work");assertEquals("CANCELLED",tutors.get(owner,original).status());
    }
    @Test void manualExpertEditDuringReviewInvalidatesOldResultsWithoutOverwrite(){
        var run=start();studies.advance(owner,workspace,run.id());studies.advance(owner,workspace,run.id());while(worker.runOne()){}
        // Preserve the completed expert request, then pause before review starts so its human revision is admitted.
        studies.advance(owner,workspace,run.id());var detail=studies.details(owner,run.id());UUID expert=detail.artifactIds().get(0);var current=artifacts.get(owner,expert);var version=artifacts.version(owner,expert,current.currentRevision());var edited=version.content().deepCopy();for(var block:edited.path("blocks"))if(block.path("id").asText().equals("answer"))((com.fasterxml.jackson.databind.node.ObjectNode)block).put("text","学习者修订：USER_EDIT_PRESERVED；PECS 通配符 extends super。");artifacts.save(owner,expert,version.revision(),edited,version.inputRefs());
        // A review already started against the old revision must stop, rather than certify a different draft.
        var result=drain(run.id());assertNotEquals("COMPLETED",result.status());for(UUID id:studies.details(owner,run.id()).artifactIds())assertNotEquals("VERIFIED",artifacts.version(owner,id,artifacts.get(owner,id).currentRevision()).reviewStatus());assertEquals(2,artifacts.get(owner,expert).currentRevision());
    }
    @Test void cancellationCannotCreateLateArtifacts(){var run=start();studies.advance(owner,workspace,run.id());studies.advance(owner,workspace,run.id());var active=runtime.get(owner,workspace,run.id());runtime.command(owner,workspace,run.id(),active.runEpoch(),DurableAgentRuntime.Command.CANCEL);studies.cancelAssociated(owner,run.id());while(worker.runOne()){}studies.advance(owner,workspace,run.id());assertEquals("CANCELLED",runtime.get(owner,workspace,run.id()).status());assertEquals(0,artifacts.list(owner,workspace).size());}
    @Test void expiredWorkerLeaseCanBeAccountedThenExplicitlyResumed(){var run=start();studies.advance(owner,workspace,run.id());studies.advance(owner,workspace,run.id());jdbc.update("UPDATE agent_step SET lease_until=? WHERE run_id=? AND status='RUNNING'",Timestamp.from(Instant.now().minusSeconds(2)),run.id().toString());studies.advance(owner,workspace,run.id());assertEquals("WAITING_USER",runtime.get(owner,workspace,run.id()).status());studies.advance(owner,workspace,run.id());var waiting=runtime.get(owner,workspace,run.id());runtime.command(owner,workspace,run.id(),waiting.runEpoch(),DurableAgentRuntime.Command.RESUME);assertEquals("COMPLETED",drain(run.id()).status());}
    @Test void wrongOwnerAndChangedGoalCannotAdmitOldWork(){var run=start();UUID stranger=UUID.randomUUID();assertThrows(DomainException.class,()->studies.details(stranger,run.id()));var g=workspaces.goal(owner,workspace,goal);workspaces.updateGoal(owner,g.id(),g.revision(),"新的类型擦除学习目标",g.acceptance(),"ACTIVE");studies.advance(owner,workspace,run.id());assertEquals("WAITING_USER",runtime.get(owner,workspace,run.id()).status());assertTrue(studies.details(owner,run.id()).artifactIds().isEmpty());}
    private UUID selectionExercise(){return UUID.fromString(jdbc.queryForObject("SELECT e.id FROM exercise e JOIN release_exercise re ON re.exercise_id=e.id AND re.exercise_revision=e.revision WHERE re.release_id=? AND e.kind='SINGLE' AND e.status='REVIEWED' ORDER BY e.id LIMIT 1",String.class,DemoContentInitializer.RELEASE_ID.toString()));}
    private LearningService.Attempt graded(UUID user,UUID space,UUID exercise,boolean correct){
        var spec=json.tree(jdbc.queryForObject("SELECT answer_spec FROM exercise WHERE id=? AND revision=1",String.class,exercise.toString()));
        String expected=spec.path("optionIds").get(0).asText();String selected=expected;
        if(!correct)for(var option:spec.path("options"))if(!option.path("id").asText().equals(expected)){selected=option.path("id").asText();break;}
        return learning.submit(user,space,DemoContentInitializer.RELEASE_ID,exercise,1,json.tree(json.write(Map.of("kind","CHOICE","optionIds",List.of(selected)))),key());
    }
    @Test void notebookSelectionIsPersistedExactlyAndCannotExpandOnIdempotentReplay(){
        UUID exercise=selectionExercise();var omitted=graded(owner,workspace,exercise,false);var selected=graded(owner,workspace,exercise,false);
        snapshot=workspaces.createContext(owner,workspace).id();String idem=key();
        var request=new StudyWorkflowService.Input(goal,snapshot,List.of("NOTEBOOK"),input().budget(),input().mode(),List.of(selected.id()));
        var run=studies.create(owner,workspace,request,idem);assertEquals(run.id(),studies.create(owner,workspace,request,idem).id());
        var changed=new StudyWorkflowService.Input(goal,snapshot,List.of("NOTEBOOK"),input().budget(),input().mode(),List.of(omitted.id(),selected.id()));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(DomainException.class,()->studies.create(owner,workspace,changed,idem)).code());
        var completed=drain(run.id());assertEquals("COMPLETED",completed.status(),json.write(completed));
        var output=completed.steps().stream().filter(step->step.key().equals("output-notebook")).findFirst().orElseThrow().outputRefs().get(0);
        var notebook=artifacts.validateCurrentInputs(owner,output.id(),output.revision());
        assertEquals(1,notebook.content().path("blocks").size());assertEquals(selected.id().toString(),notebook.content().path("blocks").get(0).path("attemptId").asText());
        assertEquals(List.of(selected.id()),notebook.inputRefs().stream().filter(ref->ref.kind().equals("ATTEMPT")).map(ref->ref.id()).toList());
        assertEquals(json.tree(json.write(List.of(selected.id()))),json.tree(jdbc.queryForObject("SELECT selected_attempts FROM study_workflow WHERE run_id=?",String.class,run.id().toString())));
    }
    @Test void notebookRejectsForeignUnselectedCorrectAndDuplicateAttemptsBeforeCreatingAnyRun(){
        UUID exercise=selectionExercise();var independentCorrect=graded(owner,workspace,exercise,true);assertEquals(0,independentCorrect.hintLevel());
        var eligible=graded(owner,workspace,exercise,false);snapshot=workspaces.createContext(owner,workspace).id();
        var outsideSnapshot=graded(owner,workspace,exercise,false);
        UUID other=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",other.toString(),"foreign-study-"+other,"fixture-not-login");
        UUID otherSpace=workspaces.create(other,DemoContentInitializer.RELEASE_ID,"Foreign correction","ON_DEMAND",key()).id();var foreign=graded(other,otherSpace,exercise,false);
        for(List<UUID> invalid:List.of(List.<UUID>of(),List.of(independentCorrect.id()),List.of(outsideSnapshot.id()),List.of(foreign.id()),List.of(eligible.id(),eligible.id()))) {
            var request=new StudyWorkflowService.Input(goal,snapshot,List.of("NOTEBOOK"),input().budget(),input().mode(),invalid);
            assertEquals(400,assertThrows(DomainException.class,()->studies.create(owner,workspace,request,key())).status(),invalid.toString());
        }
        var wrongOutput=new StudyWorkflowService.Input(goal,snapshot,List.of("DOCUMENT"),input().budget(),input().mode(),List.of(eligible.id()));
        assertEquals(400,assertThrows(DomainException.class,()->studies.create(owner,workspace,wrongOutput,key())).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE workspace_id=?",Integer.class,workspace.toString()));
        assertTrue(artifacts.list(owner,workspace).isEmpty());
    }
}
