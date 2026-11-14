package io.typeforge.identity;

import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Checks current database roles as well as the HTTP session; revocation is effective immediately. */
@Component
public final class ContentAccess {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final Clock clock;
    public ContentAccess(JdbcTemplate jdbc, JsonCodec json, Clock clock) { this.jdbc=jdbc; this.json=json; this.clock=clock; }

    public List<String> requireEditor(UUID actor) { return editor(actor, false); }
    public List<String> lockEditor(UUID actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Editorial changes require a transaction");
        // One coarse lock deliberately serves a two-person editorial team. No network call holds it.
        jdbc.queryForObject("SELECT singleton_id FROM content_workbench_lock WHERE singleton_id=1 FOR UPDATE", Integer.class);
        return editor(actor, true);
    }
    private List<String> editor(UUID actor, boolean lock) {
        if (actor==null) throw new DomainException("UNAUTHENTICATED",401,"Sign in again");
        var rows=jdbc.queryForList("SELECT roles FROM app_user WHERE id=? AND status='ACTIVE'"+(lock?" FOR UPDATE":""),actor.toString());
        var roles=new ArrayList<String>();
        if (!rows.isEmpty()) {
            var node=json.tree(rows.get(0).get("roles"));
            if (node.isArray()) node.forEach(role->{ if(role.isTextual() && List.of("LEARNER","EDITOR","ADMIN").contains(role.asText())) roles.add(role.asText()); });
        }
        if (!roles.contains("EDITOR") && !roles.contains("ADMIN")) throw new DomainException("EDITOR_REQUIRED",403,"An active editorial role is required");
        return List.copyOf(roles);
    }
    public UUID replay(UUID actor,String operation,String key,Object input) {
        key(key);
        var rows=jdbc.queryForList("SELECT request_hash,resource_id FROM idempotency_record WHERE user_id=? AND operation=? AND idempotency_key=?",actor.toString(),operation,key);
        if (rows.isEmpty()) return null;
        if (!json.hash(input).equals(rows.get(0).get("request_hash"))) throw DomainException.conflict("Idempotency key was used for different editorial input");
        return UUID.fromString(rows.get(0).get("resource_id").toString());
    }
    public void remember(UUID actor,String operation,String key,Object input,UUID resource) {
        jdbc.update("INSERT INTO idempotency_record(user_id,operation,idempotency_key,request_hash,resource_id,expires_at) VALUES(?,?,?,?,?,?)",
            actor.toString(),operation,key,json.hash(input),resource.toString(),Timestamp.from(clock.instant().plusSeconds(86400)));
    }
    public void audit(UUID actor,String action,UUID resource,Map<String,?> safe) {
        jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_id,trace_id,safe_metadata,created_at) VALUES(?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),actor.toString(),action,resource.toString(),UUID.randomUUID().toString(),json.write(safe),Timestamp.from(clock.instant()));
    }
    public static void key(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_.:-]{16,64}")) throw DomainException.invalid("Idempotency-Key must contain 16..64 safe characters");
    }
    public static String text(String value,int maximum) {
        if(value==null || value.isBlank() || value.length()>maximum || value.chars().anyMatch(c->c<32 && c!='\n' && c!='\r' && c!='\t')) throw DomainException.invalid("Editorial text is missing, too long or contains control characters");
        return value;
    }
    public static void hash(String value) { if(value==null || !value.matches("[a-f0-9]{64}")) throw DomainException.invalid("A SHA256 content hash is required"); }
    public static void limit(int value) { if(value<1 || value>100) throw DomainException.invalid("Limit must be 1..100"); }
}
