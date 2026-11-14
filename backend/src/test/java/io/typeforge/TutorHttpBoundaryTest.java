package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.tutor.TutorWorker;
import io.typeforge.workspace.WorkspaceService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** HTTP/schema/CSRF gates with real SQL ownership; login itself is covered by the full-loop suite. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:typeforge-tutor-boundary;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH","typeforge.tutor.worker-enabled=false","typeforge.tutor.provider-url=","typeforge.tutor.api-key=","typeforge.tutor.model="})
@AutoConfigureMockMvc
@ActiveProfiles({"test","demo"})
@Timeout(30)
class TutorHttpBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired SchemaCatalog schemas;
    @Autowired WorkspaceService workspaces;
    @Autowired TutorWorker worker;
    private UUID owner;
    private UUID workspace;
    private UUID snapshot;
    private String username;
    private static String key() { return UUID.randomUUID().toString(); }
    @BeforeEach void fixture() {
        owner=UUID.randomUUID();username="tutor-http-boundary-"+owner;
        jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",owner.toString(),username,"fixture-not-a-login-credential");
        workspace=workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"Tutor HTTP boundary","ON_DEMAND",key()).id();
        snapshot=workspaces.createContext(owner,workspace).id();
    }
    private JsonNode call(String method,String path,Object body,int status,String schema) throws Exception {
        var request=request(HttpMethod.valueOf(method),"/api/v1"+path).with(user(username).roles("LEARNER")).with(csrf().asHeader()).header("Idempotency-Key",key());
        if(body!=null) request.contentType("application/json").content(json.write(body));
        var response=mvc.perform(request).andReturn().getResponse();assertEquals(status,response.getStatus(),response.getContentAsString());
        JsonNode value=json.tree(response.getContentAsString());schemas.validate(status>=400?"Error":schema,value);return value;
    }
    private JsonNode conversation() throws Exception { return call("POST","/tutor/conversations",Map.of("workspaceId",workspace,"releaseId",DemoContentInitializer.RELEASE_ID,"title","HTTP conversation"),201,"ConversationSummary"); }
    private Map<String,Object> input(JsonNode conversation,String mode) { return Map.of("conversationId",conversation.path("id").asText(),"snapshotId",snapshot,"question","PECS 通配符","hintLevel",1,"mode",mode); }

    @Test void completeHttpJourneyIncludesConversationRequestEvidenceAndDraftDocument() throws Exception {
        var conversation=conversation();String conversationId=conversation.path("id").asText();
        var queued=call("POST","/tutor/requests",input(conversation,"EVIDENCE_ONLY"),202,"TutorResult");assertEquals("QUEUED",queued.path("status").asText());
        assertTrue(worker.runOne());String id=queued.path("id").asText();
        var answer=call("GET","/tutor/requests/"+id,null,200,"TutorResult");assertEquals("COMPLETED",answer.path("status").asText());assertEquals("CURRENT",answer.path("sourceState").asText());
        var saved=call("POST","/tutor/requests/"+id+"/document",Map.of("title","Learning from PECS sources"),201,"TutorDocumentResult");assertEquals("DRAFT",saved.path("reviewStatus").asText());
        var history=call("GET","/tutor/conversations/"+conversationId,null,200,"Conversation");assertEquals(id,history.path("messages").get(0).path("id").asText());
        var list=call("GET","/tutor/conversations?workspaceId="+workspace,null,200,"ConversationList");assertEquals(conversationId,list.path("items").get(0).path("id").asText());
        call("POST","/tutor/requests/"+id+"/cancel",Map.of("expectedEpoch",answer.path("epoch").asLong()),409,null);
    }
    @Test void authCsrfUnknownFieldsAndUnavailableProviderAreControlledBeforeMutation() throws Exception {
        assertEquals(401,mvc.perform(get("/api/v1/tutor/conversations")).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post("/api/v1/tutor/conversations").with(user(username).roles("LEARNER")).header("Idempotency-Key",key()).contentType("application/json").content(json.write(Map.of("workspaceId",workspace,"releaseId",DemoContentInitializer.RELEASE_ID,"title","No CSRF")))).andReturn().getResponse().getStatus());
        var conversation=conversation();
        call("POST","/tutor/requests",input(conversation,"MODEL"),503,null);
        var extra=new java.util.LinkedHashMap<>(input(conversation,"EVIDENCE_ONLY"));extra.put("apiKey","must-not-be-stored");
        call("POST","/tutor/requests",extra,400,null);
        extra.remove("apiKey");extra.put("userId",UUID.randomUUID());call("POST","/tutor/requests",extra,400,null);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tutor_request WHERE conversation_id=?",Integer.class,conversation.path("id").asText()));
        call("GET","/tutor/conversations?limit=101",null,400,null);
    }
    @Test void cancelUsesExpectedEpochAndForeignRequestIdsRemainPrivate() throws Exception {
        var conversation=conversation();var queued=call("POST","/tutor/requests",input(conversation,"EVIDENCE_ONLY"),202,"TutorResult");String id=queued.path("id").asText();
        call("POST","/tutor/requests/"+id+"/cancel",Map.of(),400,null);
        call("POST","/tutor/requests/"+id+"/cancel",Map.of("expectedEpoch",9007199254740992L),400,null);
        var cancelled=call("POST","/tutor/requests/"+id+"/cancel",Map.of("expectedEpoch",1),200,"TutorResult");assertEquals("CANCELLED",cancelled.path("status").asText());assertEquals(2,cancelled.path("epoch").asLong());
        call("POST","/tutor/requests/"+id+"/cancel",Map.of("expectedEpoch",1),200,"TutorResult");
        call("POST","/tutor/requests/"+id+"/document",Map.of("title","Cannot materialize cancelled answer"),409,null);
        UUID other=UUID.randomUUID();String otherName="tutor-other-"+other;
        jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",other.toString(),otherName,"fixture-not-a-login-credential");
        var foreign=mvc.perform(get("/api/v1/tutor/requests/"+id).with(user(otherName).roles("LEARNER"))).andReturn().getResponse();assertEquals(404,foreign.getStatus());schemas.validate("Error",json.tree(foreign.getContentAsString()));
        var foreignCancel=mvc.perform(post("/api/v1/tutor/requests/"+id+"/cancel").with(user(otherName).roles("LEARNER")).with(csrf().asHeader()).contentType("application/json").content("{\"expectedEpoch\":2}")).andReturn().getResponse();assertEquals(404,foreignCancel.getStatus());
    }
}
