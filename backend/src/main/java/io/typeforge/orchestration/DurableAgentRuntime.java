package io.typeforge.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable, deterministic execution control. This class never calls a model, renderer, shell or compiler.
 * Every mutation takes owner -> workspace -> run locks and commits before a worker performs external work.
 * Worker-facing methods are not public HTTP actions. Tool settlement is accounting, not publication.
 */
@Service
public final class DurableAgentRuntime {
    private static final Set<String> TERMINAL_RUNS = Set.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED");
    private static final Set<String> TERMINAL_STEPS = Set.of("SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED");
    private static final Set<String> ACTIVE_STEPS = Set.of("RUNNING", "WAITING_TOOL", "REVIEWING");
    private static final Set<String> TERMINAL_TOOLS = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final int MAX_ATTEMPTS = 3;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonCodec json;
    private final WorkspaceAccess workspaces;
    private final EventJournal journal;
    private final Clock clock;
    private final PlanValidator validator;

    public DurableAgentRuntime(JdbcTemplate jdbc, TransactionTemplate transactions, JsonCodec json,
            WorkspaceAccess workspaces, EventJournal journal, Clock clock, PlanValidator validator) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.workspaces = workspaces;
        this.journal = journal;
        this.clock = clock;
        this.validator = validator;
    }

    public enum Command { PAUSE, RESUME, CANCEL }
    public enum ToolOutcome { SUCCEEDED, FAILED, CANCELLED }
    public record OutputReference(String kind, UUID id, int revision, String contentHash) {}
    public record Lease(UUID runId, UUID stepId, String stepKey, int planRevision, long runEpoch,
                        UUID token, Instant expiresAt, int attempt) {}
    public record StepView(UUID id, String key, String role, String status, int attempts,
                           boolean required, List<OutputReference> outputRefs) {}
    public record RunView(UUID id, UUID workspaceId, UUID goalId, UUID snapshotId, String status,
                          int planRevision, long runEpoch, TaskPlan plan, long budgetMicros,
                          long tokenBudget, long spentMicros, long reservedMicros, long spentTokens,
                          long reservedTokens, Instant deadline, List<StepView> steps, List<String> limitations) {}
    public record RunEvent(long sequence, String type, JsonNode payload, Instant createdAt) {}
    public record ToolView(UUID id, String executionKey, String tool, String status,
                           long reservedMicros, long reservedTokens, Long actualMicros, Long actualTokens,
                           String providerJobId, List<OutputReference> resultRefs) {}

    /** The caller proposes a plan; the database and registered capabilities decide admission. */
    public RunView create(UUID ownerId, UUID workspaceId, UUID goalId, UUID snapshotId,
                          TaskPlan proposed, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        validatePlan(proposed);
        if (proposed.revision() != 1 || proposed.runEpoch() != 1) {
            throw problem("INITIAL_PLAN_VERSION", 400, "New runs start at plan revision and epoch one");
        }
        String requestHash = json.hash(List.of(workspaceId, goalId, snapshotId, planIdentity(proposed)));
        String operation = "agent-run:create:" + workspaceId;
        return transaction(() -> {
            var workspace = workspaces.lockOwned(ownerId, workspaceId);
            var prior = jdbc.query("SELECT request_hash,resource_id FROM idempotency_record WHERE user_id=? AND operation=? AND idempotency_key=?",
                    (rs, row) -> Map.entry(rs.getString(1), UUID.fromString(rs.getString(2))), ownerId.toString(), operation, idempotencyKey);
            if (!prior.isEmpty()) {
                if (!prior.get(0).getKey().equals(requestHash)) {
                    throw problem("IDEMPOTENCY_CONFLICT", 409, "This key already identifies a different request");
                }
                return view(load(workspaceId, prior.get(0).getValue(), true));
            }
            var goals = jdbc.query("SELECT goal_text FROM learning_goal WHERE id=? AND workspace_id=? AND status='ACTIVE'",
                    (rs, row) -> rs.getString(1), goalId.toString(), workspaceId.toString());
            if (goals.isEmpty()) throw problem("GOAL_NOT_FOUND", 404, "Active goal not found in this workspace");
            if (!goals.get(0).equals(proposed.goal())) {
                throw problem("GOAL_CHANGED", 409, "Plan goal must match the current saved goal");
            }
            workspaces.validateSnapshot(ownerId, workspaceId, snapshotId);
            if (count("SELECT COUNT(*) FROM agent_run WHERE id=?", proposed.runId().toString()) > 0) {
                throw problem("RUN_ID_CONFLICT", 409, "Run identifier is already in use");
            }
            // Bound unattended work; concurrent runs still share the same workspace scope locks below.
            if (count("SELECT COUNT(*) FROM agent_run WHERE workspace_id=? AND status NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELLED')",
                    workspaceId.toString()) >= 3) {
                throw problem("ACTIVE_RUN_LIMIT", 409, "Cancel or finish existing runs before starting more");
            }
            TaskPlan plan = normalizeScopes(ownerId, workspaceId, workspace.releaseId(), proposed);
            OutputReference contextReference = snapshotReference(workspaceId, snapshotId);
            Instant deadline = now().plusMillis(plan.budget().maxMillis());
            jdbc.update("INSERT INTO agent_run(id,workspace_id,goal_id,snapshot_id,status,plan_revision,run_epoch,plan_json,budget_micros,token_budget,deadline_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    plan.runId().toString(), workspaceId.toString(), goalId.toString(), snapshotId.toString(), "QUEUED",
                    plan.revision(), plan.runEpoch(), json.write(plan), plan.budget().maxCostMicros(), plan.budget().maxTokens(), timestamp(deadline));
            Map<String, UUID> ids = new LinkedHashMap<>();
            for (TaskPlan.Step step : plan.steps()) {
                UUID id = UUID.randomUUID();
                ids.put(step.id(), id);
                jdbc.update("INSERT INTO agent_step(id,run_id,plan_revision,step_key,role_key,status,input_refs,output_refs,read_scopes,write_scopes,required_output) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        id.toString(), plan.runId().toString(), plan.revision(), step.id(), step.role(), "PENDING",
                        json.write(List.of(contextReference)), "[]",
                        json.write(step.readScopes().stream().sorted().toList()), json.write(step.writeScopes().stream().sorted().toList()), step.required());
            }
            for (TaskPlan.Step step : plan.steps()) {
                for (String dependency : step.dependencies()) {
                    jdbc.update("INSERT INTO agent_step_dependency(run_id,plan_revision,step_id,depends_on_id) VALUES(?,?,?,?)",
                            plan.runId().toString(), plan.revision(), ids.get(step.id()).toString(), ids.get(dependency).toString());
                }
            }
            jdbc.update("INSERT INTO idempotency_record(user_id,operation,idempotency_key,request_hash,resource_id,expires_at) VALUES(?,?,?,?,?,?)",
                    ownerId.toString(), operation, idempotencyKey, requestHash, plan.runId().toString(), timestamp(now().plus(1, ChronoUnit.DAYS)));
            RunRow run = load(workspaceId, plan.runId(), true);
            emit(run, "agent.run.created.v1", Map.of("planRevision", 1, "runEpoch", 1, "stepCount", ids.size()));
            return view(run);
        });
    }

    public RunView get(UUID ownerId, UUID workspaceId, UUID runId) {
        return transaction(() -> {
            workspaces.lockOwned(ownerId, workspaceId);
            return view(load(workspaceId, runId, true));
        });
    }

    public List<RunEvent> events(UUID ownerId, UUID workspaceId, UUID runId, long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 100) throw problem("INVALID_CURSOR", 400, "Event cursor or limit is invalid");
        workspaces.requireOwned(ownerId, workspaceId);
        load(workspaceId, runId, false);
        return jdbc.query("SELECT sequence_no,event_type,payload,created_at FROM agent_run_event WHERE run_id=? AND sequence_no>? ORDER BY sequence_no LIMIT ?",
                (rs, row) -> new RunEvent(rs.getLong(1), rs.getString(2), json.tree(rs.getString(3)), rs.getTimestamp(4).toInstant()),
                runId.toString(), afterSequence, limit);
    }

    /** One transaction atomically claims a non-conflicting, dependency-ready batch. */
    public List<Lease> claim(UUID ownerId, UUID workspaceId, UUID runId, long expectedEpoch, long leaseMillis) {
        validateLeaseDuration(leaseMillis);
        return transaction(() -> {
            workspaces.lockOwned(ownerId, workspaceId);
            RunRow run = load(workspaceId, runId, true);
            requireEpoch(run, expectedEpoch);
            if (!Set.of("QUEUED", "RUNNING").contains(run.status())) return List.of();
            if (!now().isBefore(run.deadline())) {
                expireRun(run);
                return List.of();
            }
            try {
                workspaces.validateSnapshot(ownerId, workspaceId, run.snapshotId());
            } catch (DomainException invalidContext) {
                // Scope and epoch were already checked. Only authoritative reference failures
                // become a durable stop; service outages and unrelated errors must still surface.
                if (!Set.of("NOT_FOUND", "VERSION_CONFLICT", "INVALID_INPUT").contains(invalidContext.code())
                        || !Set.of(400, 404, 409).contains(invalidContext.status())) throw invalidContext;
                suspendInvalidContext(run, invalidContext.code());
                return List.of();
            }
            recoverExpiredLeases(run);
            run = load(workspaceId, runId, true);
            if (!Set.of("QUEUED", "RUNNING").contains(run.status())) return List.of();
            List<StepRow> steps = steps(run);
            Set<String> succeeded = new HashSet<>();
            List<TaskPlan.Step> held = new ArrayList<>();
            Map<String, StepRow> byKey = new HashMap<>();
            RunRow schedulingRun = run;
            steps.forEach(step -> {
                byKey.put(step.key(), step);
                if (step.status().equals("SUCCEEDED")) succeeded.add(step.key());
                if (ACTIVE_STEPS.contains(step.status())) held.add(plannedStep(schedulingRun, step.key()));
            });
            List<TaskPlan.Step> otherLeases = otherWorkspaceLeases(run);
            List<Lease> claims = new ArrayList<>();
            for (String key : validator.validate(run.plan())) {
                if (held.size() >= run.plan().budget().maxParallel()) break;
                StepRow step = byKey.get(key);
                if (!Set.of("PENDING", "READY").contains(step.status()) || step.attempts() >= MAX_ATTEMPTS) continue;
                TaskPlan.Step planned = plannedStep(run, key);
                if (!succeeded.containsAll(planned.dependencies()) || held.stream().anyMatch(other -> PlanValidator.conflicts(planned, other))
                        || otherLeases.stream().anyMatch(other -> PlanValidator.conflicts(planned, other))) continue;
                UUID token = UUID.randomUUID();
                Instant until = minimum(now().plusMillis(leaseMillis), run.deadline());
                jdbc.update("UPDATE agent_step SET status='RUNNING',lease_token=?,lease_until=?,attempt_count=attempt_count+1 WHERE id=? AND plan_revision=?",
                        token.toString(), timestamp(until), step.id().toString(), run.revision());
                Lease lease = new Lease(run.id(), step.id(), key, run.revision(), run.epoch(), token, until, step.attempts() + 1);
                claims.add(lease);
                held.add(planned);
                emit(run, "agent.step.claimed.v1", Map.of("stepId", step.id(), "stepKey", key, "attempt", lease.attempt(), "runEpoch", run.epoch()));
            }
            if (!claims.isEmpty()) jdbc.update("UPDATE agent_run SET status='RUNNING' WHERE id=?", run.id().toString());
            return List.copyOf(claims);
        });
    }

    public Lease heartbeat(UUID ownerId, UUID workspaceId, Lease lease, long leaseMillis) {
        validateLeaseDuration(leaseMillis);
        return transaction(() -> {
            workspaces.lockOwned(ownerId, workspaceId);
            RunRow run = load(workspaceId, lease.runId(), true);
            StepRow step = requireLiveLease(run, lease);
            Instant until = minimum(now().plusMillis(leaseMillis), run.deadline());
            jdbc.update("UPDATE agent_step SET lease_until=? WHERE id=?", timestamp(until), step.id().toString());
            return new Lease(run.id(), step.id(), step.key(), run.revision(), run.epoch(), lease.token(), until, step.attempts());
        });
    }

    /** Completion accepts existing, scoped, versioned evidence; arbitrary text cannot become a fact. */
    public RunView complete(UUID ownerId, UUID workspaceId, Lease lease, List<OutputReference> outputs) {
        List<OutputReference> immutable = validateReferenceShape(outputs, true);
        return transaction(() -> {
            workspaces.lockOwned(ownerId, workspaceId);
            RunRow run = load(workspaceId, lease.runId(), true);
            requireEpoch(run, lease.runEpoch());
            StepRow current = step(run, lease.stepId());
            if (current.status().equals("SUCCEEDED") && Objects.equals(current.leaseToken(), lease.token())) {
                if (!json.hash(current.outputs()).equals(json.hash(immutable))) {
                    throw problem("COMPLETION_CONFLICT", 409, "A completed lease has different output references");
                }
                return view(run);
            }
            StepRow step = requireLiveLease(run, lease);
            workspaces.validateSnapshot(ownerId, workspaceId, run.snapshotId());
            if (unsettledTools(step.id()) > 0) throw problem("UNSETTLED_TOOL", 409, "Reconcile outstanding tool calls before completing the step");
            validateOutputReferences(ownerId, workspaceId, immutable);
            jdbc.update("UPDATE agent_step SET status='SUCCEEDED',output_refs=? WHERE id=?", json.write(immutable), step.id().toString());
            emit(run, "agent.step.completed.v1", Map.of("stepId", step.id(), "outputRefs", immutable, "validation", "reference-integrity"));
            finishIfSettled(run);
            return view(load(workspaceId, run.id(), true));
        });
    }

    public RunView fail(UUID ownerId, UUID workspaceId, Lease lease, String errorCode, boolean retryable) {
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) throw problem("INVALID_ERROR_CODE", 400, "Use a bounded public error code");
        return transaction(() -> {
            workspaces.lockOwned(ownerId, workspaceId);
            RunRow run = load(workspaceId, lease.runId(), true);
            StepRow step = requireLiveLease(run, lease);
            if (unsettledTools(step.id()) > 0) throw problem("UNSETTLED_TOOL", 409, "Unknown external effects must be reconciled before retrying");
            boolean retry = retryable && step.attempts() < MAX_ATTEMPTS;
            jdbc.update("UPDATE agent_step SET status=?,lease_token=NULL,lease_until=NULL WHERE id=?", retry ? "PENDING" : "FAILED", step.id().toString());
            emit(run, retry ? "agent.step.retry.queued.v1" : "agent.step.failed.v1",
                    Map.of("stepId", step.id(), "errorCode", errorCode, "attempt", step.attempts(), "retryable", retry));
            finishIfSettled(run);
            return view(load(workspaceId, run.id(), true));
        });
    }

    public RunView command(UUID ownerId, UUID workspaceId, UUID runId, long expectedEpoch, Command command) {
        Objects.requireNonNull(command, "command");
        return transaction(() -> {
            workspaces.lockOwned(ownerId, workspaceId);
            RunRow run = load(workspaceId, runId, true);
            requireEpoch(run, expectedEpoch);
            if (command == Command.CANCEL && run.status().equals("CANCELLED")) return view(run);
            if (command == Command.PAUSE && run.status().equals("PAUSED")) return view(run);
            if (TERMINAL_RUNS.contains(run.status())) throw problem("RUN_TERMINAL", 409, "A terminal run cannot be resumed or changed");
            if (command == Command.RESUME) {
                if (!Set.of("PAUSED", "WAITING_USER").contains(run.status())) throw problem("RUN_NOT_PAUSED", 409, "Only paused runs can resume");
                if (!now().isBefore(run.deadline())) throw problem("RUN_DEADLINE", 409, "This run reached its deadline; start a new bounded run");
                if (unsettledRunTools(run.id()) > 0) throw problem("RECONCILIATION_REQUIRED", 409, "Reconcile unknown external calls before resuming");
                workspaces.validateSnapshot(ownerId, workspaceId, run.snapshotId());
            }
            if (command != Command.RESUME) fenceToolCalls(run);
            String next = switch (command) { case PAUSE -> "PAUSED"; case RESUME -> "QUEUED"; case CANCEL -> "CANCELLED"; };
            long epoch = Math.addExact(run.epoch(), 1);
            TaskPlan revisedEpoch = withEpoch(run.plan(), epoch);
            jdbc.update("UPDATE agent_run SET status=?,run_epoch=?,plan_json=? WHERE id=?", next, epoch, json.write(revisedEpoch), run.id().toString());
            if (command == Command.CANCEL) {
                jdbc.update("UPDATE agent_step SET status='CANCELLED',lease_token=NULL,lease_until=NULL WHERE run_id=? AND status NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED')", run.id().toString());
            } else {
                for (StepRow step : steps(run)) {
                    if (ACTIVE_STEPS.contains(step.status())) {
                        String state = unsettledTools(step.id()) > 0 ? "WAITING_TOOL" : step.attempts() < MAX_ATTEMPTS ? "PENDING" : "FAILED";
                        jdbc.update("UPDATE agent_step SET status=?,lease_token=NULL,lease_until=NULL WHERE id=?", state, step.id().toString());
                    }
                }
            }
            emit(run, "agent.run.commanded.v1", Map.of("command", command, "status", next, "runEpoch", epoch));
            if (command == Command.RESUME) finishIfSettled(load(workspaceId, runId, true));
            return view(load(workspaceId, run.id(), true));
        });
    }

    /**
     * Replace a paused plan without deleting history, resetting money or extending its deadline.
     * A different context always invalidates cached work. Identical context permits reuse only when
     * a step, its complete dependency closure and its current output references are unchanged.
     */
    public RunView replan(UUID ownerId, UUID workspaceId, UUID runId, int expectedRevision, long expectedEpoch,
                          UUID snapshotId, TaskPlan proposed, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        validatePlan(proposed);
        String operation = "agent-run:replan:" + runId;
        String requestHash = json.hash(List.of(workspaceId, runId, expectedRevision, expectedEpoch, snapshotId, planIdentity(proposed)));
        return transaction(() -> {
            var workspace = workspaces.lockOwned(ownerId, workspaceId);
            RunRow run = load(workspaceId, runId, true);
            var previous = jdbc.query("SELECT request_hash FROM idempotency_record WHERE user_id=? AND operation=? AND idempotency_key=?",
                    (rs, row) -> rs.getString(1), ownerId.toString(), operation, idempotencyKey);
            if (!previous.isEmpty()) {
                if (!previous.get(0).equals(requestHash)) throw problem("IDEMPOTENCY_CONFLICT", 409, "This replan key identifies a different request");
                return view(run);
            }
            requireEpoch(run, expectedEpoch);
            if (run.revision() != expectedRevision || !run.id().equals(proposed.runId())
                    || proposed.revision() != expectedRevision + 1 || proposed.runEpoch() != expectedEpoch + 1) {
                throw problem("PLAN_VERSION_CONFLICT", 409, "Replacement plan must advance the current revision and epoch exactly once");
            }
            if (!Set.of("PAUSED", "WAITING_USER").contains(run.status())) throw problem("RUN_NOT_PAUSED", 409, "Pause before replacing a plan");
            if (unsettledRunTools(run.id()) > 0) throw problem("RECONCILIATION_REQUIRED", 409, "Reconcile every external effect before replanning");
            if (!now().isBefore(run.deadline())) throw problem("RUN_DEADLINE", 409, "Replanning does not extend a run deadline");
            if (proposed.budget().maxCostMicros() > run.budgetMicros() || proposed.budget().maxTokens() > run.tokenBudget()
                    || proposed.budget().maxCostMicros() < run.spentMicros() || proposed.budget().maxTokens() < run.spentTokens()) {
                throw problem("REPLAN_BUDGET_CONFLICT", 409, "A replacement plan cannot expand the authorized budget or erase existing spend");
            }
            var goals = jdbc.query("SELECT goal_text FROM learning_goal WHERE id=? AND workspace_id=? AND status='ACTIVE'",
                    (rs, row) -> rs.getString(1), run.goalId().toString(), workspaceId.toString());
            if (goals.isEmpty() || !goals.get(0).equals(proposed.goal())) throw problem("GOAL_CHANGED", 409, "Plan must match the current saved goal");
            workspaces.validateSnapshot(ownerId, workspaceId, snapshotId);
            TaskPlan replacement = normalizeScopes(ownerId, workspaceId, workspace.releaseId(), proposed);
            OutputReference contextReference = snapshotReference(workspaceId, snapshotId);
            Map<String, StepRow> oldStates = new HashMap<>();
            steps(run).forEach(step -> oldStates.put(step.key(), step));
            Map<String, TaskPlan.Step> oldPlan = new HashMap<>();
            run.plan().steps().forEach(step -> oldPlan.put(step.id(), step));
            Map<String, StepRow> reusable = new HashMap<>();
            for (String key : validator.validate(replacement)) {
                TaskPlan.Step candidate = replacement.steps().stream().filter(step -> step.id().equals(key)).findFirst().orElseThrow();
                StepRow old = oldStates.get(key);
                if (!run.snapshotId().equals(snapshotId) || old == null || !old.status().equals("SUCCEEDED")
                        || !reusable.keySet().containsAll(candidate.dependencies())
                        || !json.hash(stepIdentity(candidate)).equals(json.hash(stepIdentity(oldPlan.get(key))))) continue;
                try {
                    validateOutputReferences(ownerId, workspaceId, old.outputs());
                    reusable.put(key, old);
                } catch (DomainException invalidated) {
                    // Keep the old output as history, but require fresh work when it is no longer current.
                    if (invalidated.status() != 404 && invalidated.status() != 409) throw invalidated;
                }
            }
            jdbc.update("UPDATE agent_step SET status='CANCELLED',lease_token=NULL,lease_until=NULL WHERE run_id=? AND plan_revision=? AND status NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED')",
                    run.id().toString(), run.revision());
            Instant deadline = minimum(run.deadline(), now().plusMillis(replacement.budget().maxMillis()));
            jdbc.update("UPDATE agent_run SET snapshot_id=?,status='QUEUED',plan_revision=?,run_epoch=?,plan_json=?,budget_micros=?,token_budget=?,deadline_at=? WHERE id=?",
                    snapshotId.toString(), replacement.revision(), replacement.runEpoch(), json.write(replacement), replacement.budget().maxCostMicros(),
                    replacement.budget().maxTokens(), timestamp(deadline), run.id().toString());
            Map<String, UUID> ids = new HashMap<>();
            for (TaskPlan.Step step : replacement.steps()) {
                UUID id = UUID.randomUUID();
                ids.put(step.id(), id);
                StepRow reused = reusable.get(step.id());
                jdbc.update("INSERT INTO agent_step(id,run_id,plan_revision,step_key,role_key,status,input_refs,output_refs,read_scopes,write_scopes,required_output) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        id.toString(), run.id().toString(), replacement.revision(), step.id(), step.role(), reused == null ? "PENDING" : "SUCCEEDED",
                        json.write(List.of(contextReference)), reused == null ? "[]" : json.write(reused.outputs()),
                        json.write(step.readScopes().stream().sorted().toList()), json.write(step.writeScopes().stream().sorted().toList()), step.required());
            }
            for (TaskPlan.Step step : replacement.steps()) {
                for (String dependency : step.dependencies()) {
                    jdbc.update("INSERT INTO agent_step_dependency(run_id,plan_revision,step_id,depends_on_id) VALUES(?,?,?,?)",
                            run.id().toString(), replacement.revision(), ids.get(step.id()).toString(), ids.get(dependency).toString());
                }
            }
            jdbc.update("INSERT INTO idempotency_record(user_id,operation,idempotency_key,request_hash,resource_id,expires_at) VALUES(?,?,?,?,?,?)",
                    ownerId.toString(), operation, idempotencyKey, requestHash, run.id().toString(), timestamp(now().plus(1, ChronoUnit.DAYS)));
            emit(run, "agent.run.replanned.v1", Map.of("planRevision", replacement.revision(), "runEpoch", replacement.runEpoch(),
                    "previousPlanRevision", run.revision(), "reusedStepKeys", reusable.keySet().stream().sorted().toList()));
            RunRow current = load(workspaceId, runId, true);
            finishIfSettled(current);
            return view(load(workspaceId, runId, true));
        });
    }

    /** Persist this returned execution key before using it as the provider idempotency key. */
    public ToolView reserveTool(UUID ownerId, UUID workspaceId, Lease lease, String toolKey, String callKey,
                                Object input, long maximumMicros, long maximumTokens) {
        requireIdempotencyKey(callKey);
        if (input == null || toolKey == null || !toolKey.matches("[A-Za-z][A-Za-z0-9]{0,63}")
                || maximumMicros < 0 || maximumTokens < 0 || maximumMicros > 2_000_000 || maximumTokens > 100_000) {
            throw problem("INVALID_RESERVATION", 400, "Tool input and bounded reservations are required");
        }
        String inputJson = json.write(input);
        if (inputJson.length() > 65_536) throw problem("TOOL_INPUT_TOO_LARGE", 413, "Use versioned references for large inputs");
        String inputHash = json.hash(input);
        String executionKey = "exec:" + json.hash(List.of(lease.runId(), lease.planRevision(), lease.stepId(), callKey));
        return transaction(() -> {
            workspaces.lockOwned(ownerId, workspaceId);
            RunRow run = load(workspaceId, lease.runId(), true);
            StepRow step = requireLiveLease(run, lease);
            workspaces.validateSnapshot(ownerId, workspaceId, run.snapshotId());
            if (!plannedStep(run, step.key()).tools().contains(toolKey)) throw problem("TOOL_NOT_ADMITTED", 403, "Tool is not admitted to this step");
            var previous = jdbc.query("SELECT * FROM tool_execution WHERE execution_key=?", this::toolRow, executionKey);
            if (!previous.isEmpty()) {
                ToolRow existing = previous.get(0);
                if (!existing.stepId().equals(step.id()) || !existing.tool().equals(toolKey) || !existing.inputHash().equals(inputHash)
                        || existing.reservedMicros() != maximumMicros || existing.reservedTokens() != maximumTokens) {
                    throw problem("TOOL_IDEMPOTENCY_CONFLICT", 409, "Call key already identifies a different tool request");
                }
                return toolView(existing);
            }
            if (count("SELECT COUNT(*) FROM tool_execution WHERE step_id=?", step.id().toString()) >= 16) {
                throw problem("TOOL_CALL_LIMIT", 409, "Step reached its bounded tool call limit");
            }
            if (maximumMicros > run.budgetMicros() - run.spentMicros() - run.reservedMicros()
                    || maximumTokens > run.tokenBudget() - run.spentTokens() - run.reservedTokens()) {
                throw problem("BUDGET_EXHAUSTED", 409, "Reservation exceeds the uncommitted run budget");
            }
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO tool_execution(id,step_id,execution_key,tool_key,input_hash,status,reserved_micros,reserved_tokens,result_refs) VALUES(?,?,?,?,?,?,?,?,?)",
                    id.toString(), step.id().toString(), executionKey, toolKey, inputHash, "ADMITTED", maximumMicros, maximumTokens, "[]");
            jdbc.update("UPDATE agent_run SET reserved_micros=reserved_micros+?,reserved_tokens=reserved_tokens+? WHERE id=?",
                    maximumMicros, maximumTokens, run.id().toString());
            emit(run, "agent.tool.reserved.v1", Map.of("executionId", id, "stepId", step.id(), "tool", toolKey, "reservedMicros", maximumMicros, "reservedTokens", maximumTokens));
            return toolView(tool(run, id));
        });
    }

    /** Call before external dispatch. A provider job ID may be attached once when it is learned. */
    public ToolView markToolRunning(UUID ownerId, UUID workspaceId, Lease lease, UUID executionId, String providerJobId) {
        validateProviderJobId(providerJobId);
        return transaction(() -> {
            workspaces.lockOwned(ownerId, workspaceId);
            RunRow run = load(workspaceId, lease.runId(), true);
            StepRow step = requireLiveLease(run, lease);
            ToolRow tool = tool(run, executionId);
            if (!tool.stepId().equals(step.id())) throw problem("TOOL_STEP_MISMATCH", 409, "Execution belongs to another step");
            if (!Set.of("ADMITTED", "RUNNING").contains(tool.status())) throw problem("TOOL_NOT_DISPATCHABLE", 409, "Execution must be reconciled or reused, never dispatched again");
            if (tool.providerJobId() != null && !tool.providerJobId().equals(providerJobId)) {
                throw problem("PROVIDER_JOB_CONFLICT", 409, "An execution cannot change its external job identifier");
            }
            if (tool.status().equals("RUNNING") && (tool.providerJobId() != null || providerJobId == null)) {
                throw problem("EXECUTION_ALREADY_DISPATCHED", 409, "Dispatch was already claimed; reconcile instead of making the external call again");
            }
            jdbc.update("UPDATE tool_execution SET status='RUNNING',provider_job_id=? WHERE id=?", providerJobId, executionId.toString());
            emit(run, "agent.tool.dispatched.v1", Map.of("executionId", executionId, "stepId", step.id()));
            return toolView(tool(run, executionId));
        });
    }

    /** Unknown is a durable accounting hold, not a retry instruction. */
    public ToolView markToolUnknown(UUID ownerId, UUID workspaceId, UUID runId, UUID executionId) {
        return transaction(() -> {
            workspaces.lockOwned(ownerId, workspaceId);
            RunRow run = load(workspaceId, runId, true);
            ToolRow tool = tool(run, executionId);
            if (tool.status().equals("UNKNOWN")) return toolView(tool);
            if (!tool.status().equals("RUNNING")) throw problem("TOOL_NOT_RUNNING", 409, "Only dispatched calls can have unknown external outcomes");
            jdbc.update("UPDATE tool_execution SET status='UNKNOWN' WHERE id=?", executionId.toString());
            if (!TERMINAL_RUNS.contains(run.status())) jdbc.update("UPDATE agent_run SET status='WAITING_USER' WHERE id=?", run.id().toString());
            emit(run, "agent.tool.reconciliation.required.v1", Map.of("executionId", executionId));
            return toolView(tool(run, executionId));
        });
    }

    /**
     * Reconciliation remains possible after cancellation: real charges must still be recorded.
     * This method never writes an artifact or accepts a stale worker's step completion.
     */
    public ToolView settleTool(UUID ownerId, UUID workspaceId, UUID runId, UUID executionId,
                               ToolOutcome outcome, long actualMicros, long actualTokens, List<OutputReference> outputs) {
        Objects.requireNonNull(outcome, "outcome");
        List<OutputReference> immutable = validateReferenceShape(outputs, false);
        if (actualMicros < 0 || actualTokens < 0 || actualMicros > 1_000_000_000_000L || actualTokens > 1_000_000_000L) {
            throw problem("INVALID_ACTUAL_USAGE", 400, "Actual usage must be nonnegative and bounded");
        }
        return transaction(() -> {
            workspaces.lockOwned(ownerId, workspaceId);
            RunRow run = load(workspaceId, runId, true);
            ToolRow tool = tool(run, executionId);
            if (TERMINAL_TOOLS.contains(tool.status())) {
                if (!tool.status().equals(outcome.name()) || !Objects.equals(tool.actualMicros(), actualMicros)
                        || !Objects.equals(tool.actualTokens(), actualTokens) || !json.hash(tool.outputs()).equals(json.hash(immutable))) {
                    throw problem("SETTLEMENT_CONFLICT", 409, "A settled execution has different accounting or result data");
                }
                return toolView(tool);
            }
            if (!Set.of("RUNNING", "UNKNOWN").contains(tool.status())) {
                throw problem("TOOL_NOT_DISPATCHED", 409, "Only dispatched or uncertain calls may be settled");
            }
            if (tool.actualMicros() != null && (actualMicros < tool.actualMicros() || actualTokens < tool.actualTokens())) {
                throw problem("BILLING_RECONCILIATION_REQUIRED", 409, "Observed excess usage cannot be silently reduced");
            }
            if (actualMicros > tool.reservedMicros() || actualTokens > tool.reservedTokens()) {
                // Keep all reservations. Expose the observed bill; do not falsify it or breach the budget CHECK.
                jdbc.update("UPDATE tool_execution SET status='UNKNOWN',actual_micros=?,actual_tokens=? WHERE id=?", actualMicros, actualTokens, executionId.toString());
                if (!TERMINAL_RUNS.contains(run.status())) jdbc.update("UPDATE agent_run SET status='WAITING_USER' WHERE id=?", run.id().toString());
                emit(run, "agent.tool.billing.exception.v1", Map.of("executionId", executionId, "observedMicros", actualMicros, "observedTokens", actualTokens));
                return toolView(tool(run, executionId));
            }
            jdbc.update("UPDATE tool_execution SET status=?,actual_micros=?,actual_tokens=?,result_refs=? WHERE id=?",
                    outcome.name(), actualMicros, actualTokens, json.write(immutable), executionId.toString());
            jdbc.update("UPDATE agent_run SET reserved_micros=reserved_micros-?,reserved_tokens=reserved_tokens-?,spent_micros=spent_micros+?,spent_tokens=spent_tokens+? WHERE id=?",
                    tool.reservedMicros(), tool.reservedTokens(), actualMicros, actualTokens, run.id().toString());
            emit(run, "agent.tool.settled.v1", Map.of("executionId", executionId, "outcome", outcome, "actualMicros", actualMicros, "actualTokens", actualTokens));
            return toolView(tool(run, executionId));
        });
    }

    private TaskPlan normalizeScopes(UUID ownerId, UUID workspaceId, UUID releaseId, TaskPlan plan) {
        List<TaskPlan.Step> normalized = new ArrayList<>();
        for (TaskPlan.Step step : plan.steps()) {
            Set<String> reads = new HashSet<>();
            Set<String> writes = new HashSet<>();
            for (String scope : step.readScopes()) reads.add(admitScope(ownerId, workspaceId, releaseId, scope, false));
            for (String scope : step.writeScopes()) writes.add(admitScope(ownerId, workspaceId, releaseId, scope, true));
            normalized.add(new TaskPlan.Step(step.id(), step.role(), step.dependencies(), reads, writes, step.tools(), step.required(), step.acceptance(), step.maxModelSteps()));
        }
        return new TaskPlan(plan.runId(), plan.revision(), plan.runEpoch(), plan.goal(), plan.budget(), normalized);
    }

    private String admitScope(UUID ownerId, UUID workspaceId, UUID releaseId, String scope, boolean write) {
        if (scope == null || scope.length() > 220 || !scope.matches("[a-z]+:[a-f0-9-]{36}(?:/[A-Za-z0-9._:-]+)*") || scope.contains("/..") || scope.endsWith("/")) {
            throw problem("INVALID_SCOPE", 400, "Scopes must be canonical typed resource identifiers");
        }
        String[] segments = scope.split("/", 2);
        String[] identity = segments[0].split(":", 2);
        UUID id;
        try { id = UUID.fromString(identity[1]); }
        catch (IllegalArgumentException exception) { throw problem("INVALID_SCOPE", 400, "Invalid resource UUID"); }
        if (!id.toString().equals(identity[1])) throw problem("INVALID_SCOPE", 400, "Resource UUID must be canonical");
        String suffix = segments.length == 2 ? "/" + segments[1] : "";
        String root = "workspace:" + workspaceId;
        boolean admitted = switch (identity[0]) {
            case "workspace" -> id.equals(workspaceId) && suffix.isEmpty();
            case "artifact" -> count("SELECT COUNT(*) FROM learning_artifact WHERE id=? AND workspace_id=?", id.toString(), workspaceId.toString()) == 1;
            case "goal" -> count("SELECT COUNT(*) FROM learning_goal WHERE id=? AND workspace_id=?", id.toString(), workspaceId.toString()) == 1;
            case "snapshot" -> !write && count("SELECT COUNT(*) FROM context_snapshot WHERE id=? AND workspace_id=?", id.toString(), workspaceId.toString()) == 1;
            case "memory" -> count("SELECT COUNT(*) FROM learner_memory WHERE id=? AND workspace_id=? AND status='ACTIVE'", id.toString(), workspaceId.toString()) == 1;
            case "attempt" -> !write && count("SELECT COUNT(*) FROM exercise_attempt WHERE id=? AND user_id=? AND release_id=?", id.toString(), ownerId.toString(), releaseId.toString()) == 1;
            case "knowledge" -> !write && id.equals(releaseId) && suffix.isEmpty();
            case "chunk" -> !write && count("SELECT COUNT(*) FROM release_chunk WHERE chunk_id=? AND release_id=?", id.toString(), releaseId.toString()) == 1;
            case "learning" -> !write && id.equals(ownerId) && suffix.isEmpty();
            default -> false;
        };
        if (!admitted) throw problem("SCOPE_NOT_ADMITTED", 403, "Resource scope is outside this workspace capability");
        if (identity[0].equals("workspace")) return root;
        if (Set.of("knowledge", "chunk").contains(identity[0])) return "knowledge:" + releaseId + (identity[0].equals("chunk") ? "/" + segments[0] + suffix : "");
        return root + "/" + segments[0] + suffix;
    }

    private void validateOutputReferences(UUID ownerId, UUID workspaceId, List<OutputReference> references) {
        var workspace = workspaces.requireOwned(ownerId, workspaceId);
        for (OutputReference reference : references) {
            List<String> hashes = switch (reference.kind()) {
                case "ARTIFACT" -> jdbc.query("SELECT r.content_hash FROM artifact_revision r JOIN learning_artifact a ON a.id=r.artifact_id WHERE a.id=? AND a.workspace_id=? AND a.current_revision=? AND r.revision=? AND r.review_status='VERIFIED'",
                        (rs, row) -> rs.getString(1), reference.id().toString(), workspaceId.toString(), reference.revision(), reference.revision());
                case "KNOWLEDGE_CHUNK" -> jdbc.query("SELECT k.content_hash FROM knowledge_chunk k JOIN release_chunk r ON r.chunk_id=k.id AND r.chunk_revision=k.revision JOIN content_release cr ON cr.id=r.release_id WHERE k.id=? AND k.revision=? AND k.status='REVIEWED' AND cr.status='PUBLISHED' AND r.release_id=?",
                        (rs, row) -> rs.getString(1), reference.id().toString(), reference.revision(), workspace.releaseId().toString());
                case "SNAPSHOT" -> jdbc.query("SELECT content_hash FROM context_snapshot WHERE id=? AND workspace_id=? AND workspace_revision=?",
                        (rs, row) -> rs.getString(1), reference.id().toString(), workspaceId.toString(), reference.revision());
                case "ATTEMPT" -> reference.revision() != 1 ? List.of() : jdbc.query("SELECT a.answer,a.feedback,a.score FROM exercise_attempt a JOIN workspace_attempt wa ON wa.attempt_id=a.id WHERE a.id=? AND a.user_id=? AND a.release_id=? AND wa.workspace_id=? AND a.status='GRADED'",
                        (rs, row) -> json.hash(Map.of("answer", json.tree(rs.getString("answer")), "feedback", json.tree(rs.getString("feedback")), "score", rs.getBigDecimal("score"))),
                        reference.id().toString(), ownerId.toString(), workspace.releaseId().toString(), workspaceId.toString());
                default -> List.of();
            };
            if (hashes.size() != 1 || !hashes.get(0).equals(reference.contentHash())) {
                throw problem("OUTPUT_REFERENCE_INVALID", 409, "Output must identify existing, current and verified evidence in the same workspace");
            }
            if (reference.kind().equals("SNAPSHOT")) workspaces.validateSnapshot(ownerId, workspaceId, reference.id());
        }
    }

    private OutputReference snapshotReference(UUID workspaceId, UUID snapshotId) {
        var references = jdbc.query("SELECT workspace_revision,content_hash FROM context_snapshot WHERE id=? AND workspace_id=?",
                (rs, row) -> new OutputReference("SNAPSHOT", snapshotId, rs.getInt(1), rs.getString(2)), snapshotId.toString(), workspaceId.toString());
        if (references.isEmpty()) throw problem("SNAPSHOT_NOT_FOUND", 404, "Context snapshot not found in this workspace");
        return references.get(0);
    }

    private List<OutputReference> validateReferenceShape(List<OutputReference> references, boolean requireOutput) {
        if (references == null || references.size() > 32 || (requireOutput && references.isEmpty())) {
            throw problem("INVALID_OUTPUT_REFERENCES", 400, "Provide one to 32 versioned output references");
        }
        Set<String> identities = new HashSet<>();
        for (OutputReference reference : references) {
            if (reference == null || reference.kind() == null || !Set.of("ARTIFACT", "KNOWLEDGE_CHUNK", "ATTEMPT", "SNAPSHOT").contains(reference.kind())
                    || reference.id() == null || reference.revision() < 1 || reference.contentHash() == null
                    || !reference.contentHash().matches("[a-f0-9]{64}") || !identities.add(reference.kind() + ":" + reference.id())) {
                throw problem("INVALID_OUTPUT_REFERENCE", 400, "Output references must be unique, typed and versioned");
            }
        }
        return List.copyOf(references);
    }

    private void recoverExpiredLeases(RunRow run) {
        boolean unknown = false;
        for (StepRow step : steps(run)) {
            if (!ACTIVE_STEPS.contains(step.status()) || step.leaseUntil() == null || now().isBefore(step.leaseUntil())) continue;
            releaseUndispatched(run, step.id());
            jdbc.update("UPDATE tool_execution SET status='UNKNOWN' WHERE step_id=? AND status='RUNNING'", step.id().toString());
            boolean unsettled = unsettledTools(step.id()) > 0;
            unknown |= unsettled;
            String next = unsettled ? "WAITING_TOOL" : step.attempts() < MAX_ATTEMPTS ? "PENDING" : "FAILED";
            jdbc.update("UPDATE agent_step SET status=?,lease_token=NULL,lease_until=NULL WHERE id=?", next, step.id().toString());
            emit(run, "agent.step.lease.expired.v1", Map.of("stepId", step.id(), "attempt", step.attempts(), "state", next));
        }
        if (unknown) jdbc.update("UPDATE agent_run SET status='WAITING_USER' WHERE id=?", run.id().toString());
        else finishIfSettled(run);
    }

    private void expireRun(RunRow run) {
        fenceToolCalls(run);
        long epoch = Math.addExact(run.epoch(), 1);
        jdbc.update("UPDATE agent_step SET status='CANCELLED',lease_token=NULL,lease_until=NULL WHERE run_id=? AND status NOT IN ('SUCCEEDED','FAILED','SKIPPED','CANCELLED')", run.id().toString());
        String terminal = steps(run).stream().anyMatch(step -> step.status().equals("SUCCEEDED")) ? "PARTIAL" : "FAILED";
        jdbc.update("UPDATE agent_run SET status=?,run_epoch=?,plan_json=? WHERE id=?", terminal, epoch, json.write(withEpoch(run.plan(), epoch)), run.id().toString());
        emit(run, "agent.run.deadline.exceeded.v1", Map.of("status", terminal, "runEpoch", epoch));
    }

    /** Stop invalid snapshots once, fence every outstanding worker and retain uncertain charges. */
    private void suspendInvalidContext(RunRow run, String causeCode) {
        fenceToolCalls(run);
        long epoch = Math.addExact(run.epoch(), 1);
        for (StepRow step : steps(run)) {
            if (TERMINAL_STEPS.contains(step.status())) continue;
            String state = unsettledTools(step.id()) > 0 ? "WAITING_TOOL" : step.attempts() < MAX_ATTEMPTS ? "PENDING" : "FAILED";
            jdbc.update("UPDATE agent_step SET status=?,lease_token=NULL,lease_until=NULL WHERE id=?", state, step.id().toString());
        }
        jdbc.update("UPDATE agent_run SET status='WAITING_USER',run_epoch=?,plan_json=? WHERE id=?", epoch, json.write(withEpoch(run.plan(), epoch)), run.id().toString());
        emit(run, "agent.run.context.invalidated.v1", Map.of("status", "WAITING_USER", "runEpoch", epoch,
                "reason", "CONTEXT_INVALIDATED", "causeCode", causeCode, "nextAction", "CANCEL_AND_START_WITH_FRESH_CONTEXT"));
    }

    private void fenceToolCalls(RunRow run) {
        for (StepRow step : steps(run)) {
            releaseUndispatched(run, step.id());
            jdbc.update("UPDATE tool_execution SET status='UNKNOWN' WHERE step_id=? AND status='RUNNING'", step.id().toString());
        }
    }

    private void releaseUndispatched(RunRow run, UUID stepId) {
        for (ToolRow tool : jdbc.query("SELECT * FROM tool_execution WHERE step_id=? AND status='ADMITTED'", this::toolRow, stepId.toString())) {
            jdbc.update("UPDATE tool_execution SET status='CANCELLED',actual_micros=0,actual_tokens=0 WHERE id=?", tool.id().toString());
            jdbc.update("UPDATE agent_run SET reserved_micros=reserved_micros-?,reserved_tokens=reserved_tokens-? WHERE id=?",
                    tool.reservedMicros(), tool.reservedTokens(), run.id().toString());
        }
    }

    private void finishIfSettled(RunRow run) {
        boolean changed;
        do {
            changed = false;
            Map<String, StepRow> byKey = new HashMap<>();
            steps(run).forEach(step -> byKey.put(step.key(), step));
            for (TaskPlan.Step planned : run.plan().steps()) {
                StepRow step = byKey.get(planned.id());
                if (Set.of("PENDING", "READY").contains(step.status())
                        && planned.dependencies().stream().map(byKey::get).anyMatch(dependency -> Set.of("FAILED", "SKIPPED", "CANCELLED").contains(dependency.status()))) {
                    jdbc.update("UPDATE agent_step SET status='SKIPPED',lease_token=NULL,lease_until=NULL WHERE id=?", step.id().toString());
                    emit(run, "agent.step.blocked.v1", Map.of("stepId", step.id(), "reason", "DEPENDENCY_FAILED"));
                    changed = true;
                }
            }
        } while (changed);
        List<StepRow> states = steps(run);
        if (states.stream().allMatch(step -> TERMINAL_STEPS.contains(step.status())) && unsettledRunTools(run.id()) == 0) {
            String terminal = states.stream().allMatch(step -> step.status().equals("SUCCEEDED")) ? "COMPLETED"
                    : states.stream().anyMatch(step -> step.status().equals("SUCCEEDED")) ? "PARTIAL" : "FAILED";
            if (!TERMINAL_RUNS.contains(load(run.workspaceId(), run.id(), false).status())) {
                jdbc.update("UPDATE agent_run SET status=? WHERE id=?", terminal, run.id().toString());
                emit(run, "agent.run.finished.v1", Map.of("status", terminal, "verification", "executor-receipts-and-reference-integrity"));
            }
        }
    }

    private List<TaskPlan.Step> otherWorkspaceLeases(RunRow run) {
        return jdbc.query("SELECT s.step_key,s.role_key,s.read_scopes,s.write_scopes FROM agent_step s JOIN agent_run r ON r.id=s.run_id WHERE r.workspace_id=? AND r.id<>? AND s.status IN ('RUNNING','WAITING_TOOL','REVIEWING') AND r.status NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELLED')",
                (rs, row) -> new TaskPlan.Step(rs.getString(1), rs.getString(2), List.of(), strings(rs.getString(3)), strings(rs.getString(4)), Set.of(), true, List.of("held scope"), 0),
                run.workspaceId().toString(), run.id().toString());
    }

    private StepRow requireLiveLease(RunRow run, Lease lease) {
        requireEpoch(run, lease.runEpoch());
        if (!run.id().equals(lease.runId()) || run.revision() != lease.planRevision() || !run.status().equals("RUNNING")) {
            throw problem("STALE_LEASE", 409, "Run is not accepting this worker lease");
        }
        StepRow step = step(run, lease.stepId());
        if (!step.status().equals("RUNNING") || !step.key().equals(lease.stepKey()) || !Objects.equals(step.leaseToken(), lease.token())
                || step.attempts() != lease.attempt() || step.leaseUntil() == null || !now().isBefore(step.leaseUntil()) || !now().isBefore(run.deadline())) {
            throw problem("STALE_LEASE", 409, "Worker lease is stale, expired or no longer owned");
        }
        return step;
    }

    private void requireEpoch(RunRow run, long epoch) {
        if (epoch != run.epoch()) throw problem("STALE_RUN_EPOCH", 409, "Run changed; reload its current state");
    }

    private void emit(RunRow run, String type, Map<String, ?> details) {
        String eventType = type.toUpperCase(Locale.ROOT);
        long next = Objects.requireNonNull(jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0)+1 FROM agent_run_event WHERE run_id=?", Long.class, run.id().toString()));
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("runId", run.id());
        payload.put("workspaceId", run.workspaceId());
        payload.put("schemaVersion", 1);
        jdbc.update("INSERT INTO agent_run_event(run_id,sequence_no,event_type,payload,created_at) VALUES(?,?,?,?,?)", run.id().toString(), next, eventType, json.write(payload), timestamp(now()));
        journal.append(run.workspaceId(), eventType, "SERVER", payload);
    }

    private RunView view(RunRow run) {
        List<StepView> views = steps(run).stream().map(step -> new StepView(step.id(), step.key(), step.role(), step.status(), step.attempts(), step.required(), step.outputs())).toList();
        return new RunView(run.id(), run.workspaceId(), run.goalId(), run.snapshotId(), run.status(), run.revision(), run.epoch(), run.plan(),
                run.budgetMicros(), run.tokenBudget(), run.spentMicros(), run.reservedMicros(), run.spentTokens(), run.reservedTokens(), run.deadline(), views,
                List.of("Deterministic durable runtime; no model, compiler or media worker is implicitly executed.",
                        "COMPLETED describes registered executor receipts and reference integrity, not an independently measured learning outcome.",
                        "Unknown external calls retain their reservation until provider reconciliation; billing overruns require operator review."));
    }

    private RunRow load(UUID workspaceId, UUID runId, boolean lock) {
        List<RunRow> rows = jdbc.query("SELECT * FROM agent_run WHERE id=? AND workspace_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new RunRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("workspace_id")),
                        UUID.fromString(rs.getString("goal_id")), UUID.fromString(rs.getString("snapshot_id")), rs.getString("status"),
                        rs.getInt("plan_revision"), rs.getLong("run_epoch"), json.read(rs.getString("plan_json"), TaskPlan.class),
                        rs.getLong("budget_micros"), rs.getLong("token_budget"), rs.getLong("reserved_micros"), rs.getLong("spent_micros"),
                        rs.getLong("reserved_tokens"), rs.getLong("spent_tokens"), rs.getTimestamp("deadline_at").toInstant()), runId.toString(), workspaceId.toString());
        if (rows.isEmpty()) throw problem("RUN_NOT_FOUND", 404, "Run not found in this workspace");
        return rows.get(0);
    }

    private List<StepRow> steps(RunRow run) {
        List<StepRow> values = jdbc.query("SELECT * FROM agent_step WHERE run_id=? AND plan_revision=?", this::stepRow, run.id().toString(), run.revision());
        Map<String, StepRow> indexed = new HashMap<>();
        values.forEach(value -> indexed.put(value.key(), value));
        return run.plan().steps().stream().map(planned -> Objects.requireNonNull(indexed.get(planned.id()), "persisted plan/steps drift")).toList();
    }

    private StepRow step(RunRow run, UUID id) {
        return steps(run).stream().filter(step -> step.id().equals(id)).findFirst().orElseThrow(() -> problem("STEP_NOT_FOUND", 404, "Step not found in this run"));
    }

    private StepRow stepRow(ResultSet rs, int row) throws SQLException {
        Timestamp until = rs.getTimestamp("lease_until");
        String token = rs.getString("lease_token");
        return new StepRow(UUID.fromString(rs.getString("id")), rs.getString("step_key"), rs.getString("role_key"), rs.getString("status"),
                rs.getInt("attempt_count"), rs.getBoolean("required_output"), token == null ? null : UUID.fromString(token),
                until == null ? null : until.toInstant(), references(rs.getString("output_refs")));
    }

    private ToolRow tool(RunRow run, UUID id) {
        List<ToolRow> rows = jdbc.query("SELECT t.* FROM tool_execution t JOIN agent_step s ON s.id=t.step_id WHERE t.id=? AND s.run_id=?",
                this::toolRow, id.toString(), run.id().toString());
        if (rows.isEmpty()) throw problem("EXECUTION_NOT_FOUND", 404, "Tool execution not found in this run");
        return rows.get(0);
    }

    private ToolRow toolRow(ResultSet rs, int row) throws SQLException {
        long micros = rs.getLong("actual_micros");
        Long actualMicros = rs.wasNull() ? null : micros;
        long tokens = rs.getLong("actual_tokens");
        Long actualTokens = rs.wasNull() ? null : tokens;
        return new ToolRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("step_id")), rs.getString("execution_key"),
                rs.getString("tool_key"), rs.getString("input_hash"), rs.getString("status"), rs.getLong("reserved_micros"), rs.getLong("reserved_tokens"),
                actualMicros, actualTokens, rs.getString("provider_job_id"), references(rs.getString("result_refs")));
    }

    private ToolView toolView(ToolRow row) {
        return new ToolView(row.id(), row.executionKey(), row.tool(), row.status(), row.reservedMicros(), row.reservedTokens(),
                row.actualMicros(), row.actualTokens(), row.providerJobId(), row.outputs());
    }

    private int unsettledTools(UUID stepId) {
        return count("SELECT COUNT(*) FROM tool_execution WHERE step_id=? AND status IN ('ADMITTED','RUNNING','UNKNOWN')", stepId.toString());
    }

    private int unsettledRunTools(UUID runId) {
        return count("SELECT COUNT(*) FROM tool_execution t JOIN agent_step s ON s.id=t.step_id WHERE s.run_id=? AND t.status IN ('ADMITTED','RUNNING','UNKNOWN')", runId.toString());
    }

    private TaskPlan.Step plannedStep(RunRow run, String key) {
        return run.plan().steps().stream().filter(step -> step.id().equals(key)).findFirst().orElseThrow();
    }

    private List<OutputReference> references(String value) {
        return List.of(json.read(value, OutputReference[].class));
    }

    private Set<String> strings(String value) {
        return Set.of(json.read(value, String[].class));
    }

    private int count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args)); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static Instant minimum(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant); }
    private <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    private static DomainException problem(String code, int status, String message) { return new DomainException(code, status, message); }
    private static TaskPlan withEpoch(TaskPlan plan, long epoch) { return new TaskPlan(plan.runId(), plan.revision(), epoch, plan.goal(), plan.budget(), plan.steps()); }
    private static Map<String, Object> planIdentity(TaskPlan plan) {
        // Set iteration order can change after a JVM restart; it must not change request identity.
        List<Map<String, Object>> steps = plan.steps().stream().map(DurableAgentRuntime::stepIdentity).toList();
        return Map.of("runId", plan.runId(), "revision", plan.revision(), "runEpoch", plan.runEpoch(),
                "goal", plan.goal(), "budget", plan.budget(), "steps", steps);
    }
    private static Map<String, Object> stepIdentity(TaskPlan.Step step) {
        return Map.of("id", step.id(), "role", step.role(), "dependencies", step.dependencies(),
                "readScopes", step.readScopes().stream().sorted().toList(),
                "writeScopes", step.writeScopes().stream().sorted().toList(),
                "tools", step.tools().stream().sorted().toList(), "required", step.required(),
                "acceptance", step.acceptance(), "maxModelSteps", step.maxModelSteps());
    }
    private static void requireIdempotencyKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,64}")) throw problem("INVALID_IDEMPOTENCY_KEY", 400, "Use an 8 to 64 character idempotency key");
    }
    private static void validateLeaseDuration(long milliseconds) {
        if (milliseconds < 1_000 || milliseconds > 60_000) throw problem("INVALID_LEASE_DURATION", 400, "Lease duration must be 1000 to 60000 milliseconds");
    }
    private static void validateProviderJobId(String id) {
        if (id != null && (id.isBlank() || id.length() > 255 || id.chars().anyMatch(Character::isISOControl))) throw problem("INVALID_PROVIDER_JOB_ID", 400, "Provider job identifier is invalid");
    }
    private void validatePlan(TaskPlan plan) {
        try { validator.validate(plan); }
        catch (IllegalArgumentException | NullPointerException exception) { throw problem("INVALID_PLAN", 400, "Plan violates the registered DAG, role or budget constraints"); }
    }
    private record RunRow(UUID id, UUID workspaceId, UUID goalId, UUID snapshotId, String status, int revision, long epoch,
                          TaskPlan plan, long budgetMicros, long tokenBudget, long reservedMicros, long spentMicros,
                          long reservedTokens, long spentTokens, Instant deadline) {}
    private record StepRow(UUID id, String key, String role, String status, int attempts, boolean required,
                           UUID leaseToken, Instant leaseUntil, List<OutputReference> outputs) {}
    private record ToolRow(UUID id, UUID stepId, String executionKey, String tool, String inputHash, String status,
                           long reservedMicros, long reservedTokens, Long actualMicros, Long actualTokens,
                           String providerJobId, List<OutputReference> outputs) {}
}
