package io.typeforge.identity;

import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdentityService implements UserDetailsService {
    public record Session(UUID userId,String username,List<String> roles) {}
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    public IdentityService(JdbcTemplate jdbc,PasswordEncoder passwords,TransactionTemplate tx,JsonCodec json) {
        this.jdbc=jdbc;this.passwords=passwords;this.tx=tx;this.json=json;
    }
    public Session register(String username,String password) {
        String canonical=normalize(username);
        if (password==null || password.codePointCount(0,password.length())<12 || password.getBytes(StandardCharsets.UTF_8).length>72) throw DomainException.invalid("Password must have at least 12 characters and at most 72 UTF-8 bytes");
        String encoded=passwords.encode(password);
        try {
            return tx.execute(status -> {
                UUID id=UUID.randomUUID();
                jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,?)",id.toString(),canonical,encoded,"[\"LEARNER\"]");
                jdbc.update("INSERT INTO user_preference(user_id) VALUES(?)",id.toString());
                return new Session(id,canonical,List.of("LEARNER"));
            });
        } catch (DuplicateKeyException e) { throw DomainException.conflict("Username is unavailable"); }
    }
    public Session session(String username) {
        String canonical=normalize(username);
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        AccountPrincipal bound=authentication!=null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof AccountPrincipal principal && principal.getUsername().equals(canonical)?principal:null;
        // Controllers pass the authenticated login name. Bind it to the original immutable identity as well:
        // a request paused after the session filter must never adopt a newly registered namesake after closure.
        String condition=bound==null?"":" AND id=? AND auth_epoch=?";
        Object[] arguments=bound==null?new Object[]{canonical}:new Object[]{canonical,bound.userId().toString(),bound.authEpoch()};
        var rows=jdbc.query("SELECT id,username,roles FROM app_user WHERE username=? AND status='ACTIVE'"+condition,(rs,n)->new Session(UUID.fromString(rs.getString("id")),rs.getString("username"),roles(rs.getString("roles"))),arguments);
        if (rows.isEmpty()) throw new DomainException("UNAUTHENTICATED",401,"Sign in again");
        return rows.get(0);
    }
    @Override public UserDetails loadUserByUsername(String username) {
        String canonical;
        try { canonical=normalize(username); }
        catch (DomainException e) { throw new UsernameNotFoundException("Invalid credentials"); }
        var rows=jdbc.query("SELECT id,username,password_hash,status,roles,auth_epoch FROM app_user WHERE username=?",(rs,n)->new AccountPrincipal(UUID.fromString(rs.getString("id")),rs.getString("username"),rs.getString("password_hash"),rs.getString("status").equals("ACTIVE"),rs.getLong("auth_epoch"),roles(rs.getString("roles")).stream().map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList()),canonical);
        if (rows.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
        return rows.get(0);
    }
    private List<String> roles(String value) {
        var node=json.tree(value);
        if(!node.isArray()) throw new DomainException("UNAUTHENTICATED",401,"Account roles require administrative repair");
        var result=new java.util.TreeSet<String>();
        for(var role:node) {
            if(!role.isTextual() || !Set.of("LEARNER","EDITOR","ADMIN").contains(role.asText())) throw new DomainException("UNAUTHENTICATED",401,"Account roles require administrative repair");
            result.add(role.asText());
        }
        if(result.isEmpty()) throw new DomainException("UNAUTHENTICATED",401,"Account has no authorized role");
        return List.copyOf(result);
    }
    private static String normalize(String username) {
        if (username==null) throw DomainException.invalid("Username is required");
        String canonical=username.toLowerCase(Locale.ROOT);
        if (!canonical.matches("[a-z0-9][a-z0-9_.-]{2,63}")) throw DomainException.invalid("Username must use 3..64 lowercase letters, digits, dot, dash or underscore");
        return canonical;
    }
}
