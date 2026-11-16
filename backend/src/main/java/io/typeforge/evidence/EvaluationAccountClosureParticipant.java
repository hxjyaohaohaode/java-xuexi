package io.typeforge.evidence;

import io.typeforge.identity.AccountClosureParticipant;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Preserve immutable historical evidence under the disabled identity; revoke all unfinished work. */
@Component
@Order(50)
public final class EvaluationAccountClosureParticipant implements AccountClosureParticipant {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public EvaluationAccountClosureParticipant(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    @Override public void closeWorkspace(UUID owner,UUID workspace,boolean active){
        Timestamp now=Timestamp.from(clock.instant());
        jdbc.update("UPDATE evaluation_suite SET status='ARCHIVED',updated_at=? WHERE owner_id=? AND workspace_id=? AND status='ACTIVE'",now,owner.toString(),workspace.toString());
        jdbc.update("UPDATE evaluation_run SET status='CANCELLED',error_code='ACCOUNT_CLOSED',lease_token=NULL,lease_until=NULL,finished_at=? WHERE owner_id=? AND workspace_id=? AND status IN ('QUEUED','RUNNING')",now,owner.toString(),workspace.toString());
    }
}
