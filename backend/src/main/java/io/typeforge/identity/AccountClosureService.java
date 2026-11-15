package io.typeforge.identity;

import io.typeforge.shared.DomainException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Closing an account withdraws access and active work without rewriting published evidence or other learners. */
@Service
public class AccountClosureService {
    public record Closure(String status,Instant closedAt,String dataRetention) {}
    private final AccountService accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final PasswordEncoder passwords;
    private final List<AccountClosureParticipant> participants;
    public AccountClosureService(AccountService accounts,JdbcTemplate jdbc,TransactionTemplate tx,Clock clock,PasswordEncoder passwords,List<AccountClosureParticipant> participants) {
        this.accounts=accounts;this.jdbc=jdbc;this.tx=tx;this.clock=clock;this.passwords=passwords;this.participants=List.copyOf(participants);
    }
    public Closure close(UUID owner,String password,String confirmationUsername) {
        String verified=accounts.verifyPassword(owner,password);
        String unreachable=passwords.encode(UUID.randomUUID().toString()+UUID.randomUUID());
        return tx.execute(status->{
            accounts.lockActive(owner);accounts.requireUnchangedPassword(owner,verified);
            String current=jdbc.queryForObject("SELECT username FROM app_user WHERE id=?",String.class,owner.toString());
            if(!current.equals(confirmationUsername)) throw DomainException.invalid("Confirm the exact current username before closing this account");
            var roles=accounts.profile(owner).roles();
            boolean reservedName=roles.contains("EDITOR") || roles.contains("ADMIN") || jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE resource_id=? AND action='OPERATOR_ROLE_GRANTED'",Long.class,owner.toString())>0;
            // The owner lock precedes every workspace lock and stops all new private job admission.
            var spaces=jdbc.queryForList("SELECT id,status FROM learning_workspace WHERE owner_id=? ORDER BY id FOR UPDATE",owner.toString());
            for(var space:spaces) {
                String id=space.get("id").toString();
                for(var participant:participants) participant.closeWorkspace(owner,UUID.fromString(id),"ACTIVE".equals(space.get("status")));
                jdbc.update("UPDATE learner_memory SET status='REVOKED',revision=revision+1 WHERE workspace_id=? AND status='ACTIVE'",id);
                jdbc.update("UPDATE learning_workspace SET status='ARCHIVED',revision=revision+1 WHERE id=? AND status='ACTIVE'",id);
            }
            for(var participant:participants) participant.closeAccount(owner);
            Instant now=clock.instant();
            accounts.revokeAll(owner);
            // Privileged login names remain reserved: startup role configuration must never grant a later namesake.
            // The tombstone prefix cannot be registered through the public username grammar.
            jdbc.update("UPDATE app_user SET username=?,display_name='',password_hash=?,status='DISABLED',auth_epoch=auth_epoch+1,closed_at=? WHERE id=?",reservedName?current:"~closed-"+owner,unreachable,Timestamp.from(now),owner.toString());
            accounts.audit(owner,"ACCOUNT_CLOSED",Map.of("privateAccessRevoked",true,"historicalEvidenceRetained",true));
            return new Closure("CLOSED",now,"账户已关闭，旧登录与会话已失效，上传的素材文件已撤回并移除。历史学习记录及发布审计保留在停用账户标识下，不属于物理擦除。"+(reservedName?"此账户曾具有编辑或管理权限，原用户名保持保留；可以使用其他用户名注册新账户。":"相同用户名可以重新注册，新账户不会继承旧数据。"));
        });
    }
}
