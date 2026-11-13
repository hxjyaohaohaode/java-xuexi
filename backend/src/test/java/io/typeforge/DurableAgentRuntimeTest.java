package io.typeforge;

import io.typeforge.operations.events.EventJournal;
import io.typeforge.orchestration.DurableAgentRuntime;
import io.typeforge.orchestration.DurableAgentRuntime.Command;
import io.typeforge.orchestration.DurableAgentRuntime.Lease;
import io.typeforge.orchestration.DurableAgentRuntime.OutputReference;
import io.typeforge.orchestration.DurableAgentRuntime.RunView;
import io.typeforge.orchestration.DurableAgentRuntime.ToolOutcome;
import io.typeforge.orchestration.PlanValidator;
import io.typeforge.orchestration.TaskPlan;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceAccess;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real database transactions, restart reconstruction, parallel callers and deliberately stale workers. */
@SpringBootTest
@ActiveProfiles("test")
class DurableAgentRuntimeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JsonCodec json;
    @Autowired WorkspaceAccess access;
    @Autowired WorkspaceService workspaces;
    @Autowired EventJournal journal;
    @Autowired PlanValidator validator;
    private MutableClock clock;
    private DurableAgentRuntime runtime;
    private UUID owner;
    private UUID workspace;
    private UUID goal;
    private UUID snapshot;
    private UUID release;
    private OutputReference knowledge;
    private static final String GOAL = "Explain why extends and super differ";

    @BeforeEach void prepareIndependentRealDatabaseFixture() {
        clock = new MutableClock(Instant.now());
        runtime = runtimeInstance();
        owner = UUID.randomUUID();
        release = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,roles,status) VALUES(?,?,?,?,?)",
                owner.toString(), "runtime-" + owner, "not-a-login-credential", "[\"LEARNER\"]", "ACTIVE");
        jdbc.update("INSERT INTO content_release(id,version,status,manifest_hash,created_by,published_at) VALUES(?,?,?,?,?,?)",
                release.toString(), "rt-" + release.toString().substring(0, 20), "PUBLISHED", json.hash(release), owner.toString(), Timestamp.from(clock.instant()));
        workspace = workspaces.create(owner, release, "Runtime integration fixture", "ON_DEMAND", UUID.randomUUID().toString()).id();
        goal = workspaces.createGoal(owner, workspace, GOAL, List.of("Refer to versioned generic type evidence"), UUID.randomUUID().toString()).id();
        snapshot = workspaces.createContext(owner, workspace).id();
        UUID concept = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        String content = "A producer with an upper bounded wildcard accepts no new non-null element of an arbitrary captured type.";
        String hash = json.sha256(content);
        jdbc.update("INSERT INTO concept(id,code,title) VALUES(?,?,?)", concept.toString(), "T" + concept.toString().replace("-", "").substring(0, 14), "Wildcard fixture");
        jdbc.update("INSERT INTO knowledge_chunk(id,revision,concept_id,title,body,content_hash,status,author_id) VALUES(?,?,?,?,?,?,?,?)",
                chunk.toString(), 1, concept.toString(), "Reviewed fixture", content, hash, "REVIEWED", owner.toString());
        jdbc.update("INSERT INTO release_chunk(release_id,chunk_id,chunk_revision) VALUES(?,?,?)", release.toString(), chunk.toString(), 1);
        knowledge = new OutputReference("KNOWLEDGE_CHUNK", chunk, 1, hash);
    }

    private DurableAgentRuntime runtimeInstance() { return new DurableAgentRuntime(jdbc, tx, json, access, journal, clock, validator); }
    private TaskPlan.Step read(String key, String... dependencies) {
        return new TaskPlan.Step(key, "retrieval-researcher", List.of(dependencies), Set.of("knowledge:" + release), Set.of(), Set.of("searchEvidence"), true, List.of("Reference integrity"), 0);
    }
    private TaskPlan plan(TaskPlan.Step... steps) {
        return new TaskPlan(UUID.randomUUID(), 1, 1, GOAL, new TaskPlan.Budget(1_000, 120_000, 10_000, 3), List.of(steps));
    }
    private RunView create(TaskPlan plan) { return runtime.create(owner, workspace, goal, snapshot, plan, UUID.randomUUID().toString()); }
    private Lease claim(RunView run) { return runtime.claim(owner, workspace, run.id(), run.runEpoch(), 30_000).get(0); }
    private DomainException rejects(String code, Runnable work) {
        DomainException failure = assertThrows(DomainException.class, work::run);
        assertEquals(code, failure.code());
        return failure;
    }

    @Test void planAndReceiptsSurviveReconstructingRuntimeWithSameDatabase() {
        RunView run = create(plan(read("evidence"), read("explain", "evidence")));
        Lease first = claim(run);
        assertEquals("evidence", first.stepKey());
        assertTrue(runtime.claim(owner, workspace, run.id(), 1, 30_000).isEmpty());
        runtime.complete(owner, workspace, first, List.of(knowledge));
        DurableAgentRuntime restarted = runtimeInstance();
        assertEquals("SUCCEEDED", restarted.get(owner, workspace, run.id()).steps().get(0).status());
        Lease next = restarted.claim(owner, workspace, run.id(), 1, 30_000).get(0);
        assertEquals("explain", next.stepKey());
        assertEquals("COMPLETED", restarted.complete(owner, workspace, next, List.of(knowledge)).status());
        assertEquals("COMPLETED", restarted.complete(owner, workspace, next, List.of(knowledge)).status());
        List<DurableAgentRuntime.RunEvent> events = restarted.events(owner, workspace, run.id(), 0, 100);
        assertEquals(6, events.size());
        for (int i = 0; i < events.size(); i++) assertEquals(i + 1L, events.get(i).sequence());
        assertEquals(events.subList(2, events.size()), restarted.events(owner, workspace, run.id(), 2, 100));
    }

    @Test void idempotencyReplaysSameRunAndRejectsDifferentPayloadWithoutWriting() {
        TaskPlan proposed = plan(read("evidence"));
        String key = UUID.randomUUID().toString();
        RunView original = runtime.create(owner, workspace, goal, snapshot, proposed, key);
        assertEquals(original.id(), runtime.create(owner, workspace, goal, snapshot, proposed, key).id());
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE workspace_id=?", Long.class, workspace.toString());
        rejects("IDEMPOTENCY_CONFLICT", () -> runtime.create(owner, workspace, goal, snapshot, plan(read("other")), key));
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE workspace_id=?", Long.class, workspace.toString()));
    }

    @Test void graphAndRoleValidationCannotGrantAnUnregisteredCapability() {
        TaskPlan.Step injected = new TaskPlan.Step("unsafe", "retrieval-researcher", List.of(), Set.of(), Set.of(), Set.of("shell"), true, List.of("run"), 0);
        rejects("INVALID_PLAN", () -> create(plan(injected)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE workspace_id=?", Integer.class, workspace.toString()));
    }

    @Test void anotherOwnerCannotReadOrControlRunEvenWhenAllIdentifiersAreKnown() {
        RunView run = create(plan(read("a")));
        assertEquals(404, assertThrows(DomainException.class, () -> runtime.get(UUID.randomUUID(), workspace, run.id())).status());
        assertEquals(404, assertThrows(DomainException.class, () -> runtime.command(UUID.randomUUID(), workspace, run.id(), 1, Command.CANCEL)).status());
        assertEquals("QUEUED", runtime.get(owner, workspace, run.id()).status());
    }

    @Test void crossWorkspaceResourceScopeIsRejectedBeforeCreatingAnyRun() {
        UUID foreignArtifact = UUID.randomUUID();
        TaskPlan.Step task = new TaskPlan.Step("write", "document-author", List.of(), Set.of(), Set.of("artifact:" + foreignArtifact), Set.of("composeDocument"), true, List.of("scoped write"), 0);
        rejects("SCOPE_NOT_ADMITTED", () -> create(plan(task)));
    }

    @Test void concurrentSchedulersCannotClaimTheSamePersistedStep() throws Exception {
        RunView run = create(plan(read("a"), read("b"), read("c")));
        List<List<Lease>> batches = parallel(List.of(
                () -> runtime.claim(owner, workspace, run.id(), 1, 30_000),
                () -> runtimeInstance().claim(owner, workspace, run.id(), 1, 30_000),
                () -> runtimeInstance().claim(owner, workspace, run.id(), 1, 30_000)));
        List<Lease> claims = batches.stream().flatMap(List::stream).toList();
        assertEquals(3, claims.size());
        assertEquals(3, claims.stream().map(Lease::stepId).distinct().count());
        assertTrue(claims.stream().allMatch(lease -> lease.attempt() == 1));
    }

    @Test void ancestorWorkspaceWriteBlocksArtifactReadAcrossDifferentRuns() {
        UUID artifact = UUID.randomUUID();
        jdbc.update("INSERT INTO learning_artifact(id,workspace_id,kind,title) VALUES(?,?,?,?)", artifact.toString(), workspace.toString(), "DOCUMENT", "Scope fixture");
        TaskPlan.Step wholeWorkspace = new TaskPlan.Step("write", "document-author", List.of(), Set.of(), Set.of("workspace:" + workspace), Set.of("composeDocument"), true, List.of("write"), 0);
        TaskPlan.Step artifactRead = new TaskPlan.Step("read", "experience-reviewer", List.of(), Set.of("artifact:" + artifact + "/page:1"), Set.of(), Set.of("previewArtifact"), true, List.of("read"), 0);
        RunView first = create(plan(wholeWorkspace));
        RunView second = create(plan(artifactRead));
        claim(first);
        assertTrue(runtime.claim(owner, workspace, second.id(), 1, 30_000).isEmpty());
        runtime.command(owner, workspace, first.id(), 1, Command.CANCEL);
        assertEquals(1, runtime.claim(owner, workspace, second.id(), 1, 30_000).size());
    }

    @Test void expiredLeaseCanBeReclaimedButOldWorkerCannotCommit() {
        RunView run = create(plan(read("a")));
        Lease old = runtime.claim(owner, workspace, run.id(), 1, 1_000).get(0);
        clock.advanceMillis(1_001);
        Lease replacement = runtimeInstance().claim(owner, workspace, run.id(), 1, 30_000).get(0);
        assertEquals(2, replacement.attempt());
        assertNotEquals(old.token(), replacement.token());
        rejects("STALE_LEASE", () -> runtime.complete(owner, workspace, old, List.of(knowledge)));
        assertEquals("COMPLETED", runtime.complete(owner, workspace, replacement, List.of(knowledge)).status());
    }

    @Test void pauseRevokesEpochAndResumeDoesNotExtendDeadline() {
        RunView run = create(plan(read("a")));
        Lease lease = claim(run);
        RunView paused = runtime.command(owner, workspace, run.id(), 1, Command.PAUSE);
        assertEquals(2, paused.runEpoch());
        rejects("STALE_RUN_EPOCH", () -> runtime.complete(owner, workspace, lease, List.of(knowledge)));
        RunView resumed = runtime.command(owner, workspace, run.id(), 2, Command.RESUME);
        assertEquals(3, resumed.runEpoch());
        assertEquals(run.deadline(), resumed.deadline());
        Lease fresh = claim(resumed);
        assertEquals(3, fresh.runEpoch());
        assertEquals("COMPLETED", runtime.complete(owner, workspace, fresh, List.of(knowledge)).status());
    }

    @Test void concurrentReservationsCannotOversellTheRunBudget() throws Exception {
        RunView run = create(plan(read("a"), read("b")));
        List<Lease> leases = runtime.claim(owner, workspace, run.id(), 1, 30_000);
        List<Boolean> outcomes = parallel(List.of(
                () -> reserveIfPossible(leases.get(0), "parallel-one"),
                () -> reserveIfPossible(leases.get(1), "parallel-two")));
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
        RunView persisted = runtime.get(owner, workspace, run.id());
        assertEquals(8_000, persisted.reservedMicros());
        assertEquals(800, persisted.reservedTokens());
    }

    private boolean reserveIfPossible(Lease lease, String key) {
        try {
            runtimeInstance().reserveTool(owner, workspace, lease, "searchEvidence", key, Map.of("query", "PECS"), 8_000, 800);
            return true;
        } catch (DomainException exception) {
            assertEquals("BUDGET_EXHAUSTED", exception.code());
            return false;
        }
    }

    @Test void stableExecutionKeysAndSettlementPreventDuplicateCharges() {
        RunView run = create(plan(read("a")));
        Lease lease = claim(run);
        var reservation = runtime.reserveTool(owner, workspace, lease, "searchEvidence", "stable-call", Map.of("q", "PECS"), 5_000, 500);
        var same = runtime.reserveTool(owner, workspace, lease, "searchEvidence", "stable-call", Map.of("q", "PECS"), 5_000, 500);
        assertEquals(reservation.id(), same.id());
        rejects("TOOL_IDEMPOTENCY_CONFLICT", () -> runtime.reserveTool(owner, workspace, lease, "searchEvidence", "stable-call", Map.of("q", "other"), 5_000, 500));
        runtime.markToolRunning(owner, workspace, lease, reservation.id(), null);
        rejects("EXECUTION_ALREADY_DISPATCHED", () -> runtime.markToolRunning(owner, workspace, lease, reservation.id(), null));
        runtime.settleTool(owner, workspace, run.id(), reservation.id(), ToolOutcome.SUCCEEDED, 2_000, 120, List.of(knowledge));
        runtimeInstance().settleTool(owner, workspace, run.id(), reservation.id(), ToolOutcome.SUCCEEDED, 2_000, 120, List.of(knowledge));
        rejects("SETTLEMENT_CONFLICT", () -> runtime.settleTool(owner, workspace, run.id(), reservation.id(), ToolOutcome.SUCCEEDED, 1_000, 120, List.of(knowledge)));
        assertEquals(2_000, runtime.get(owner, workspace, run.id()).spentMicros());
        assertEquals(0, runtime.get(owner, workspace, run.id()).reservedMicros());
    }

    @Test void unknownExternalEffectRetainsReservationAcrossCrashAndCannotBeBlindlyRetried() {
        RunView run = create(plan(read("a")));
        Lease lease = runtime.claim(owner, workspace, run.id(), 1, 1_000).get(0);
        var tool = runtime.reserveTool(owner, workspace, lease, "searchEvidence", "unknown-call", Map.of("q", "PECS"), 5_000, 500);
        runtime.markToolRunning(owner, workspace, lease, tool.id(), "provider-job-1");
        clock.advanceMillis(1_001);
        assertTrue(runtimeInstance().claim(owner, workspace, run.id(), 1, 30_000).isEmpty());
        RunView held = runtime.get(owner, workspace, run.id());
        assertEquals("WAITING_USER", held.status());
        assertEquals(5_000, held.reservedMicros());
        rejects("RECONCILIATION_REQUIRED", () -> runtime.command(owner, workspace, run.id(), 1, Command.RESUME));
        runtime.settleTool(owner, workspace, run.id(), tool.id(), ToolOutcome.SUCCEEDED, 2_000, 100, List.of(knowledge));
        RunView resumed = runtime.command(owner, workspace, run.id(), 1, Command.RESUME);
        Lease next = claim(resumed);
        var previous = runtime.reserveTool(owner, workspace, next, "searchEvidence", "unknown-call", Map.of("q", "PECS"), 5_000, 500);
        assertEquals(tool.id(), previous.id());
        assertEquals("SUCCEEDED", previous.status());
        assertEquals("COMPLETED", runtime.complete(owner, workspace, next, List.of(knowledge)).status());
    }

    @Test void cancellationReleasesOnlyUndispatchedReservationsAndKeepsLateBillingAccountable() {
        RunView run = create(plan(read("a")));
        Lease lease = claim(run);
        var running = runtime.reserveTool(owner, workspace, lease, "searchEvidence", "dispatched", Map.of("q", "first"), 4_000, 400);
        var notSent = runtime.reserveTool(owner, workspace, lease, "searchEvidence", "undispatched", Map.of("q", "second"), 2_000, 200);
        runtime.markToolRunning(owner, workspace, lease, running.id(), null);
        RunView canceled = runtime.command(owner, workspace, run.id(), 1, Command.CANCEL);
        assertEquals("CANCELLED", canceled.status());
        assertEquals(4_000, canceled.reservedMicros());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM tool_execution WHERE id=?", String.class, notSent.id().toString()));
        runtime.settleTool(owner, workspace, run.id(), running.id(), ToolOutcome.SUCCEEDED, 1_500, 150, List.of(knowledge));
        RunView reconciled = runtime.get(owner, workspace, run.id());
        assertEquals("CANCELLED", reconciled.status());
        assertEquals(0, reconciled.reservedMicros());
        assertEquals(1_500, reconciled.spentMicros());
        rejects("STALE_RUN_EPOCH", () -> runtime.complete(owner, workspace, lease, List.of(knowledge)));
    }

    @Test void observedProviderOverrunIsNotDiscardedOrSilentlyAdjustedDown() {
        RunView run = create(plan(read("a")));
        Lease lease = claim(run);
        var tool = runtime.reserveTool(owner, workspace, lease, "searchEvidence", "overrun-call", Map.of("q", "PECS"), 1_000, 100);
        runtime.markToolRunning(owner, workspace, lease, tool.id(), null);
        var held = runtime.settleTool(owner, workspace, run.id(), tool.id(), ToolOutcome.SUCCEEDED, 1_500, 150, List.of(knowledge));
        assertEquals("UNKNOWN", held.status());
        assertEquals(1_500, held.actualMicros());
        assertEquals("WAITING_USER", runtime.get(owner, workspace, run.id()).status());
        assertEquals(1_000, runtime.get(owner, workspace, run.id()).reservedMicros());
        rejects("BILLING_RECONCILIATION_REQUIRED", () -> runtime.settleTool(owner, workspace, run.id(), tool.id(), ToolOutcome.SUCCEEDED, 1_000, 100, List.of(knowledge)));
    }

    @Test void failedRequiredBranchSkipsDependentsAndPreservesCompletedPartialWork() {
        RunView run = create(plan(read("good"), read("bad"), read("blocked", "bad")));
        List<Lease> leases = runtime.claim(owner, workspace, run.id(), 1, 30_000);
        runtime.complete(owner, workspace, leases.get(0), List.of(knowledge));
        RunView result = runtime.fail(owner, workspace, leases.get(1), "EVIDENCE_UNAVAILABLE", false);
        assertEquals("PARTIAL", result.status());
        assertEquals("SKIPPED", result.steps().get(2).status());
        assertTrue(runtime.claim(owner, workspace, run.id(), 1, 30_000).isEmpty());
    }

    @Test void retriesAreBoundedAndFailedNodesDoNotStarveRemainingIndependentSteps() {
        RunView run = create(plan(read("a"), read("b"), read("c"), read("d")));
        List<Lease> first = runtime.claim(owner, workspace, run.id(), 1, 30_000);
        runtime.fail(owner, workspace, first.get(0), "PERMANENT", false);
        runtime.complete(owner, workspace, first.get(1), List.of(knowledge));
        runtime.complete(owner, workspace, first.get(2), List.of(knowledge));
        Lease fourth = runtime.claim(owner, workspace, run.id(), 1, 30_000).get(0);
        assertEquals("d", fourth.stepKey());
        runtime.fail(owner, workspace, fourth, "TRANSIENT", true);
        Lease second = runtime.claim(owner, workspace, run.id(), 1, 30_000).get(0);
        runtime.fail(owner, workspace, second, "TRANSIENT", true);
        Lease third = runtime.claim(owner, workspace, run.id(), 1, 30_000).get(0);
        assertEquals(3, third.attempt());
        assertEquals("PARTIAL", runtime.fail(owner, workspace, third, "TRANSIENT", true).status());
        assertTrue(runtime.claim(owner, workspace, run.id(), 1, 30_000).isEmpty());
    }

    @Test void withdrawnContextStopsBothNewClaimsAndPreviouslyIssuedCompletions() {
        var memory = workspaces.stateMemory(owner, workspace, "Use short Java examples", UUID.randomUUID().toString());
        snapshot = workspaces.createContext(owner, workspace).id();
        RunView run = create(plan(read("a")));
        Lease lease = claim(run);
        workspaces.revokeMemory(owner, workspace, memory.id(), memory.revision());
        assertThrows(DomainException.class, () -> runtime.complete(owner, workspace, lease, List.of(knowledge)));
        assertTrue(runtime.claim(owner, workspace, run.id(), 1, 30_000).isEmpty());
        RunView stopped = runtime.get(owner, workspace, run.id());
        assertEquals("WAITING_USER", stopped.status());
        assertEquals(2, stopped.runEpoch());
        assertEquals(2, stopped.plan().runEpoch());
        assertTrue(runtime.claim(owner, workspace, run.id(), 2, 30_000).isEmpty());
        assertEquals(1, runtime.events(owner, workspace, run.id(), 0, 100).stream()
                .filter(event -> event.type().equals("AGENT.RUN.CONTEXT.INVALIDATED.V1")).count());
        rejects("STALE_RUN_EPOCH", () -> runtime.complete(owner, workspace, lease, List.of(knowledge)));
        runtime.command(owner, workspace, run.id(), 2, Command.CANCEL);
        assertEquals("CANCELLED", runtime.get(owner, workspace, run.id()).status());
    }

    @Test void invalidatedContextFencesLeasesButRetainsUnknownProviderReservations() {
        var memory = workspaces.stateMemory(owner, workspace, "Use reviewed source material", UUID.randomUUID().toString());
        snapshot = workspaces.createContext(owner, workspace).id();
        RunView run = create(plan(read("a")));Lease lease = claim(run);
        var dispatched = runtime.reserveTool(owner, workspace, lease, "searchEvidence", "dispatched-call", Map.of("q", "PECS"), 3_000, 100);
        runtime.markToolRunning(owner, workspace, lease, dispatched.id(), null);
        var admitted = runtime.reserveTool(owner, workspace, lease, "searchEvidence", "undispatched-call", Map.of("q", "wildcard"), 2_000, 50);
        workspaces.revokeMemory(owner, workspace, memory.id(), memory.revision());
        assertTrue(runtime.claim(owner, workspace, run.id(), 1, 30_000).isEmpty());
        RunView stopped = runtime.get(owner, workspace, run.id());
        assertEquals("WAITING_USER", stopped.status());assertEquals("WAITING_TOOL", stopped.steps().get(0).status());
        assertEquals(3_000, stopped.reservedMicros());assertEquals(100, stopped.reservedTokens());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM tool_execution WHERE id=?", String.class, dispatched.id().toString()));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM tool_execution WHERE id=?", String.class, admitted.id().toString()));
        assertNull(jdbc.queryForObject("SELECT lease_token FROM agent_step WHERE id=?", String.class, lease.stepId().toString()));
        rejects("STALE_RUN_EPOCH", () -> runtime.heartbeat(owner, workspace, lease, 30_000));
        runtime.settleTool(owner, workspace, run.id(), dispatched.id(), ToolOutcome.FAILED, 1_000, 40, List.of());
        RunView billed = runtime.get(owner, workspace, run.id());
        assertEquals(1_000, billed.spentMicros());assertEquals(40, billed.spentTokens());assertEquals(0, billed.reservedMicros());
    }

    @Test void overdueRunTerminatesEvenWhenItsSnapshotHasAlsoBeenRevoked() {
        var memory = workspaces.stateMemory(owner, workspace, "Deadline fixture", UUID.randomUUID().toString());
        snapshot = workspaces.createContext(owner, workspace).id();
        RunView run = create(plan(read("a")));
        workspaces.revokeMemory(owner, workspace, memory.id(), memory.revision());
        clock.advanceMillis(120_001);
        assertTrue(runtime.claim(owner, workspace, run.id(), 1, 30_000).isEmpty());
        RunView expired = runtime.get(owner, workspace, run.id());
        assertEquals("FAILED", expired.status());assertEquals(2, expired.runEpoch());
        assertTrue(runtime.events(owner, workspace, run.id(), 0, 100).stream()
                .anyMatch(event -> event.type().equals("AGENT.RUN.DEADLINE.EXCEEDED.V1")));
        assertFalse(runtime.events(owner, workspace, run.id(), 0, 100).stream()
                .anyMatch(event -> event.type().equals("AGENT.RUN.CONTEXT.INVALIDATED.V1")));
    }

    @Test void snapshotServiceFailureIsNotSilentlyReportedAsAnEmptySuccessfulClaim() {
        RunView run = create(plan(read("a")));
        WorkspaceAccess unavailable = new WorkspaceAccess() {
            @Override public OwnedWorkspace lockOwned(UUID ownerId, UUID workspaceId) { return access.lockOwned(ownerId, workspaceId); }
            @Override public OwnedWorkspace requireOwned(UUID ownerId, UUID workspaceId) { return access.requireOwned(ownerId, workspaceId); }
            @Override public void validateSnapshot(UUID ownerId, UUID workspaceId, UUID snapshotId) {
                throw new DomainException("CONTEXT_SERVICE_UNAVAILABLE", 503, "Deliberate unavailable reference store fixture");
            }
        };
        var unavailableRuntime = new DurableAgentRuntime(jdbc, tx, json, unavailable, journal, clock, validator);
        rejects("CONTEXT_SERVICE_UNAVAILABLE", () -> unavailableRuntime.claim(owner, workspace, run.id(), 1, 30_000));
        RunView unchanged = runtime.get(owner, workspace, run.id());
        assertEquals("QUEUED", unchanged.status());assertEquals(1, unchanged.runEpoch());
        assertEquals(1, runtime.events(owner, workspace, run.id(), 0, 100).size());
    }

    @Test void forgedAndRetiredEvidenceCannotCompleteRequiredWork() {
        RunView run = create(plan(read("a")));
        Lease lease = claim(run);
        OutputReference forged = new OutputReference("KNOWLEDGE_CHUNK", UUID.randomUUID(), 1, knowledge.contentHash());
        rejects("OUTPUT_REFERENCE_INVALID", () -> runtime.complete(owner, workspace, lease, List.of(forged)));
        jdbc.update("UPDATE knowledge_chunk SET status='RETIRED' WHERE id=?", knowledge.id().toString());
        rejects("OUTPUT_REFERENCE_INVALID", () -> runtime.complete(owner, workspace, lease, List.of(knowledge)));
        assertEquals("RUNNING", runtime.get(owner, workspace, run.id()).status());
    }

    @Test void deadlineProducesFailureAndFencesLateCallbacks() {
        RunView run = create(plan(read("a")));
        Lease lease = claim(run);
        clock.advanceMillis(120_001);
        assertTrue(runtime.claim(owner, workspace, run.id(), 1, 30_000).isEmpty());
        assertEquals("FAILED", runtime.get(owner, workspace, run.id()).status());
        rejects("STALE_RUN_EPOCH", () -> runtime.complete(owner, workspace, lease, List.of(knowledge)));
    }

    @Test void replanRetainsHistoryReusesOnlyVerifiedUnchangedWorkAndDoesNotResetLimits() {
        TaskPlan original = plan(read("evidence"), read("old-output", "evidence"));
        RunView run = create(original);
        Lease completed = claim(run);
        runtime.complete(owner, workspace, completed, List.of(knowledge));
        RunView paused = runtime.command(owner, workspace, run.id(), 1, Command.PAUSE);
        TaskPlan replacement = new TaskPlan(run.id(), 2, paused.runEpoch() + 1, GOAL, original.budget(),
                List.of(read("evidence"), read("new-output", "evidence")));
        String key = UUID.randomUUID().toString();
        RunView revised = runtime.replan(owner, workspace, run.id(), 1, 2, snapshot, replacement, key);
        assertEquals(2, revised.planRevision());
        assertEquals(3, revised.runEpoch());
        assertEquals("SUCCEEDED", revised.steps().get(0).status());
        assertEquals("PENDING", revised.steps().get(1).status());
        assertEquals(run.deadline(), revised.deadline());
        assertEquals(run.budgetMicros(), revised.budgetMicros());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM agent_step WHERE run_id=?", Integer.class, run.id().toString()));
        rejects("STALE_RUN_EPOCH", () -> runtime.complete(owner, workspace, completed, List.of(knowledge)));
        Lease fresh = claim(revised);
        assertEquals("new-output", fresh.stepKey());
        assertEquals("COMPLETED", runtime.complete(owner, workspace, fresh, List.of(knowledge)).status());
        assertEquals("COMPLETED", runtime.replan(owner, workspace, run.id(), 1, 2, snapshot, replacement, key).status());
    }

    @Test void differentSnapshotPreventsReusingOtherwiseIdenticalCompletedSteps() {
        TaskPlan original = plan(read("a"), read("b", "a"));
        RunView run = create(original);
        runtime.complete(owner, workspace, claim(run), List.of(knowledge));
        runtime.command(owner, workspace, run.id(), 1, Command.PAUSE);
        workspaces.stateMemory(owner, workspace, "Include a contrasting counterexample", UUID.randomUUID().toString());
        UUID changedSnapshot = workspaces.createContext(owner, workspace).id();
        TaskPlan replacement = new TaskPlan(run.id(), 2, 3, GOAL, original.budget(), List.of(read("a"), read("b", "a")));
        RunView revised = runtime.replan(owner, workspace, run.id(), 1, 2, changedSnapshot, replacement, UUID.randomUUID().toString());
        assertTrue(revised.steps().stream().allMatch(step -> step.status().equals("PENDING")));
    }

    @Test void aChangedDependencyInvalidatesItsPreviouslyCompletedSuccessors() {
        TaskPlan original = plan(read("a"), read("b", "a"), read("c", "b"));
        RunView run = create(original);
        runtime.complete(owner, workspace, claim(run), List.of(knowledge));
        runtime.complete(owner, workspace, claim(run), List.of(knowledge));
        runtime.command(owner, workspace, run.id(), 1, Command.PAUSE);
        var a = read("a");
        TaskPlan.Step stricter = new TaskPlan.Step(a.id(), a.role(), a.dependencies(), a.readScopes(), a.writeScopes(), a.tools(), true,
                List.of("A different acceptance criterion must be checked"), 0);
        TaskPlan replacement = new TaskPlan(run.id(), 2, 3, GOAL, original.budget(), List.of(stricter, read("b", "a"), read("c", "b")));
        RunView revised = runtime.replan(owner, workspace, run.id(), 1, 2, snapshot, replacement, UUID.randomUUID().toString());
        assertTrue(revised.steps().stream().allMatch(step -> step.status().equals("PENDING")));
    }

    @Test void replanCannotIncreaseBudgetOrEvadeUnknownExternalEffects() {
        TaskPlan original = plan(read("a"));
        RunView run = create(original);
        Lease lease = claim(run);
        var tool = runtime.reserveTool(owner, workspace, lease, "searchEvidence", "replan-call", Map.of("q", "PECS"), 2_000, 100);
        runtime.markToolRunning(owner, workspace, lease, tool.id(), null);
        runtime.command(owner, workspace, run.id(), 1, Command.PAUSE);
        TaskPlan replacement = new TaskPlan(run.id(), 2, 3, GOAL, original.budget(), List.of(read("b")));
        rejects("RECONCILIATION_REQUIRED", () -> runtime.replan(owner, workspace, run.id(), 1, 2, snapshot, replacement, UUID.randomUUID().toString()));
        runtime.settleTool(owner, workspace, run.id(), tool.id(), ToolOutcome.FAILED, 1_000, 50, List.of());
        TaskPlan inflated = new TaskPlan(run.id(), 2, 3, GOAL, new TaskPlan.Budget(1_000, 120_000, 20_000, 3), List.of(read("b")));
        rejects("REPLAN_BUDGET_CONFLICT", () -> runtime.replan(owner, workspace, run.id(), 1, 2, snapshot, inflated, UUID.randomUUID().toString()));
        RunView revised = runtime.replan(owner, workspace, run.id(), 1, 2, snapshot, replacement, UUID.randomUUID().toString());
        assertEquals(1_000, revised.spentMicros());
        assertEquals(50, revised.spentTokens());
        assertEquals(0, revised.reservedMicros());
    }

    private static <T> List<T> parallel(List<Callable<T>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<T>> futures = executor.invokeAll(calls);
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get());
            return results;
        } finally { executor.shutdownNow(); }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;
        private MutableClock(Instant instant) { current = new AtomicReference<>(instant); }
        void advanceMillis(long milliseconds) { current.updateAndGet(instant -> instant.plusMillis(milliseconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("Test clock is UTC");
            return this;
        }
        @Override public Instant instant() { return current.get(); }
    }
}
