package io.typeforge.orchestration;

import io.typeforge.identity.AccountClosureParticipant;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Runtime commands keep epochs, reservations and node leases consistent on account withdrawal. */
@Component @Order(20)
public final class AgentAccountClosureParticipant implements AccountClosureParticipant {
    private final JdbcTemplate jdbc;
    private final DurableAgentRuntime runs;
    public AgentAccountClosureParticipant(JdbcTemplate jdbc,DurableAgentRuntime runs) {this.jdbc=jdbc;this.runs=runs;}
    @Override public void closeWorkspace(UUID owner,UUID workspace,boolean active) {
        if(!active) return;
        var pending=jdbc.queryForList("SELECT id,run_epoch FROM agent_run WHERE workspace_id=? AND status NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELLED') ORDER BY id",workspace.toString());
        for(var run:pending) runs.command(owner,workspace,UUID.fromString(run.get("id").toString()),((Number)run.get("run_epoch")).longValue(),DurableAgentRuntime.Command.CANCEL);
    }
}
