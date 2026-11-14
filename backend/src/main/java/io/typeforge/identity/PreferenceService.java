package io.typeforge.identity;

import io.typeforge.shared.DomainException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PreferenceService {
    public record Preference(String theme,boolean reducedMotion,int codeFontSize,int revision) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public PreferenceService(JdbcTemplate jdbc,TransactionTemplate tx) {this.jdbc=jdbc;this.tx=tx;}
    public Preference get(UUID owner) {
        var rows=jdbc.query("SELECT p.* FROM user_preference p JOIN app_user u ON u.id=p.user_id WHERE p.user_id=? AND u.status='ACTIVE'",(rs,n)->new Preference(rs.getString("theme"),rs.getBoolean("reduced_motion"),rs.getInt("code_font_size"),rs.getInt("revision")),owner.toString());
        if(rows.isEmpty()) throw DomainException.missing();
        return rows.get(0);
    }
    public Preference update(UUID owner,Preference input,int expected) {
        if(input==null || !Set.of("system","light","dark").contains(input.theme()) || input.codeFontSize()<14 || input.codeFontSize()>24 || expected<1 || expected==Integer.MAX_VALUE || input.revision()!=expected) throw DomainException.invalid("Invalid preference or mismatched revision");
        return tx.execute(status->{
            if(jdbc.queryForList("SELECT id FROM app_user WHERE id=? AND status='ACTIVE' FOR UPDATE",owner.toString()).isEmpty()) throw DomainException.missing();
            if(jdbc.update("UPDATE user_preference SET theme=?,reduced_motion=?,code_font_size=?,revision=revision+1 WHERE user_id=? AND revision=?",input.theme(),input.reducedMotion(),input.codeFontSize(),owner.toString(),expected)!=1) throw new DomainException("PRECONDITION_FAILED",412,"Preferences changed; refresh before merging");
            return get(owner);
        });
    }
}
