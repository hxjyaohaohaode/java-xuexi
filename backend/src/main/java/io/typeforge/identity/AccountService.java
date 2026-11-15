package io.typeforge.identity;

import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Account profile and security facts are independent of browser state. */
@Service
public class AccountService {
    public record Profile(UUID userId,String username,String displayName,List<String> roles,int revision,Instant createdAt) {}
    public record SessionView(UUID id,String deviceLabel,Instant createdAt,Instant lastSeenAt,Instant expiresAt,boolean current) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final Clock clock;
    private final IdentityService identities;
    private final PasswordEncoder passwords;
    public AccountService(JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json,Clock clock,IdentityService identities,PasswordEncoder passwords) {
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.clock=clock;this.identities=identities;this.passwords=passwords;
    }
    public Profile profile(UUID owner) {
        var rows=jdbc.query("SELECT username,display_name,profile_revision,created_at FROM app_user WHERE id=? AND status='ACTIVE'",(rs,n)->{
            String username=rs.getString("username");String display=rs.getString("display_name");
            return new Profile(owner,username,display.isBlank()?username:display,identities.session(username).roles(),rs.getInt("profile_revision"),rs.getTimestamp("created_at").toInstant());
        },owner.toString());
        if(rows.isEmpty()) throw DomainException.missing();return rows.get(0);
    }
    public Profile update(UUID owner,String displayName,int revision) {
        if(displayName==null || displayName.isBlank() || displayName.length()>80 || displayName.codePoints().anyMatch(c->Character.isISOControl(c) || c==0x202a || c==0x202b || c==0x202d || c==0x202e)) throw DomainException.invalid("Display name must contain 1..80 visible characters");
        if(revision<1 || revision==Integer.MAX_VALUE) throw DomainException.invalid("A bounded profile revision is required");
        return tx.execute(status->{
            lockActive(owner);
            if(jdbc.update("UPDATE app_user SET display_name=?,profile_revision=profile_revision+1 WHERE id=? AND profile_revision=?",displayName.strip(),owner.toString(),revision)!=1) throw new DomainException("PRECONDITION_FAILED",412,"Profile changed; keep your draft and merge with the current profile");
            audit(owner,"PROFILE_UPDATED",Map.of("revision",revision+1));return profile(owner);
        });
    }
    public void changePassword(UUID owner,String current,String replacement) {
        password(replacement);
        if(replacement.equals(current)) throw DomainException.invalid("Choose a different new password");
        String verified=verifyPassword(owner,current);String encoded=passwords.encode(replacement);
        tx.executeWithoutResult(status->{
            lockActive(owner);requireUnchangedPassword(owner,verified);
            jdbc.update("UPDATE app_user SET password_hash=?,auth_epoch=auth_epoch+1 WHERE id=?",encoded,owner.toString());
            revokeAll(owner);audit(owner,"PASSWORD_CHANGED",Map.of("sessionsRevoked",true));
        });
    }
    public String verifyPassword(UUID owner,String input) {
        if(input==null || input.isBlank() || input.getBytes(StandardCharsets.UTF_8).length>72) throw new DomainException("CURRENT_PASSWORD_INVALID",400,"Current password is incorrect");
        var hashes=jdbc.queryForList("SELECT password_hash FROM app_user WHERE id=? AND status='ACTIVE'",String.class,owner.toString());
        if(hashes.size()!=1 || !passwords.matches(input,hashes.get(0))) throw new DomainException("CURRENT_PASSWORD_INVALID",400,"Current password is incorrect");
        return hashes.get(0);
    }
    public void requireUnchangedPassword(UUID owner,String verified) {
        String current=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=? AND status='ACTIVE'",String.class,owner.toString());
        if(!verified.equals(current)) throw new DomainException("ACCOUNT_SECURITY_CHANGED",409,"Account security changed; sign in again before retrying");
    }
    public void lockActive(UUID owner) {
        if(jdbc.queryForList("SELECT id FROM app_user WHERE id=? AND status='ACTIVE' FOR UPDATE",owner.toString()).isEmpty()) throw new DomainException("UNAUTHENTICATED",401,"Sign in again");
    }
    public void openSession(AccountPrincipal principal,String sessionId,String userAgent) {
        tx.executeWithoutResult(status->{
            lockActive(principal.userId());
            Long epoch=jdbc.queryForObject("SELECT auth_epoch FROM app_user WHERE id=?",Long.class,principal.userId().toString());
            if(epoch==null || epoch!=principal.authEpoch()) throw new DomainException("UNAUTHENTICATED",401,"Account security changed; sign in again");
            Instant now=clock.instant();
            // A browser that signs in again replaces its former record, even if the servlet rotated its ID.
            jdbc.update("INSERT INTO account_session(id,user_id,session_hash,auth_epoch,device_label,created_at,last_seen_at,expires_at) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),principal.userId().toString(),json.sha256(sessionId),epoch,deviceLabel(userAgent),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now.plusSeconds(43200)));
            var older=jdbc.queryForList("SELECT id FROM account_session WHERE user_id=? AND revoked_at IS NULL AND expires_at>? ORDER BY created_at DESC,id DESC LIMIT 100",String.class,principal.userId().toString(),Timestamp.from(now));
            for(String id:older.stream().skip(10).toList()) jdbc.update("UPDATE account_session SET revoked_at=? WHERE id=?",Timestamp.from(now),id);
            audit(principal.userId(),"SESSION_STARTED",Map.of("deviceLabel",deviceLabel(userAgent)));
        });
    }
    public boolean validSession(AccountPrincipal principal,String sessionId) {
        if(sessionId==null) return false;
        String hash=json.sha256(sessionId);Instant now=clock.instant();
        Integer found=jdbc.queryForObject("SELECT COUNT(*) FROM account_session s JOIN app_user u ON u.id=s.user_id WHERE s.session_hash=? AND s.user_id=? AND s.auth_epoch=? AND u.auth_epoch=s.auth_epoch AND u.status='ACTIVE' AND s.revoked_at IS NULL AND s.expires_at>? AND s.last_seen_at>?",Integer.class,hash,principal.userId().toString(),principal.authEpoch(),Timestamp.from(now),Timestamp.from(now.minusSeconds(1860)));
        if(found==null || found!=1) return false;
        jdbc.update("UPDATE account_session SET last_seen_at=? WHERE session_hash=? AND last_seen_at<? AND revoked_at IS NULL",Timestamp.from(now),hash,Timestamp.from(now.minusSeconds(60)));
        return true;
    }
    public List<SessionView> sessions(UUID owner,String currentId) {
        String hash=currentId==null?"":json.sha256(currentId);
        return jdbc.query("SELECT s.* FROM account_session s JOIN app_user u ON u.id=s.user_id WHERE s.user_id=? AND u.status='ACTIVE' AND s.auth_epoch=u.auth_epoch AND s.revoked_at IS NULL AND s.expires_at>? AND s.last_seen_at>? ORDER BY s.created_at DESC,s.id DESC LIMIT 10",(rs,n)->new SessionView(UUID.fromString(rs.getString("id")),rs.getString("device_label"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("last_seen_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),hash.equals(rs.getString("session_hash"))),owner.toString(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant().minusSeconds(1860)));
    }
    public boolean revoke(UUID owner,UUID id,String currentId) {
        return Boolean.TRUE.equals(tx.execute(status->{
            lockActive(owner);var rows=jdbc.queryForList("SELECT session_hash FROM account_session WHERE id=? AND user_id=?",String.class,id.toString(),owner.toString());
            if(rows.isEmpty()) throw DomainException.missing();
            jdbc.update("UPDATE account_session SET revoked_at=? WHERE id=? AND user_id=? AND revoked_at IS NULL",Timestamp.from(clock.instant()),id.toString(),owner.toString());
            audit(owner,"SESSION_REVOKED",Map.of("sessionId",id));return currentId!=null && rows.get(0).equals(json.sha256(currentId));
        }));
    }
    public void revokeOthers(UUID owner,String currentId) {
        if(currentId==null) throw new DomainException("UNAUTHENTICATED",401,"Current session is required");
        tx.executeWithoutResult(status->{
            lockActive(owner);int count=jdbc.update("UPDATE account_session SET revoked_at=? WHERE user_id=? AND session_hash<>? AND revoked_at IS NULL",Timestamp.from(clock.instant()),owner.toString(),json.sha256(currentId));
            audit(owner,"OTHER_SESSIONS_REVOKED",Map.of("count",count));
        });
    }
    public void revokeSessionId(String sessionId) { if(sessionId!=null) jdbc.update("UPDATE account_session SET revoked_at=? WHERE session_hash=? AND revoked_at IS NULL",Timestamp.from(clock.instant()),json.sha256(sessionId)); }
    public void revokeAll(UUID owner) { jdbc.update("UPDATE account_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",Timestamp.from(clock.instant()),owner.toString()); }
    public void audit(UUID owner,String action,Map<String,?> safe) {
        jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_id,trace_id,safe_metadata,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),owner.toString(),action,owner.toString(),UUID.randomUUID().toString(),json.write(safe),Timestamp.from(clock.instant()));
    }
    private static void password(String value) {
        if(value==null || value.codePointCount(0,value.length())<12 || value.getBytes(StandardCharsets.UTF_8).length>72) throw DomainException.invalid("Password must have at least 12 characters and at most 72 UTF-8 bytes");
    }
    private static String deviceLabel(String value) {
        String ua=value==null?"":value.toLowerCase(Locale.ROOT);
        String browser=ua.contains("edg/")?"Microsoft Edge":ua.contains("firefox/")?"Firefox":ua.contains("chrome/")?"Chrome":ua.contains("safari/")?"Safari":"浏览器";
        String platform=ua.contains("android")?"Android":ua.contains("iphone")?"iPhone":ua.contains("ipad")?"iPad":ua.contains("windows")?"Windows":ua.contains("macintosh")?"macOS":ua.contains("linux")?"Linux":"未知设备";
        return browser+" · "+platform;
    }
}
