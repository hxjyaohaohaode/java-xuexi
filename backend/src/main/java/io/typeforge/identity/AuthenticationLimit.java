package io.typeforge.identity;

import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Database atomic counters avoid per-process bypass; addresses come only from the trusted servlet peer. */
@Service
public class AuthenticationLimit {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final Clock clock;
    public AuthenticationLimit(JdbcTemplate jdbc,JsonCodec json,Clock clock) { this.jdbc=jdbc;this.json=json;this.clock=clock; }
    public void login(String peer,String username) {
        consume("login-peer",peer,300,100);
        consume("login-identity",peer+":"+(username==null?"":username.toLowerCase(Locale.ROOT)),300,12);
    }
    public void register(String peer) { consume("register-peer",peer,3600,20); }
    public void sensitive(UUID owner) { consume("account-sensitive",owner.toString(),300,8); }
    public void consume(String policy,String identity,int seconds,int maximum) {
        long now=clock.instant().getEpochSecond();long window=now-Math.floorMod(now,seconds);
        String hash=json.sha256(policy+"\n"+identity);
        if(jdbc.update("UPDATE authentication_limit SET hit_count=hit_count+1 WHERE bucket_hash=? AND window_start=? AND hit_count<?",hash,window,maximum)==1) return;
        try {
            jdbc.update("INSERT INTO authentication_limit(bucket_hash,window_start,hit_count,expires_at) VALUES(?,?,1,?)",hash,window,Timestamp.from(clock.instant().plusSeconds(seconds*2L)));
            return;
        } catch(DuplicateKeyException raced) {
            if(jdbc.update("UPDATE authentication_limit SET hit_count=hit_count+1 WHERE bucket_hash=? AND window_start=? AND hit_count<?",hash,window,maximum)==1) return;
        }
        throw new DomainException(seconds==3600?"REGISTRATION_RATE_LIMITED":"AUTH_RATE_LIMITED",429,seconds==3600?"Too many registration attempts; retry after the current one-hour window":"Too many authentication attempts; retry after the current five-minute window");
    }
    @Scheduled(fixedDelay=600000,initialDelay=600000)
    public void cleanup() { jdbc.update("DELETE FROM authentication_limit WHERE expires_at<?",Timestamp.from(clock.instant())); }
}
