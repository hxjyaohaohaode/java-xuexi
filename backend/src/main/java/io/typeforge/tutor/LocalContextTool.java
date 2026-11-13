package io.typeforge.tutor;

import io.typeforge.orchestration.AgentTool;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceAccess.OwnedWorkspace;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Typed, read-only adapter for the already admitted snapshot; never creates a new context. */
@Component
public final class LocalContextTool implements AgentTool<LocalContextTool.Input,WorkspaceService.Context> {
    public record Input(UUID id) {}
    record Admission(OwnedWorkspace workspace,UUID snapshotId) {}
    private record Execution(UUID snapshotId,int planRevision,long runEpoch,String runStatus,Instant deadline,
                             int stepRevision,String stepStatus,Instant leaseUntil,String toolKey,String inputHash,String toolStatus) {}
    private final WorkspaceService workspaces;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final SchemaCatalog schemas;
    private final Clock clock;
    public LocalContextTool(WorkspaceService workspaces,JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json,SchemaCatalog schemas,Clock clock) {
        this.workspaces=workspaces;this.jdbc=jdbc;this.tx=tx;this.json=json;this.schemas=schemas;this.clock=clock;
    }
    @Override public String name() { return "retrieveContext"; }
    @Override public String inputSchemaId() { return "ToolResourceInput"; }
    @Override public String outputSchemaId() { return "ContextSnapshot"; }
    @Override public WorkspaceService.Context execute(Input input,AgentTool.Context context) {
        schemas.validate(inputSchemaId(),input);
        return tx.execute(status->{
            var admission=admit(name(),input,context,workspaces,jdbc,json,clock);
            if(!admission.snapshotId().equals(input.id())) throw DomainException.missing();
            var result=workspaces.readContext(context.ownerId(),context.workspaceId(),input.id());
            schemas.validate(outputSchemaId(),result);
            return result;
        });
    }

    /** Shared only by the two fixed local adapters; this does not dispatch arbitrary tool names. */
    static Admission admit(String tool,Object input,AgentTool.Context context,WorkspaceService workspaces,JdbcTemplate jdbc,JsonCodec json,Clock clock) {
        if(context==null || context.ownerId()==null || context.workspaceId()==null || context.runId()==null
            || context.planRevision()<1 || context.runEpoch()<1 || context.executionKey()==null || context.executionKey().length()>128 || context.deadline()==null) {
            throw DomainException.invalid("An authoritative tool execution context is required");
        }
        var workspace=workspaces.lockOwned(context.ownerId(),context.workspaceId());
        var rows=jdbc.query("SELECT r.snapshot_id,r.plan_revision,r.run_epoch,r.status AS run_status,r.deadline_at,s.plan_revision AS step_revision,s.status AS step_status,s.lease_until,t.tool_key,t.input_hash,t.status AS tool_status FROM agent_run r JOIN agent_step s ON s.run_id=r.id JOIN tool_execution t ON t.step_id=s.id WHERE r.id=? AND r.workspace_id=? AND t.execution_key=?",
            (rs,n)->{
                Timestamp lease=rs.getTimestamp("lease_until");
                return new Execution(UUID.fromString(rs.getString("snapshot_id")),rs.getInt("plan_revision"),rs.getLong("run_epoch"),rs.getString("run_status"),rs.getTimestamp("deadline_at").toInstant(),
                    rs.getInt("step_revision"),rs.getString("step_status"),lease==null?null:lease.toInstant(),rs.getString("tool_key"),rs.getString("input_hash"),rs.getString("tool_status"));
            },
            context.runId().toString(),context.workspaceId().toString(),context.executionKey());
        if(rows.isEmpty()) throw DomainException.missing();
        var row=rows.get(0);
        if(!"RUNNING".equals(row.runStatus()) || !"RUNNING".equals(row.stepStatus()) || !"RUNNING".equals(row.toolStatus())
            || row.planRevision()!=context.planRevision() || row.stepRevision()!=context.planRevision()
            || row.runEpoch()!=context.runEpoch() || row.leaseUntil()==null || !clock.instant().isBefore(row.leaseUntil())
            || !clock.instant().isBefore(row.deadline()) || !clock.instant().isBefore(context.deadline()) || context.deadline().isAfter(row.deadline())
            || !tool.equals(row.toolKey()) || !json.hash(input).equals(row.inputHash())) {
            throw new DomainException("STALE_TOOL_CONTEXT",409,"Tool execution is stale, expired or does not match the admitted request");
        }
        UUID snapshot=row.snapshotId();
        workspaces.validateSnapshot(context.ownerId(),context.workspaceId(),snapshot);
        return new Admission(workspace,snapshot);
    }
}
