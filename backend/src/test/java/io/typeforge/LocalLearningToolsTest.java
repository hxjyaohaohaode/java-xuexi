package io.typeforge;

import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.orchestration.AgentTool;
import io.typeforge.orchestration.DurableAgentRuntime;
import io.typeforge.orchestration.DurableAgentRuntime.OutputReference;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.tutor.EvidenceWorkflow;
import io.typeforge.tutor.LocalContextTool;
import io.typeforge.tutor.LocalSearchTool;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Typed tool contracts are checked against actual admitted SQL runs and published source evidence. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:typeforge-local-tools;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH")
@ActiveProfiles({"test","demo"})
class LocalLearningToolsTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired SchemaCatalog schemas;
    @Autowired WorkspaceService workspaces;
    @Autowired DurableAgentRuntime runtime;
    @Autowired EvidenceWorkflow workflow;
    @Autowired LocalContextTool contextTool;
    @Autowired LocalSearchTool searchTool;
    private UUID owner;
    private UUID workspace;
    private WorkspaceService.Context snapshot;
    private DurableAgentRuntime.RunView run;
    private record Call(DurableAgentRuntime.Lease lease,DurableAgentRuntime.ToolView tool,AgentTool.Context context) {}

    @BeforeEach void fixture() {
        owner=user();
        workspace=workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"Typed tool fixture","ON_DEMAND",key()).id();
        var goal=workspaces.createGoal(owner,workspace,"Explain PECS",List.of("Cite reviewed sources"),key());
        snapshot=workspaces.createContext(owner,workspace);
        run=workflow.create(owner,workspace,goal.id(),snapshot.id(),"PECS 通配符",key());
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private UUID user() {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",id.toString(),"tools-"+id,"fixture-is-not-a-login-credential");
        return id;
    }
    private Call claim(String tool,Object input) {
        var lease=runtime.claim(owner,workspace,run.id(),run.runEpoch(),30_000).get(0);
        var execution=runtime.reserveTool(owner,workspace,lease,tool,"typed-read-v1",input,0,0);
        runtime.markToolRunning(owner,workspace,lease,execution.id(),null);
        return new Call(lease,execution,new AgentTool.Context(owner,workspace,run.id(),lease.planRevision(),lease.runEpoch(),execution.executionKey(),lease.expiresAt()));
    }
    private void completeContext() {
        var input=new LocalContextTool.Input(snapshot.id());var call=claim(contextTool.name(),input);
        var result=contextTool.execute(input,call.context());
        var refs=List.of(new OutputReference("SNAPSHOT",result.id(),result.workspaceRevision(),result.contentHash()));
        runtime.settleTool(owner,workspace,run.id(),call.tool().id(),DurableAgentRuntime.ToolOutcome.SUCCEEDED,0,0,refs);
        runtime.complete(owner,workspace,call.lease(),refs);
    }

    @Test void contextToolReturnsTheExistingValidatedSnapshotWithoutCreatingState() {
        var input=new LocalContextTool.Input(snapshot.id());var call=claim(contextTool.name(),input);
        int snapshots=jdbc.queryForObject("SELECT COUNT(*) FROM context_snapshot WHERE workspace_id=?",Integer.class,workspace.toString());
        long events=workspaces.get(owner,workspace).eventSequence();
        var result=contextTool.execute(input,call.context());
        assertEquals(snapshot,result);schemas.validate(contextTool.outputSchemaId(),result);
        assertEquals(snapshots,jdbc.queryForObject("SELECT COUNT(*) FROM context_snapshot WHERE workspace_id=?",Integer.class,workspace.toString()));
        assertEquals(events,workspaces.get(owner,workspace).eventSequence());
        assertEquals("ToolResourceInput",contextTool.inputSchemaId());assertEquals("ContextSnapshot",contextTool.outputSchemaId());
    }

    @Test void searchToolReturnsSchemaValidatedRealSourceHashesWithinTheFixedRelease() {
        completeContext();
        var input=new LocalSearchTool.Input("PECS 通配符",DemoContentInitializer.RELEASE_ID,3);var call=claim(searchTool.name(),input);
        var result=searchTool.execute(input,call.context());schemas.validate(searchTool.outputSchemaId(),result);
        assertFalse(result.items().isEmpty());assertFalse(result.insufficientEvidence());assertTrue(result.items().size()<=3);
        assertEquals(DemoContentInitializer.RELEASE_ID,result.releaseId());
        for(var evidence:result.items()) {
            assertEquals(DemoContentInitializer.RELEASE_ID,evidence.releaseId());
            String body=jdbc.queryForObject("SELECT body FROM knowledge_chunk WHERE id=? AND revision=?",String.class,evidence.chunkId().toString(),evidence.revision());
            assertEquals(json.sha256(body),evidence.contentHash());assertFalse(evidence.source().url().isBlank());
        }
    }

    @Test void zeroEvidenceRemainsAnExplicitEmptyToolResult() {
        completeContext();
        var input=new LocalSearchTool.Input("noevidencetoken"+key().replace("-",""),DemoContentInitializer.RELEASE_ID,3);
        var result=searchTool.execute(input,claim(searchTool.name(),input).context());
        assertTrue(result.insufficientEvidence());assertTrue(result.items().isEmpty());schemas.validate("SearchResult",result);
    }

    @Test void anotherPublishedReleaseIsRejectedEvenWithARealAdmittedExecution() {
        completeContext();UUID foreignRelease=UUID.randomUUID();
        jdbc.update("INSERT INTO content_release(id,version,status,manifest_hash,created_by,published_at) VALUES(?,?,'PUBLISHED',?,?,?)",
                foreignRelease.toString(),"foreign-"+foreignRelease.toString().substring(0,16),json.hash(foreignRelease),owner.toString(),Timestamp.from(Instant.now()));
        var input=new LocalSearchTool.Input("PECS",foreignRelease,3);var call=claim(searchTool.name(),input);
        assertEquals(400,assertThrows(DomainException.class,()->searchTool.execute(input,call.context())).status());
        assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM tool_execution WHERE id=?",String.class,call.tool().id().toString()));
    }

    @Test void privateOrUnadmittedSnapshotsCannotBeReadByKnowingTheirIds() {
        UUID other=user();UUID otherWorkspace=workspaces.create(other,DemoContentInitializer.RELEASE_ID,"Private workspace","ON_DEMAND",key()).id();
        var privateSnapshot=workspaces.createContext(other,otherWorkspace);
        var input=new LocalContextTool.Input(privateSnapshot.id());var call=claim(contextTool.name(),input);
        assertEquals(404,assertThrows(DomainException.class,()->contextTool.execute(input,call.context())).status());
        var forgedOwner=new AgentTool.Context(other,workspace,run.id(),1,1,call.tool().executionKey(),call.lease().expiresAt());
        assertEquals(404,assertThrows(DomainException.class,()->contextTool.execute(input,forgedOwner)).status());
        assertEquals(404,assertThrows(DomainException.class,()->workspaces.readContext(owner,workspace,privateSnapshot.id())).status());
    }

    @Test void inputHashAndRunEpochMustMatchTheActualAdmittedToolExecution() {
        var input=new LocalContextTool.Input(snapshot.id());var call=claim(contextTool.name(),input);
        var changed=new LocalContextTool.Input(UUID.randomUUID());
        assertEquals("STALE_TOOL_CONTEXT",assertThrows(DomainException.class,()->contextTool.execute(changed,call.context())).code());
        runtime.command(owner,workspace,run.id(),1,DurableAgentRuntime.Command.PAUSE);
        assertEquals("STALE_TOOL_CONTEXT",assertThrows(DomainException.class,()->contextTool.execute(input,call.context())).code());
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM tool_execution WHERE id=?",String.class,call.tool().id().toString()));
    }

    @Test void toolInputAndOutputSchemasRejectInvalidValuesBeforeReturningData() {
        assertEquals(400,assertThrows(DomainException.class,()->contextTool.execute(new LocalContextTool.Input(null),null)).status());
        assertEquals(400,assertThrows(DomainException.class,()->searchTool.execute(new LocalSearchTool.Input("PECS",DemoContentInitializer.RELEASE_ID,9),null)).status());
        assertThrows(DomainException.class,()->schemas.validate("ToolResourceInput",Map.of("id",snapshot.id(),"ownerId",owner)));
        // A syntactically valid SQL bigint still exceeds the public safe-integer output contract.
        long unsupportedSequence=9_007_199_254_740_992L;
        String validHash=json.hash(Map.of("workspaceId",workspace,"revision",snapshot.workspaceRevision(),"sequence",unsupportedSequence,"references",snapshot.references()));
        jdbc.update("UPDATE context_snapshot SET event_sequence=?,content_hash=? WHERE id=?",unsupportedSequence,validHash,snapshot.id().toString());
        var input=new LocalContextTool.Input(snapshot.id());var call=claim(contextTool.name(),input);
        var rejected=assertThrows(DomainException.class,()->contextTool.execute(input,call.context()));
        assertEquals("INVALID_INPUT",rejected.code());assertTrue(rejected.getMessage().contains("ContextSnapshot"));
    }

    @Test void workflowPersistsHashesOfTheRealSchemaInputsForBothTypedTools() {
        workflow.advance(owner,workspace,run.id());
        assertEquals("COMPLETED",runtime.get(owner,workspace,run.id()).status());
        var rows=jdbc.queryForList("SELECT t.tool_key,t.input_hash,t.status FROM tool_execution t JOIN agent_step s ON s.id=t.step_id WHERE s.run_id=?",run.id().toString());
        assertEquals(2,rows.size());
        for(var row:rows) {
            String expected=row.get("tool_key").equals("retrieveContext")?json.hash(new LocalContextTool.Input(snapshot.id())):
                json.hash(new LocalSearchTool.Input("PECS 通配符",DemoContentInitializer.RELEASE_ID,8));
            assertEquals(expected,row.get("input_hash"));assertEquals("SUCCEEDED",row.get("status"));
        }
    }
}
