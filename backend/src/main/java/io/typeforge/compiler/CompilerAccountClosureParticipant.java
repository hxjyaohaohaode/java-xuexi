package io.typeforge.compiler;

import io.typeforge.identity.AccountClosureParticipant;
import io.typeforge.shared.JsonCodec;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Account closure already holds the owner and workspace locks; no HTTP or fresh grading is admitted here. */
@Component
@Order(40)
public final class CompilerAccountClosureParticipant implements AccountClosureParticipant {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonCodec json;
    public CompilerAccountClosureParticipant(JdbcTemplate jdbc,Clock clock,JsonCodec json){this.jdbc=jdbc;this.clock=clock;this.json=json;}
    @Override public void closeAccount(UUID owner) {
        // Only unfinished work is cancelled and scrubbed. Completed, immutable grading history remains retained.
        jdbc.update("UPDATE exercise_attempt SET status='FAILED',score=NULL,maximum=NULL WHERE user_id=? AND status IN ('PENDING','GRADING') AND id IN (SELECT attempt_id FROM compile_job WHERE user_id=? AND status IN ('QUEUED','RUNNING'))",owner.toString(),owner.toString());
        jdbc.update("UPDATE workspace_compile SET request_json='[]',request_hash=?,error_code='ACCOUNT_CLOSED' WHERE job_id IN (SELECT id FROM compile_job WHERE user_id=? AND status IN ('QUEUED','RUNNING'))",json.hash(List.of()),owner.toString());
        jdbc.update("UPDATE compile_job SET status='CANCELLED',source_code='',result=NULL,lease_token=NULL,lease_until=NULL,finished_at=? WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Timestamp.from(clock.instant()),owner.toString());
        // Keep assistance facts for the retained ledger while removing the personalized hint bodies.
        jdbc.update("UPDATE exercise_hint_receipt SET hint_text='' WHERE user_id=?",owner.toString());
    }
}
