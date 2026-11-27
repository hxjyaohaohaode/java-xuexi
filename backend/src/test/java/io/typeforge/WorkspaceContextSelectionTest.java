package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.exercise.LearningService;
import io.typeforge.identity.IdentityService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Explicit selection admits old, real grading evidence without accepting caller-supplied references or ownership. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:context-attempt-selection;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH")
@ActiveProfiles({"test","demo"})
@AutoConfigureMockMvc
class WorkspaceContextSelectionTest {
    private static final UUID RELEASE=DemoContentInitializer.RELEASE_ID;
    private static final String PASSWORD="context-selection-fixture-password-123";
    @Autowired WorkspaceService workspaces;
    @Autowired io.typeforge.workspace.LearningContextJournal journal;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired LearningService learning;
    @Autowired IdentityService identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired SchemaCatalog schemas;
    @Autowired MockMvc mvc;
    private UUID owner,workspace,exercise,goal;
    private String username;
    private static String key(){return UUID.randomUUID().toString();}
    @BeforeEach void scope(){
        username="context-select-"+key();owner=identities.register(username,PASSWORD).userId();
        workspace=workspaces.create(owner,RELEASE,"Explicit context evidence","ON_DEMAND",key()).id();
        goal=workspaces.createGoal(owner,workspace,"复查 Java 泛型 PECS 的旧错题",List.of("只使用自己选定的判分记录"),key()).id();
        exercise=UUID.fromString(jdbc.queryForObject("SELECT e.id FROM exercise e JOIN release_exercise re ON re.exercise_id=e.id AND re.exercise_revision=e.revision WHERE re.release_id=? AND e.kind='SINGLE' AND e.status='REVIEWED' ORDER BY e.id LIMIT 1",String.class,RELEASE.toString()));
    }
    private UUID graded(UUID user,UUID space){
        JsonNode spec=json.tree(jdbc.queryForObject("SELECT answer_spec FROM exercise WHERE id=? AND revision=1",String.class,exercise.toString()));
        String expected=spec.path("optionIds").get(0).asText(),choice=null;
        for(JsonNode option:spec.path("options"))if(!option.path("id").asText().equals(expected)){choice=option.path("id").asText();break;}
        assertNotNull(choice);
        return learning.submit(user,space,RELEASE,exercise,1,json.tree(json.write(Map.of("kind","CHOICE","optionIds",List.of(choice)))),key()).id();
    }
    private UUID pending(){
        UUID id=UUID.randomUUID();
        // A persisted unfinished request fixture has no grade/hash and cannot be used as an ATTEMPT reference.
        jdbc.update("INSERT INTO exercise_attempt(id,user_id,release_id,exercise_id,exercise_revision,answer,status,hint_level,grader_version) VALUES(?,?,?,?,1,?,'PENDING',0,'pending-fixture')",id.toString(),owner.toString(),RELEASE.toString(),exercise.toString(),json.write(Map.of("kind","CHOICE","optionIds",List.of("unfinished"))));
        jdbc.update("INSERT INTO workspace_attempt(workspace_id,attempt_id) VALUES(?,?)",workspace.toString(),id.toString());return id;
    }
    private List<UUID> attemptIds(WorkspaceService.Context context){return context.references().stream().filter(ref->ref.kind().equals("ATTEMPT")).map(Reference::id).toList();}

    @Test void journalReopensAfterRestartAndRehydratesOnlyPinnedSourcesIncludingAnOlderAttempt() {
        UUID concept=UUID.fromString(jdbc.queryForObject("SELECT concept_id FROM exercise_concept WHERE exercise_id=? LIMIT 1",String.class,exercise.toString()));
        var old=graded(owner,workspace);
        for(int i=0;i<4;i++) graded(owner,workspace);
        jdbc.update("UPDATE exercise_attempt SET created_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(300)),old.toString());
        var memory=workspaces.stateMemory(owner,workspace,"先预测输出 "+"保持这一已声明偏好。".repeat(80)+" 最后独立验证",key());
        var snapshot=workspaces.createContext(owner,workspace);
        var checkpoint=journal.capture(owner,workspace,snapshot.id(),concept);
        assertTrue(checkpoint.originalChars()>checkpoint.retainedChars());
        assertTrue(checkpoint.omittedCount()>=2);
        assertTrue(checkpoint.facts().stream().noneMatch(item->item.sourceId().equals(old)));
        var reopened=new io.typeforge.workspace.LearningContextJournal(jdbc,tx,workspaces,json);
        assertEquals(checkpoint,reopened.read(owner,workspace,checkpoint.id()));
        assertEquals(checkpoint,reopened.capture(owner,workspace,snapshot.id(),concept));
        var fact=reopened.recall(owner,workspace,checkpoint.id(),"ATTEMPT",old);
        assertTrue(fact.text().contains("得分"));assertEquals(old,fact.sourceId());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM learning_context_checkpoint WHERE id=?",Integer.class,checkpoint.id().toString()));
        var different=UUID.fromString(jdbc.queryForObject("SELECT concept_id FROM release_concept WHERE release_id=? AND concept_id NOT IN (SELECT concept_id FROM exercise_concept WHERE exercise_id=?) LIMIT 1",String.class,RELEASE.toString(),exercise.toString()));
        var elsewhere=reopened.capture(owner,workspace,snapshot.id(),different);
        assertTrue(elsewhere.facts().stream().noneMatch(item->item.kind().equals("ATTEMPT")));
        assertThrows(DomainException.class,()->reopened.recall(owner,workspace,elsewhere.id(),"ATTEMPT",old));
        UUID other=identities.register("journal-other-"+key(),PASSWORD).userId();
        assertThrows(DomainException.class,()->reopened.read(other,workspace,checkpoint.id()));
        workspaces.revokeMemory(owner,workspace,memory.id(),memory.revision());
        assertThrows(DomainException.class,()->reopened.read(owner,workspace,checkpoint.id()));
        assertThrows(DomainException.class,()->reopened.recall(owner,workspace,checkpoint.id(),"ATTEMPT",old));
        var fresh=reopened.capture(owner,workspace,workspaces.createContext(owner,workspace).id(),concept);
        assertTrue(fresh.facts().stream().noneMatch(item->item.sourceId().equals(memory.id())));
    }

    @Test void explicitSelectionIncludesOlderGradedAttemptsInExactCallerOrder(){
        var history=new ArrayList<UUID>();Instant first=Instant.now().minusSeconds(30);
        for(int i=0;i<10;i++){UUID id=graded(owner,workspace);history.add(id);jdbc.update("UPDATE exercise_attempt SET created_at=? WHERE id=?",Timestamp.from(first.plusSeconds(i)),id.toString());}
        var recent=workspaces.createContext(owner,workspace);assertEquals(8,attemptIds(recent).size());assertFalse(attemptIds(recent).contains(history.get(0)));assertFalse(attemptIds(recent).contains(history.get(1)));
        List<UUID> chosen=List.of(history.get(1),history.get(0));var explicit=workspaces.createContext(owner,workspace,chosen);
        assertEquals(chosen,attemptIds(explicit));assertTrue(explicit.references().stream().anyMatch(ref->ref.kind().equals("GOAL") && ref.id().equals(goal)));
        assertTrue(explicit.references().size()<=64);assertEquals(explicit,workspaces.readContext(owner,workspace,explicit.id()));schemas.validate("ContextSnapshot",explicit);
        assertNotEquals(recent.contentHash(),explicit.contentHash());assertFalse(attemptIds(explicit).contains(history.get(9)));
    }
    @Test void privateWrongWorkspaceUnfinishedAndInvalidSelectionsNeverPersistASnapshot(){
        UUID own=graded(owner,workspace),unfinished=pending();
        UUID other=identities.register("context-other-"+key(),PASSWORD).userId();
        UUID foreignSpace=workspaces.create(other,RELEASE,"Foreign space","ON_DEMAND",key()).id();UUID foreign=graded(other,foreignSpace);
        UUID sameOwnerSpace=workspaces.create(owner,RELEASE,"Another owned space","ON_DEMAND",key()).id();UUID wrongSpace=graded(owner,sameOwnerSpace);
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM context_snapshot WHERE workspace_id=?",Integer.class,workspace.toString());
        for(UUID invalid:List.of(foreign,wrongSpace,unfinished,UUID.randomUUID()))assertEquals(404,assertThrows(DomainException.class,()->workspaces.createContext(owner,workspace,List.of(invalid))).status());
        assertEquals(400,assertThrows(DomainException.class,()->workspaces.createContext(owner,workspace,List.of(own,own))).status());
        assertEquals(400,assertThrows(DomainException.class,()->workspaces.createContext(owner,workspace,List.of())).status());
        assertEquals(400,assertThrows(DomainException.class,()->workspaces.createContext(owner,workspace,java.util.stream.IntStream.range(0,9).mapToObj(i->UUID.randomUUID()).toList())).status());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM context_snapshot WHERE workspace_id=?",Integer.class,workspace.toString()));
    }
    @Test void authenticatedHttpAcceptsOptionalSelectionAndKeepsSchemaAndCsrfStrict() throws Exception {
        UUID selected=graded(owner,workspace);var session=HttpSessionFixture.login(mvc,json,username,PASSWORD);String path="/api/v1/workspaces/"+workspace+"/context";
        var legacy=mvc.perform(post(path).session(session).with(csrf())).andReturn().getResponse();assertEquals(201,legacy.getStatus(),legacy.getContentAsString());schemas.validate("ContextSnapshot",json.tree(legacy.getContentAsString()));
        String body=json.write(Map.of("attemptIds",List.of(selected)));
        var created=mvc.perform(post(path).session(session).with(csrf()).contentType("application/json").content(body)).andReturn().getResponse();assertEquals(201,created.getStatus(),created.getContentAsString());
        var context=json.read(created.getContentAsString(),WorkspaceService.Context.class);assertEquals(List.of(selected),attemptIds(context));
        assertEquals(403,mvc.perform(post(path).session(session).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
        for(String invalid:List.of("{}","{\"attemptIds\":[]}","{\"attemptIds\":[\"not-a-uuid\"]}",json.write(Map.of("attemptIds",List.of(selected),"references",List.of())),json.write(Map.of("attemptIds",List.of(selected),"ownerId",UUID.randomUUID())))) {
            assertEquals(400,mvc.perform(post(path).session(session).with(csrf()).contentType("application/json").content(invalid)).andReturn().getResponse().getStatus(),invalid);
        }
        assertEquals(404,mvc.perform(post(path).session(session).with(csrf()).contentType("application/json").content(json.write(Map.of("attemptIds",List.of(pending()))))).andReturn().getResponse().getStatus());
    }
}
