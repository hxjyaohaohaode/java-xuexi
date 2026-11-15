package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.typeforge.compiler.CompileSettings;
import io.typeforge.identity.IdentityService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.operations.events.OutboxDispatcher;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.tutor.TutorSettings;
import io.typeforge.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real persisted sessions, hints and grading with both external execution adapters disabled. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:exercise-hint-capability;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH",
    "typeforge.compiler.enabled=false", "typeforge.compiler.worker-url=", "typeforge.compiler.worker-token=",
    "typeforge.tutor.provider-url=", "typeforge.tutor.api-key=", "typeforge.tutor.model="
})
@AutoConfigureMockMvc
@ActiveProfiles({"test","demo"})
class ExerciseHintCapabilityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonCodec json;
    @Autowired SchemaCatalog schemas;
    @Autowired IdentityService identities;
    @Autowired WorkspaceService workspaces;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher outbox;
    @Autowired CompileSettings compiler;
    @Autowired TutorSettings tutor;
    private static final UUID RELEASE=DemoContentInitializer.RELEASE_ID;
    private static final String PASSWORD="fixture-strong-password-123";
    private record Learner(UUID owner,UUID workspace,MockHttpSession session,JsonNode exercise) {}
    private static String key(){return UUID.randomUUID().toString();}
    private static Set<String> names(JsonNode value){var result=new HashSet<String>();value.forEach(item->result.add(item.asText()));return result;}

    private Learner learner() throws Exception {
        String username="hint-capability-"+key();UUID owner=identities.register(username,PASSWORD).userId();
        var session=HttpSessionFixture.login(mvc,json,username,PASSWORD);
        UUID workspace=workspaces.create(owner,RELEASE,"Hint capability","ON_DEMAND",key()).id();
        JsonNode exercise=getJson("/exercises?releaseId="+RELEASE+"&limit=100",session,"ExerciseList").path("items").get(0);
        assertTrue(Set.of("SINGLE","MULTIPLE","COMPILE_PREDICTION","READING","DIAGNOSIS").contains(exercise.path("kind").asText()));
        return new Learner(owner,workspace,session,exercise);
    }
    private JsonNode getJson(String path,MockHttpSession session,String schema) throws Exception {
        var request=get("/api/v1"+path);if(session!=null)request.session(session);
        var response=mvc.perform(request).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        JsonNode value=json.tree(response.getContentAsString());schemas.validate(schema,value);return value;
    }
    private JsonNode postJson(String path,MockHttpSession session,Object body,String idem,int expected,String schema) throws Exception {
        var response=mvc.perform(post("/api/v1"+path).session(session).with(csrf()).header("Idempotency-Key",idem)
            .contentType("application/json").content(json.write(body))).andReturn().getResponse();
        assertEquals(expected,response.getStatus(),response.getContentAsString());
        JsonNode value=json.tree(response.getContentAsString());schemas.validate(schema,value);return value;
    }
    private Map<String,Object> hintInput(Learner learner,int level){
        return Map.of("workspaceId",learner.workspace(),"releaseId",RELEASE,"exerciseRevision",learner.exercise().path("revision").asInt(),"level",level);
    }
    private JsonNode correctAnswer(Learner learner){
        var spec=json.tree(jdbc.queryForObject("SELECT answer_spec FROM exercise WHERE id=? AND revision=?",String.class,
            learner.exercise().path("id").asText(),learner.exercise().path("revision").asInt()));
        var answer=(ObjectNode)json.tree("{\"kind\":\"CHOICE\"}");answer.set("optionIds",spec.path("optionIds"));return answer;
    }
    private void drain(){for(int i=0;i<20;i++)if(outbox.dispatchBatch(100)==0)break;}

    @Test void capabilityContractIncludesHintsIndependentlyOfCompilerAndModel() throws Exception {
        assertFalse(compiler.configured());assertFalse(tutor.configured());
        JsonNode capabilities=getJson("/capabilities",learner().session(),"Capabilities");
        Set<String> enabled=names(capabilities.path("enabled")),unavailable=names(capabilities.path("unavailable"));
        assertTrue(enabled.containsAll(Set.of("EXERCISE_HINTS","CHOICE_GRADING","NOTEBOOK_ACTIONS","FINITE_TYPE_LAB","READING_EVENTS","READING_HISTORY")));
        assertTrue(unavailable.containsAll(Set.of("COMPILE_ONLY","CODE_GRADING","TUTOR_MODEL","LLM_PLANNING")));
        assertTrue(java.util.Collections.disjoint(enabled,unavailable));
        var withoutHint=(ObjectNode)capabilities.deepCopy();var remaining=(ArrayNode)json.tree("[]");
        withoutHint.path("enabled").forEach(item->{if(!item.asText().equals("EXERCISE_HINTS"))remaining.add(item);});
        withoutHint.set("enabled",remaining);
        assertThrows(DomainException.class,()->schemas.validate("Capabilities",withoutHint),"the public contract must catch the missing UI gate");
        var falselyUnavailable=(ObjectNode)capabilities.deepCopy();((ArrayNode)falselyUnavailable.path("unavailable")).add("EXERCISE_HINTS");
        assertThrows(DomainException.class,()->schemas.validate("Capabilities",falselyUnavailable));
    }

    @Test void threeLevelHintsAndNotebookActionsRemainAvailableAndAssistedWithoutAWorker() throws Exception {
        Learner learner=learner();String path="/exercises/"+learner.exercise().path("id").asText()+"/hints";
        var texts=new ArrayList<String>();
        for(int level=1;level<=3;level++){
            String idem=key();JsonNode hint=postJson(path,learner.session(),hintInput(learner,level),idem,200,"ExerciseHint");
            assertEquals(level,hint.path("level").asInt());assertEquals("ASSISTED_PRACTICE",hint.path("evidenceClass").asText());
            assertFalse(hint.path("text").asText().isBlank());texts.add(hint.path("text").asText());
            assertEquals(hint,postJson(path,learner.session(),hintInput(learner,level),idem,200,"ExerciseHint"));
        }
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_hint_receipt WHERE user_id=?",Integer.class,learner.owner().toString()));
        assertTrue(getJson("/me/progress",learner.session(),"Progress").path("items").isEmpty());
        JsonNode attempt=postJson("/attempts",learner.session(),Map.of("workspaceId",learner.workspace(),"releaseId",RELEASE,
            "exerciseId",learner.exercise().path("id").asText(),"exerciseRevision",learner.exercise().path("revision").asInt(),
            "answer",correctAnswer(learner)),key(),201,"Attempt");
        assertEquals("GRADED",attempt.path("status").asText());assertEquals(100,attempt.path("score").asInt());assertEquals(3,attempt.path("hintLevel").asInt());
        drain();
        JsonNode progress=getJson("/me/progress",learner.session(),"Progress");
        progress.path("items").forEach(item->assertEquals(0,item.path("independentSamples").asInt()));
        assertFalse(progress.path("rebuilding").asBoolean());

        JsonNode notebook=postJson("/workspaces/"+learner.workspace()+"/notebook-drafts",learner.session(),
            Map.of("attemptIds",List.of(attempt.path("id").asText()),"title","Assisted correction"),key(),201,"Artifact");
        String id=notebook.path("id").asText();
        JsonNode version=getJson("/artifacts/"+id+"/revisions?revision=1",learner.session(),"ArtifactRevision");
        JsonNode block=version.path("content").path("blocks").get(0);assertTrue(names(block.path("allowedActions")).contains("requestHint"));
        var action=(ObjectNode)json.tree(json.write(Map.of("artifactRevision",1,"blockId",block.path("id").asText(),
            "action","requestHint","expectedBlockRevision",1,"selection","","hintLevel",2)));action.putNull("answer");
        String idem=key();JsonNode result=postJson("/notebooks/"+id+"/actions",learner.session(),action,idem,200,"NotebookActionResult");
        assertEquals("COMPLETED",result.path("status").asText());assertEquals(texts.get(1),result.path("message").asText());
        assertTrue(result.path("compileJobId").isNull());assertTrue(result.path("attemptId").isNull());
        assertEquals(result,postJson("/notebooks/"+id+"/actions",learner.session(),action,idem,200,"NotebookActionResult"));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_hint_receipt WHERE user_id=?",Integer.class,learner.owner().toString()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compile_job WHERE user_id=?",Integer.class,learner.owner().toString()));
        drain();assertEquals(progress.path("items"),getJson("/me/progress",learner.session(),"Progress").path("items"));
    }

    @Test void advertisedHintsStillEnforceOwnershipVersionAndLevel() throws Exception {
        Learner owner=learner(),other=learner();String path="/exercises/"+owner.exercise().path("id").asText()+"/hints";
        postJson(path,other.session(),hintInput(owner,1),key(),404,"Error");
        var input=(ObjectNode)json.tree(json.write(hintInput(owner,1)));input.put("exerciseRevision",999);
        postJson(path,owner.session(),input,key(),404,"Error");
        postJson(path,owner.session(),hintInput(owner,4),key(),400,"Error");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_hint_receipt WHERE user_id IN (?,?)",Integer.class,owner.owner().toString(),other.owner().toString()));
    }
}
