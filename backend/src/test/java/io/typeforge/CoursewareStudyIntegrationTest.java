package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.identity.IdentityService;
import io.typeforge.learning.PersonalCourseware;
import io.typeforge.orchestration.DurableAgentRuntime;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.tutor.StudyWorkflowService;
import io.typeforge.tutor.TutorService;
import io.typeforge.tutor.TutorWorker;
import io.typeforge.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises authenticated courseware admission, durable role checkpoints and editable page fidelity on both engines. */
@SpringBootTest(properties={"typeforge.study.dispatch-enabled=false","typeforge.tutor.worker-enabled=false",
    "typeforge.tutor.provider-url=","typeforge.tutor.api-key=","typeforge.tutor.model="})
@AutoConfigureMockMvc @ActiveProfiles({"test","demo"}) @Timeout(60)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoursewareStudyIntegrationTest {
    private static final String PASSWORD="fixture-password-Courseware-012";
    @Autowired IdentityService identities;
    @Autowired WorkspaceService workspaces;
    @Autowired CurriculumService curriculum;
    @Autowired PersonalCourseware lessons;
    @Autowired StudyWorkflowService workflow;
    @Autowired DurableAgentRuntime runtime;
    @Autowired TutorWorker worker;
    @Autowired TutorService tutors;
    @Autowired ArtifactService artifacts;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    UUID owner,workspace,concept,release;
    MockHttpSession session;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        PrivateModuleDatabaseFixture.configure(properties,"courseware-study");
    }
    @BeforeAll void engine() {PrivateModuleDatabaseFixture.prove(jdbc,json,getClass().getSimpleName());}
    @BeforeEach void setup() throws Exception {
        var who=identities.register("course-study-"+key(),PASSWORD);
        owner=who.userId();
        release=curriculum.activeRelease();
        workspace=workspaces.create(owner,release,"个人课程制作","COLLABORATIVE",key()).id();
        concept=curriculum.concepts(release).stream().filter(c->c.code().equals("G06")).findFirst().orElseThrow().id();
        session=HttpSessionFixture.login(mvc,json,who.username(),PASSWORD);
    }

    static String key(){return UUID.randomUUID().toString();}
    String endpoint(UUID scope){return "/api/v1/workspaces/"+scope+"/courseware/"+concept+"/study";}
    JsonNode start(UUID scope,UUID plan,String mode,String key,int expected,MockHttpSession auth) throws Exception {
        var response=mvc.perform(post(endpoint(scope)).session(auth).with(csrf()).header("Idempotency-Key",key)
            .contentType("application/json").content(json.write(Map.of("planId",plan,"mode",mode))))
            .andExpect(status().is(expected)).andReturn().getResponse();
        return json.tree(response.getContentAsString(StandardCharsets.UTF_8));
    }
    JsonNode resume(UUID scope,UUID plan,String mode,int expected,MockHttpSession auth) throws Exception {
        var result=mvc.perform(get(endpoint(scope)).session(auth).param("planId",plan.toString()).param("mode",mode))
            .andExpect(status().is(expected)).andReturn().getResponse();
        return result.getContentAsByteArray().length==0?null:json.tree(result.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test void emptyResumeUnavailableModelAndIdempotentModeAdmissionUseThePrivateEndpoint() throws Exception {
        var plan=lessons.generate(owner,workspace,concept,null,"GUIDED",key());
        assertNull(resume(workspace,plan.id(),"EVIDENCE_ONLY",204,session));
        assertNull(resume(workspace,plan.id(),"MODEL",204,session));
        String replay=key();
        start(workspace,plan.id(),"MODEL",replay,503,session);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM learning_goal WHERE workspace_id=? AND origin_kind='COURSEWARE'",Integer.class,workspace.toString()));
        var first=start(workspace,plan.id(),"EVIDENCE_ONLY",replay,202,session).path("id").asText();
        assertEquals(first,start(workspace,plan.id(),"EVIDENCE_ONLY",replay,202,session).path("id").asText());
        assertEquals(first,start(workspace,plan.id(),"EVIDENCE_ONLY",key(),202,session).path("id").asText());
        assertEquals(first,resume(workspace,plan.id(),"EVIDENCE_ONLY",200,session).path("id").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM courseware_study_run WHERE plan_id=?",Integer.class,plan.id().toString()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM learning_goal WHERE workspace_id=? AND origin_kind='COURSEWARE'",Integer.class,workspace.toString()));
        assertTrue(workspaces.goals(owner,workspace).isEmpty());
    }

    @Test void actualRoleDagPreservesOrderedPageBodiesPromptsScopedEvidenceAndTheOriginalPrivateSnapshot() throws Exception {
        var original=workspaces.stateMemory(owner,workspace,"先读示例。BEFORE_PLAN_SOURCE",key());
        var publicGoal=workspaces.createGoal(owner,workspace,"G06 用代码理解列表不变性",List.of("指出适用条件"),key());
        var plan=lessons.generate(owner,workspace,concept,null,"EXAMPLE_FIRST",key());
        var later=workspaces.stateMemory(owner,workspace,"AFTER_PLAN_PRIVATE_SENTINEL",key());
        UUID runId=UUID.fromString(start(workspace,plan.id(),"EVIDENCE_ONLY",key(),202,session).path("id").asText());
        var run=runtime.get(owner,workspace,runId);
        var input=workspaces.readContext(owner,workspace,run.snapshotId());
        assertTrue(input.references().stream().anyMatch(r->r.id().equals(original.id())));
        assertTrue(input.references().stream().noneMatch(r->r.id().equals(later.id())));
        assertEquals(List.of(publicGoal.id()),workspaces.goals(owner,workspace).stream().map(WorkspaceService.Goal::id).toList());
        assertTrue(workspaces.createContext(owner,workspace).references().stream().noneMatch(r->r.id().equals(run.goalId())));
        for(int pass=0;pass<20;pass++) {
            workflow.advance(owner,workspace,runId);
            while(worker.runOne()) { /* Real bounded role workers finish each scheduled layer. */ }
            if(Set.of("COMPLETED","PARTIAL","FAILED","WAITING_USER").contains(runtime.get(owner,workspace,runId).status()))break;
        }
        var done=runtime.get(owner,workspace,runId);assertEquals("COMPLETED",done.status(),json.write(done));
        assertTrue(done.steps().stream().allMatch(step->step.status().equals("SUCCEEDED")));
        var details=workflow.details(owner,runId);
        assertEquals(done.steps().size(),details.contextCheckpoints().size());
        assertTrue(details.contextCheckpoints().stream().filter(c->!c.stepKey().equals("planner")).allMatch(c->!c.parentCheckpointIds().isEmpty()));
        for(var requestId:details.tutorRequestIds()) {
            var reply=tutors.get(owner,requestId);
            assertFalse(reply.evidenceRefs().isEmpty());assertTrue(reply.evidenceRefs().stream().allMatch(card->card.conceptId().equals(concept)));
            var prepared=jdbc.queryForObject("SELECT prepared_prompt FROM study_context_checkpoint WHERE request_id=?",String.class,requestId.toString());
            assertTrue(prepared.contains("BEFORE_PLAN_SOURCE"));assertFalse(prepared.contains("AFTER_PLAN_PRIVATE_SENTINEL"));
        }
        var output=done.steps().stream().filter(s->s.key().equals("output-slides")).findFirst().orElseThrow().outputRefs().get(0);
        var edition=artifacts.validateCurrentInputs(owner,output.id(),output.revision());
        assertEquals("DRAFT",edition.reviewStatus());
        assertTrue(edition.inputRefs().stream().anyMatch(ref->ref.kind().equals("MEMORY") && ref.id().equals(original.id())));
        assertTrue(edition.inputRefs().stream().anyMatch(ref->ref.kind().equals("KNOWLEDGE_CHUNK")));
        assertTrue(edition.inputRefs().stream().noneMatch(ref->ref.id().equals(later.id())));
        var pages=edition.content().path("slides");int cursor=0;
        for(var scene:plan.scenes()) {
            var body=new StringBuilder();var last=json.tree("{}");int part=0;
            while(cursor<pages.size() && pages.get(cursor).path("id").asText().equals(scene.key()+"-"+(part+1))) {
                last=pages.get(cursor++);part++;
                assertTrue(last.path("title").asText().startsWith(scene.title()));
                var element=last.path("elements").get(0);body.append(element.path("text").asText());
                assertEquals(scene.kind().equals("EXAMPLE")?"CODE":"TEXT",element.path("kind").asText());
                if(scene.chunkId()!=null) {
                    assertEquals(scene.chunkId().toString(),last.path("evidenceRefs").get(0).path("id").asText());
                    assertEquals(scene.chunkRevision(),last.path("evidenceRefs").get(0).path("revision").asInt());
                }
            }
            assertTrue(part>0,scene.key());assertEquals(scene.body(),body.toString());
            if(scene.prompt()!=null)assertEquals(scene.prompt(),last.path("elements").get(1).path("text").asText());
        }
        assertTrue(cursor<pages.size(),"The experts' actual output follows the personalized scene sequence");
        assertTrue(pages.get(cursor).path("id").asText().startsWith("slide-"));
        assertEquals("COMPLETED",resume(workspace,plan.id(),"EVIDENCE_ONLY",200,session).path("status").asText());
        workspaces.revokeMemory(owner,workspace,original.id(),original.revision());
        resume(workspace,plan.id(),"EVIDENCE_ONLY",409,session);
        assertThrows(DomainException.class,()->artifacts.validateCurrentInputs(owner,output.id(),output.revision()));
    }

    @Test void aForeignAccountAndAnotherOwnedWorkspaceCannotReadOrProduceThePlan() throws Exception {
        var plan=lessons.generate(owner,workspace,concept,null,"AUTO",key());
        var other=identities.register("course-foreign-"+key(),PASSWORD);
        var foreignSession=HttpSessionFixture.login(mvc,json,other.username(),PASSWORD);
        resume(workspace,plan.id(),"EVIDENCE_ONLY",404,foreignSession);start(workspace,plan.id(),"EVIDENCE_ONLY",key(),404,foreignSession);
        var ownOther=workspaces.create(owner,release,"其它课堂","ON_DEMAND",key()).id();
        resume(ownOther,plan.id(),"EVIDENCE_ONLY",404,session);start(ownOther,plan.id(),"EVIDENCE_ONLY",key(),404,session);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM courseware_study_run WHERE plan_id=?",Integer.class,plan.id().toString()));
    }
}
