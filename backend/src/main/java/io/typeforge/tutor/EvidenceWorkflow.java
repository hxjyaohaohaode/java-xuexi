package io.typeforge.tutor;

import io.typeforge.orchestration.AgentTool;
import io.typeforge.orchestration.DurableAgentRuntime;
import io.typeforge.orchestration.DurableAgentRuntime.OutputReference;
import io.typeforge.orchestration.TaskPlan;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Real local context + retrieval adapters. It deliberately does not impersonate an LLM tutor. */
@Service
public class EvidenceWorkflow {
    private final DurableAgentRuntime runtime;
    private final WorkspaceService workspaces;
    private final LocalContextTool contextTool;
    private final LocalSearchTool searchTool;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final boolean enabled;
    public EvidenceWorkflow(DurableAgentRuntime runtime,WorkspaceService workspaces,LocalContextTool contextTool,LocalSearchTool searchTool,JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json,
        @Value("${typeforge.events.dispatch-enabled:true}") boolean enabled) {
        this.runtime=runtime;this.workspaces=workspaces;this.contextTool=contextTool;this.searchTool=searchTool;this.jdbc=jdbc;this.tx=tx;this.json=json;this.enabled=enabled;
    }
    public DurableAgentRuntime.RunView create(UUID owner,UUID workspace,UUID goal,UUID snapshot,String query,String key) {
        WorkspaceService.requireKey(key);WorkspaceService.requireText(query,500);
        return tx.execute(status->{
            var scope=workspaces.lockOwned(owner,workspace);
            var target=workspaces.goal(owner,workspace,goal);
            UUID runId=UUID.nameUUIDFromBytes(("typeforge:local-evidence:"+owner+":"+workspace+":"+key).getBytes(StandardCharsets.UTF_8));
            TaskPlan plan=new TaskPlan(runId,1,1,target.text(),new TaskPlan.Budget(1,120000,1,1),List.of(
                new TaskPlan.Step("context","conductor",List.of(),Set.of("workspace:"+workspace),Set.of(),Set.of("retrieveContext"),true,List.of("Snapshot remains authorized and unrevoked"),0),
                new TaskPlan.Step("evidence","retrieval-researcher",List.of("context"),Set.of("knowledge:"+scope.releaseId()),Set.of(),Set.of("searchEvidence"),true,List.of("Nonempty versioned evidence; query hash "+json.sha256(query)),0)));
            var run=runtime.create(owner,workspace,goal,snapshot,plan,key);
            if(jdbc.queryForList("SELECT run_id FROM local_evidence_workflow WHERE run_id=?",run.id().toString()).isEmpty()) jdbc.update("INSERT INTO local_evidence_workflow(run_id,query_text,adapter_version) VALUES(?,?,'local-evidence-v1')",run.id().toString(),query);
            return run;
        });
    }
    @Scheduled(fixedDelayString="${typeforge.events.poll-millis:1500}")
    public void dispatch() {
        if(!enabled) return;
        var rows=jdbc.queryForList("SELECT r.id,r.workspace_id,w.owner_id FROM local_evidence_workflow f JOIN agent_run r ON r.id=f.run_id JOIN learning_workspace w ON w.id=r.workspace_id WHERE r.status IN ('QUEUED','RUNNING') AND f.adapter_version='local-evidence-v1' ORDER BY f.created_at,r.id LIMIT 16");
        for(var row:rows) {
            try { advance(UUID.fromString(row.get("owner_id").toString()),UUID.fromString(row.get("workspace_id").toString()),UUID.fromString(row.get("id").toString())); }
            catch(DomainException ignored) { /* User cancellation or revoked context fences this worker. */ }
        }
    }
    /** Internal deterministic worker entry point, not a client-supplied completion endpoint. */
    public void advance(UUID owner,UUID workspace,UUID runId) {
        var queryRows=jdbc.queryForList("SELECT query_text FROM local_evidence_workflow WHERE run_id=? AND adapter_version='local-evidence-v1'",String.class,runId.toString());
        if(queryRows.isEmpty()) throw DomainException.missing();
        for(int round=0;round<2;round++) {
            var run=runtime.get(owner,workspace,runId);
            if(!supports(run.plan())) {
                runtime.command(owner,workspace,runId,run.runEpoch(),DurableAgentRuntime.Command.CANCEL);
                throw new DomainException("UNSUPPORTED_LOCAL_PLAN",409,"The local adapter accepts only its fixed context and evidence workflow");
            }
            // These local reads have a 5-second cooperative commit deadline, not a hard SQL/CPU interrupt.
            var claims=runtime.claim(owner,workspace,runId,run.runEpoch(),5000);
            if(claims.isEmpty()) return;
            for(var lease:claims) {
                try {
                    tx.executeWithoutResult(status->{
                        var scope=workspaces.lockOwned(owner,workspace);
                        boolean contextStep=lease.stepKey().equals("context");
                        String tool=contextStep?contextTool.name():searchTool.name();
                        var contextInput=new LocalContextTool.Input(run.snapshotId());
                        var searchInput=new LocalSearchTool.Input(queryRows.get(0),scope.releaseId(),8);
                        // The reservation hash describes the exact schema input actually executed.
                        var call=runtime.reserveTool(owner,workspace,lease,tool,"local-read-v1",contextStep?contextInput:searchInput,0,0);
                        if(call.status().equals("SUCCEEDED")) { runtime.complete(owner,workspace,lease,call.resultRefs());return; }
                        runtime.markToolRunning(owner,workspace,lease,call.id(),null);
                        var toolContext=new AgentTool.Context(owner,workspace,runId,lease.planRevision(),lease.runEpoch(),call.executionKey(),lease.expiresAt());
                        List<OutputReference> outputs;
                        if(contextStep) {
                            var context=contextTool.execute(contextInput,toolContext);
                            outputs=List.of(new OutputReference("SNAPSHOT",context.id(),context.workspaceRevision(),context.contentHash()));
                        } else {
                            outputs=searchTool.execute(searchInput,toolContext).items().stream()
                                .map(evidence->new OutputReference("KNOWLEDGE_CHUNK",evidence.chunkId(),evidence.revision(),evidence.contentHash())).toList();
                        }
                        runtime.settleTool(owner,workspace,runId,call.id(),DurableAgentRuntime.ToolOutcome.SUCCEEDED,0,0,outputs);
                        if(outputs.isEmpty()) runtime.fail(owner,workspace,lease,"INSUFFICIENT_EVIDENCE",false);
                        else runtime.complete(owner,workspace,lease,outputs);
                    });
                } catch(DomainException e) {
                    try { runtime.fail(owner,workspace,lease,"LOCAL_EVIDENCE_UNAVAILABLE",false); }
                    catch(DomainException fenced) { /* Terminal/changed state already wins. */ }
                }
            }
        }
    }
    private static boolean supports(TaskPlan plan) {
        if(plan.steps().size()!=2) return false;
        for(var step:plan.steps()) {
            if(!step.required() || !step.writeScopes().isEmpty()) return false;
            if(step.id().equals("context")) {
                if(!step.role().equals("conductor") || !step.dependencies().isEmpty() || !step.tools().equals(Set.of("retrieveContext"))) return false;
            } else if(step.id().equals("evidence")) {
                if(!step.role().equals("retrieval-researcher") || !step.dependencies().equals(List.of("context")) || !step.tools().equals(Set.of("searchEvidence"))) return false;
            } else return false;
        }
        return true;
    }
}
