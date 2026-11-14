package io.typeforge.identity;

import io.typeforge.shared.JsonCodec;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operator configuration only. Never creates users or credentials, never grants via HTTP. */
@Component
public class OperatorRoleBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final String editors;
    private final String admins;
    public OperatorRoleBootstrap(JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json,
        @Value("${typeforge.bootstrap.editor-users:}") String editors,@Value("${typeforge.bootstrap.admin-users:}") String admins) {
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.editors=editors;this.admins=admins;
    }
    @Override public void run(ApplicationArguments arguments) {
        if(editors.isBlank()&&admins.isBlank()) return;
        tx.executeWithoutResult(status->{
            // Match editorial transactions before acquiring any actor/author user row.
            // Another serving instance can be reviewing while this instance starts.
            jdbc.queryForObject("SELECT singleton_id FROM content_workbench_lock WHERE singleton_id=1 FOR UPDATE",Integer.class);
            grant(editors,"EDITOR");grant(admins,"ADMIN");
        });
    }
    private void grant(String configured,String role) {
        if(configured.isBlank()) return;
        for(String entry:configured.split(",",-1)) {
            String username=entry.strip();
            if(!username.matches("[a-z0-9][a-z0-9_.-]{2,63}")) throw new IllegalStateException("Invalid operator bootstrap account configuration");
            var rows=jdbc.queryForList("SELECT id,roles FROM app_user WHERE username=? AND status='ACTIVE' FOR UPDATE",username);
            if(rows.size()!=1) throw new IllegalStateException("Operator role bootstrap requires already registered ACTIVE accounts; no account was created");
            var account=rows.get(0);var roles=new TreeSet<String>();
            json.tree(account.get("roles")).forEach(n->roles.add(n.asText()));
            if(!java.util.Set.of("LEARNER","EDITOR","ADMIN").containsAll(roles)) throw new IllegalStateException("Account has invalid role data");
            if(roles.add(role)) {
                jdbc.update("UPDATE app_user SET roles=? WHERE id=?",json.write(roles),account.get("id"));
                jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_id,trace_id,safe_metadata) VALUES(?,NULL,'OPERATOR_ROLE_GRANTED',?,?,?)",UUID.randomUUID().toString(),account.get("id"),UUID.randomUUID().toString(),json.write(Map.of("role",role,"method","explicit-startup-configuration")));
            }
        }
    }
}
