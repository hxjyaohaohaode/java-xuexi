package io.typeforge;

import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.orchestration.DurableAgentRuntime;
import io.typeforge.orchestration.PlanValidator;
import io.typeforge.shared.JsonCodec;
import io.typeforge.tutor.EvidenceWorkflow;
import io.typeforge.tutor.LocalContextTool;
import io.typeforge.tutor.LocalSearchTool;
import io.typeforge.workspace.WorkspaceAccess;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual scheduling over persisted runs, with deliberately invalid inputs and a lost worker. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:typeforge-workflow-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH")
@ActiveProfiles({"test","demo"})
class EvidenceWorkflowRecoveryTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JsonCodec json;
    @Autowired WorkspaceService workspaces;
    @Autowired WorkspaceAccess access;
    @Autowired DurableAgentRuntime runtime;
    @Autowired LocalContextTool contextTool;
    @Autowired LocalSearchTool searchTool;
    @Autowired EventJournal journal;
    @Autowired PlanValidator validator;
    private UUID owner;
    private record Fixture(UUID workspace,UUID run,WorkspaceService.Memory memory) {}

    @BeforeEach void account() {
        owner=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",
                owner.toString(),"recovery-"+owner,"fixture-is-not-a-login-credential");
    }
    private EvidenceWorkflow worker(DurableAgentRuntime engine) {
        return new EvidenceWorkflow(engine,workspaces,contextTool,searchTool,jdbc,tx,json,true);
    }
    private Fixture create(boolean includeMemory) {
        UUID workspace=workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"Recoverable evidence workflow","ON_DEMAND",UUID.randomUUID().toString()).id();
        var goal=workspaces.createGoal(owner,workspace,"Explain Java wildcard bounds",List.of("Use actual versioned sources"),UUID.randomUUID().toString());
        var memory=includeMemory?workspaces.stateMemory(owner,workspace,"Prefer concise examples",UUID.randomUUID().toString()):null;
        var snapshot=workspaces.createContext(owner,workspace);
        var run=worker(runtime).create(owner,workspace,goal.id(),snapshot.id(),"PECS 通配符",UUID.randomUUID().toString());
        return new Fixture(workspace,run.id(),memory);
    }

    @Test void aFullBatchOfRevokedSnapshotsCannotPermanentlyStarveAHealthyWorkspace() {
        var invalid=new ArrayList<Fixture>();
        for(int i=0;i<16;i++) {
            var fixture=create(true);invalid.add(fixture);
            workspaces.revokeMemory(owner,fixture.workspace(),fixture.memory().id(),fixture.memory().revision());
            jdbc.update("UPDATE local_evidence_workflow SET created_at=? WHERE run_id=?",
                    Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),fixture.run().toString());
        }
        var healthy=create(false);
        var dispatcher=worker(runtime);
        dispatcher.dispatch();
        for(var fixture:invalid) {
            var stopped=runtime.get(owner,fixture.workspace(),fixture.run());
            assertEquals("WAITING_USER",stopped.status());assertEquals(2,stopped.runEpoch());
        }
        dispatcher.dispatch();
        var completed=runtime.get(owner,healthy.workspace(),healthy.run());
        assertEquals("COMPLETED",completed.status());
        assertTrue(completed.steps().stream().flatMap(step->step.outputRefs().stream())
                .anyMatch(ref->ref.kind().equals("KNOWLEDGE_CHUNK")));
    }

    @Test void aReconstructedWorkerResumesAnExpiredClaimWithoutRepeatingCommittedSteps() {
        var fixture=create(false);
        var lost=runtime.claim(owner,fixture.workspace(),fixture.run(),1,30_000).get(0);
        assertEquals("context",lost.stepKey());
        jdbc.update("UPDATE agent_step SET lease_until=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),lost.stepId().toString());
        var reconstructed=new DurableAgentRuntime(jdbc,tx,json,access,journal,Clock.systemUTC(),validator);
        worker(reconstructed).advance(owner,fixture.workspace(),fixture.run());
        var recovered=reconstructed.get(owner,fixture.workspace(),fixture.run());
        assertEquals("COMPLETED",recovered.status());
        assertEquals(2,recovered.steps().stream().filter(step->step.key().equals("context")).findFirst().orElseThrow().attempts());
        assertEquals(1,recovered.steps().stream().filter(step->step.key().equals("evidence")).findFirst().orElseThrow().attempts());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM tool_execution t JOIN agent_step s ON s.id=t.step_id WHERE s.run_id=?",Integer.class,fixture.run().toString()));
        worker(reconstructed).advance(owner,fixture.workspace(),fixture.run());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM tool_execution t JOIN agent_step s ON s.id=t.step_id WHERE s.run_id=?",Integer.class,fixture.run().toString()));
    }
}
