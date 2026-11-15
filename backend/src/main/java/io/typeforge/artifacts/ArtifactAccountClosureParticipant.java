package io.typeforge.artifacts;

import io.typeforge.identity.AccountClosureParticipant;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Withdraws original bytes and disposable export caches while preserving source revision evidence. */
@Component @Order(30)
public final class ArtifactAccountClosureParticipant implements AccountClosureParticipant {
    private final JdbcTemplate jdbc;
    private final AssetService assets;
    public ArtifactAccountClosureParticipant(JdbcTemplate jdbc,AssetService assets) {this.jdbc=jdbc;this.assets=assets;}
    @Override public void closeWorkspace(UUID owner,UUID workspace,boolean active) {
        assets.revokeForAccountClosure(owner,workspace);
        jdbc.update("UPDATE export_job SET status='CANCELLED',failure_code='ACCOUNT_CLOSED',lease_token=NULL,lease_until=NULL WHERE artifact_id IN (SELECT id FROM learning_artifact WHERE workspace_id=?) AND status IN ('QUEUED','RUNNING')",workspace.toString());
        String ownedJobs="SELECT e.id FROM export_job e JOIN learning_artifact a ON a.id=e.artifact_id WHERE a.workspace_id=?";
        jdbc.update("DELETE FROM artifact_export_binary WHERE export_id IN ("+ownedJobs+")",workspace.toString());
        jdbc.update("DELETE FROM artifact_export_content WHERE export_id IN ("+ownedJobs+")",workspace.toString());
    }
}
