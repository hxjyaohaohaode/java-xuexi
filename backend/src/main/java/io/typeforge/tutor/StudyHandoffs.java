package io.typeforge.tutor;

import io.typeforge.orchestration.DurableAgentRuntime.Lease;
import io.typeforge.orchestration.DurableAgentRuntime.RunView;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** A receipt is committed in the same transaction as the receiving specialist's admitted work. */
public final class StudyHandoffs {
    public record Receipt(String fromRole,String toRole,String fromStep,String toStep,String kind,
                          int references,int planRevision,long runEpoch) {}
    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    StudyHandoffs(JdbcTemplate jdbc,JsonCodec json) { this.jdbc=jdbc;this.json=json; }

    void receive(RunView run,Lease lease) {
        var target=run.plan().steps().stream().filter(step->step.id().equals(lease.stepKey())).findFirst().orElseThrow();
        for(String dependency:target.dependencies()) {
            var source=run.steps().stream().filter(step->step.key().equals(dependency)).findFirst().orElseThrow();
            if(!source.status().equals("SUCCEEDED") || source.outputRefs().isEmpty())
                throw DomainException.conflict("A classroom handoff requires a completed prerequisite with an output");
            String kind=dependency.equals("review")?"VERDICT":dependency.equals("planner")?"QUESTION":"PROPOSAL";
            String dedupe=lease.planRevision()+":"+lease.runEpoch()+":"+dependency+":"+target.id();
            // A reclaimed lease in the same epoch consumes the same immutable handoff.
            if(!jdbc.queryForList("SELECT id FROM agent_message WHERE run_id=? AND dedupe_key=?",run.id().toString(),dedupe).isEmpty())continue;
            var receipt=new Receipt(source.role(),target.role(),dependency,target.id(),kind,source.outputRefs().size(),
                lease.planRevision(),lease.runEpoch());
            jdbc.update("""
                INSERT INTO agent_message(id,run_id,sender_role,receiver_role,delivery_state,dedupe_key,kind,payload,evidence_refs)
                VALUES(?,?,?,?,'ACKNOWLEDGED',?,?,?,?)
                """,UUID.randomUUID().toString(),run.id().toString(),source.role(),target.role(),dedupe,kind,json.write(receipt),json.write(source.outputRefs()));
        }
    }

    List<Receipt> list(UUID run) {
        return jdbc.query("""
            SELECT payload FROM agent_message WHERE run_id=? AND delivery_state='ACKNOWLEDGED'
            ORDER BY created_at,dedupe_key
            """,(rs,n)->json.read(rs.getString(1),Receipt.class),run.toString());
    }
}
