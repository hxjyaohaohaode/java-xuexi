package io.typeforge.tutor;

import io.typeforge.identity.AccountClosureParticipant;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Keeps cancellation and provider-usage reconciliation in the module that owns the request state. */
@Component @Order(10)
public final class TutorAccountClosureParticipant implements AccountClosureParticipant {
    private final JdbcTemplate jdbc;
    private final TutorService tutor;
    public TutorAccountClosureParticipant(JdbcTemplate jdbc,TutorService tutor) {this.jdbc=jdbc;this.tutor=tutor;}
    @Override public void closeWorkspace(UUID owner,UUID workspace,boolean active) {
        if(!active) return;
        var requests=jdbc.queryForList("SELECT r.id,x.request_epoch FROM tutor_request r JOIN tutor_request_runtime x ON x.request_id=r.id WHERE x.workspace_id=? AND r.status IN ('QUEUED','RETRIEVING','GENERATING','VALIDATING') ORDER BY r.id",workspace.toString());
        for(var request:requests) tutor.cancel(owner,UUID.fromString(request.get("id").toString()),((Number)request.get("request_epoch")).longValue());
    }
}
