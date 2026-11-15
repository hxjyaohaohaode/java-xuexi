package io.typeforge.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, bounded compile-only queue. Trusted workers execute outside this application process. */
@Service
public final class CompileService implements CompilationGateway {
    public record Job(UUID id,UUID workspaceId,UUID releaseId,UUID attemptId,String status,String sourceHash,String jdkVersion,
                      List<Diagnostic> diagnostics,String output,boolean outputTruncated,String errorCode,Instant createdAt,Instant finishedAt) {}
    public record Ticket(UUID id,UUID ownerId,UUID workspaceId,UUID leaseToken,CompileProtocol.Request request,String fingerprint) {}
    private record Scope(UUID owner,UUID workspace,String status,UUID lease,Instant leaseUntil) {}
    private static final Set<String> PENDING=Set.of("QUEUED","RUNNING");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final EventJournal events;
    private final JsonCodec json;
    private final Clock clock;
    private final CompileSettings settings;
    private final HttpCompileProvider provider;
    public CompileService(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,EventJournal events,JsonCodec json,Clock clock,CompileSettings settings,HttpCompileProvider provider) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.events=events;this.json=json;this.clock=clock;this.settings=settings;this.provider=provider;
    }
    public Job create(UUID owner,UUID workspace,UUID release,String source,String mode,String key) {
        if(!"COMPILE_ONLY".equals(mode)) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"Only isolated compilation is supported; running learner bytecode is not enabled");
        return createForAttempt(owner,workspace,release,source,null,List.of(),key);
    }
    /** Internal domain entry point only; no HTTP request can supply attempt IDs or hidden checks. */
    public Job createForAttempt(UUID owner,UUID workspace,UUID release,String source,UUID attempt,List<CompileProtocol.Check> checks,String key) {
        WorkspaceService.requireKey(key);
        if(source==null || source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length>CompileSettings.MAX_SOURCE_BYTES || source.indexOf('\0')>=0 || !StandardCharsets.UTF_8.newEncoder().canEncode(source)) throw DomainException.invalid("Java source must contain 1..32768 valid UTF-8 bytes without NUL");
        if(checks==null || checks.size()>8 || attempt==null && !checks.isEmpty() || checks.stream().map(CompileProtocol.Check::id).distinct().count()!=checks.size()) throw DomainException.invalid("Invalid compiler check manifest");
        for(var check:checks) if(!check.id().matches("check[0-7]") || check.source()==null || check.source().isBlank() || check.source().getBytes(StandardCharsets.UTF_8).length>8192) throw DomainException.invalid("Invalid bounded compile check");
        return tx.execute(status->{
            var scope=workspaces.lockOwned(owner,workspace);
            if(!scope.releaseId().equals(release)) throw DomainException.invalid("Compilation must use its workspace release");
            String hash=json.hash(Map.of("workspaceId",workspace,"releaseId",release,"source",source,"attemptId",attempt==null?"":attempt,"checks",checks));
            UUID previous=workspaces.replay(owner,"submitCompile",key,hash);
            if(previous!=null) return get(owner,previous);
            provider.requireConfigured();
            if(attempt!=null && jdbc.queryForList("SELECT a.id FROM exercise_attempt a JOIN workspace_attempt wa ON wa.attempt_id=a.id WHERE a.id=? AND a.user_id=? AND a.release_id=? AND wa.workspace_id=? AND a.status='PENDING'",attempt.toString(),owner.toString(),release.toString(),workspace.toString()).isEmpty()) throw DomainException.missing();
            jdbc.queryForObject("SELECT singleton_id FROM compiler_admission WHERE singleton_id=1 FOR UPDATE",Integer.class);
            long pending=count("SELECT COUNT(*) FROM compile_job WHERE user_id=? AND status IN ('QUEUED','RUNNING')",owner.toString());
            long global=count("SELECT COUNT(*) FROM compile_job WHERE status IN ('QUEUED','RUNNING')");
            long today=count("SELECT COUNT(*) FROM compile_job WHERE user_id=? AND created_at>=?",owner.toString(),Timestamp.from(clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()));
            if(pending>=settings.maxPending() || global>=settings.globalPending() || today>=settings.dailyLimit()) throw new DomainException("COMPILE_QUEUE_LIMIT",429,"The compiler queue or daily limit is full; review existing jobs before retrying");
            UUID id=UUID.randomUUID();String manifest=json.write(checks);
            jdbc.update("INSERT INTO compile_job(id,user_id,release_id,attempt_id,mode,source_code,source_hash,status,compiler_flags,created_at) VALUES(?,?,?,?,'COMPILE_ONLY',?,?,'QUEUED',?,?)",id.toString(),owner.toString(),release.toString(),attempt==null?null:attempt.toString(),source,json.sha256(source),json.write(List.of("--release","17","-proc:none","-Xlint:all","-Werror")),Timestamp.from(clock.instant()));
            jdbc.update("INSERT INTO workspace_compile(job_id,workspace_id,request_json,request_hash,worker_fingerprint) VALUES(?,?,?,?,?)",id.toString(),workspace.toString(),manifest,json.hash(checks),provider.fingerprint());
            events.append(workspace,"COMPILE_QUEUED","SERVER",Map.of("jobId",id,"attemptId",attempt==null?"":attempt,"mode","COMPILE_ONLY","sourceHash",json.sha256(source)));
            workspaces.remember(owner,"submitCompile",key,hash,id);return get(owner,id);
        });
    }
    public Job get(UUID owner,UUID id) {
        var rows=jdbc.query("SELECT j.*,w.workspace_id,w.error_code FROM compile_job j JOIN workspace_compile w ON w.job_id=j.id WHERE j.id=? AND j.user_id=?",this::job,id.toString(),owner.toString());
        if(rows.isEmpty()) throw DomainException.missing();workspaces.requireOwned(owner,rows.get(0).workspaceId());return rows.get(0);
    }
    public List<Job> list(UUID owner,UUID workspace) {
        workspaces.requireOwned(owner,workspace);
        return jdbc.query("SELECT j.*,w.workspace_id,w.error_code FROM compile_job j JOIN workspace_compile w ON w.job_id=j.id WHERE j.user_id=? AND w.workspace_id=? ORDER BY j.created_at DESC,j.id LIMIT 100",this::job,owner.toString(),workspace.toString());
    }
    public Job cancel(UUID owner,UUID id,String key) {
        WorkspaceService.requireKey(key);
        return tx.execute(status->{
            var job=get(owner,id);workspaces.lockOwned(owner,job.workspaceId());
            Scope scope=lock(id);
            if(PENDING.contains(scope.status())) finish(id,scope,"CANCELLED","COMPILE_CANCELLED",null);
            return get(owner,id);
        });
    }
    public Ticket claimNext() {
        recoverExpired();
        if(!settings.configured()) return null;
        var ids=jdbc.queryForList("SELECT id FROM compile_job WHERE status='QUEUED' ORDER BY created_at,id LIMIT 16",String.class);
        for(String candidate:ids) {
            Ticket ticket=tx.execute(status->{
                UUID id=UUID.fromString(candidate);Scope scope=lock(id);
                if(scope==null || !scope.status().equals("QUEUED")) return null;
                if(!scopeActive(scope)) {finish(id,scope,"CANCELLED","COMPILE_SCOPE_INACTIVE",null);return null;}
                var stored=jdbc.queryForMap("SELECT j.source_code,j.source_hash,w.request_json,w.request_hash,w.worker_fingerprint FROM compile_job j JOIN workspace_compile w ON w.job_id=j.id WHERE j.id=?",id.toString());
                var checks=List.of(json.read(json.tree(stored.get("request_json")).toString(),CompileProtocol.Check[].class));
                if(!json.hash(checks).equals(stored.get("request_hash")) || !json.sha256(stored.get("source_code").toString()).equals(stored.get("source_hash"))) {finish(id,scope,"INFRA_ERROR","COMPILE_INPUT_INTEGRITY",null);return null;}
                if(!provider.fingerprint().equals(stored.get("worker_fingerprint"))) {finish(id,scope,"INFRA_ERROR","COMPILER_CONFIGURATION_CHANGED",null);return null;}
                UUID lease=UUID.randomUUID();
                jdbc.update("UPDATE compile_job SET status='RUNNING',lease_token=?,lease_until=?,attempt_no=attempt_no+1 WHERE id=? AND status='QUEUED'",lease.toString(),Timestamp.from(clock.instant().plusMillis(settings.timeoutMillis()+10000L)),id.toString());
                if(jdbc.queryForObject("SELECT attempt_id FROM compile_job WHERE id=?",String.class,id.toString())!=null) jdbc.update("UPDATE exercise_attempt SET status='GRADING' WHERE id=(SELECT attempt_id FROM compile_job WHERE id=?) AND status='PENDING'",id.toString());
                events.append(scope.workspace(),"COMPILE_RUNNING","SERVER",Map.of("jobId",id));
                return new Ticket(id,scope.owner(),scope.workspace(),lease,new CompileProtocol.Request(id,lease,stored.get("source_code").toString(),stored.get("source_hash").toString(),checks),provider.fingerprint());
            });
            if(ticket!=null) return ticket;
        }
        return null;
    }
    public boolean active(Ticket ticket) {
        return !jdbc.queryForList("SELECT j.id FROM compile_job j JOIN workspace_compile wc ON wc.job_id=j.id JOIN learning_workspace w ON w.id=wc.workspace_id JOIN app_user u ON u.id=j.user_id WHERE j.id=? AND j.status='RUNNING' AND j.lease_token=? AND j.lease_until>? AND w.status='ACTIVE' AND u.status='ACTIVE'",ticket.id().toString(),ticket.leaseToken().toString(),Timestamp.from(clock.instant())).isEmpty();
    }
    public boolean accept(Ticket ticket,CompileProtocol.Result result) {
        return Boolean.TRUE.equals(tx.execute(status->{
            Scope scope=lock(ticket.id());if(!valid(scope,ticket)) return false;
            if(!scopeActive(scope)) {finish(ticket.id(),scope,"CANCELLED","COMPILE_SCOPE_INACTIVE",null);return false;}
            if(!ticket.fingerprint().equals(provider.fingerprint()) || !result.sourceHash().equals(ticket.request().sourceHash())) {finish(ticket.id(),scope,"INFRA_ERROR","COMPILER_CONFIGURATION_CHANGED",null);return false;}
            finish(ticket.id(),scope,result.status(),Set.of("SUCCEEDED","COMPILE_ERROR").contains(result.status())?null:"COMPILER_"+result.status(),result);return true;
        }));
    }
    public void fail(Ticket ticket,String code) {
        tx.executeWithoutResult(status->{Scope scope=lock(ticket.id());if(valid(scope,ticket)) finish(ticket.id(),scope,code.equals("COMPILER_TIMEOUT")?"TIMED_OUT":code.equals("COMPILE_CANCELLED")?"CANCELLED":"INFRA_ERROR",code,null);});
    }
    public void recoverExpired() {
        var ids=jdbc.queryForList("SELECT id FROM compile_job WHERE (status='RUNNING' AND lease_until<=?) OR (status='QUEUED' AND created_at<?) ORDER BY created_at,id LIMIT 32",String.class,Timestamp.from(clock.instant()),Timestamp.from(clock.instant().minusSeconds(600)));
        for(String candidate:ids) tx.executeWithoutResult(status->{
            UUID id=UUID.fromString(candidate);Scope scope=lock(id);
            if(scope!=null && scope.status().equals("RUNNING") && !scope.leaseUntil().isAfter(clock.instant())) finish(id,scope,"INFRA_ERROR","COMPILER_LEASE_EXPIRED",null);
            else if(scope!=null && scope.status().equals("QUEUED") && count("SELECT COUNT(*) FROM compile_job WHERE id=? AND created_at<?",candidate,Timestamp.from(clock.instant().minusSeconds(600)))>0) finish(id,scope,"TIMED_OUT","COMPILE_QUEUE_TIMEOUT",null);
        });
    }
    private Scope lock(UUID id) {
        var owners=jdbc.queryForList("SELECT j.user_id,w.workspace_id FROM compile_job j JOIN workspace_compile w ON w.job_id=j.id WHERE j.id=?",id.toString());
        if(owners.isEmpty()) return null;
        UUID owner=UUID.fromString(owners.get(0).get("user_id").toString()),workspace=UUID.fromString(owners.get(0).get("workspace_id").toString());
        jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE",owner.toString());
        jdbc.queryForList("SELECT id FROM learning_workspace WHERE id=? FOR UPDATE",workspace.toString());
        return jdbc.queryForObject("SELECT status,lease_token,lease_until FROM compile_job WHERE id=? FOR UPDATE",(rs,n)->new Scope(owner,workspace,rs.getString(1),uuid(rs.getString(2)),instant(rs.getTimestamp(3))),id.toString());
    }
    private boolean scopeActive(Scope scope) {return count("SELECT COUNT(*) FROM learning_workspace w JOIN app_user u ON u.id=w.owner_id WHERE w.id=? AND u.id=? AND w.status='ACTIVE' AND u.status='ACTIVE'",scope.workspace().toString(),scope.owner().toString())==1;}
    private boolean valid(Scope scope,Ticket ticket){return scope!=null && scope.status().equals("RUNNING") && ticket.leaseToken().equals(scope.lease()) && scope.leaseUntil()!=null && scope.leaseUntil().isAfter(clock.instant());}
    private void finish(UUID id,Scope scope,String resultStatus,String code,CompileProtocol.Result result) {
        if(!PENDING.contains(scope.status())) return;
        jdbc.update("UPDATE compile_job SET status=?,result=?,jdk_version=?,finished_at=?,lease_token=NULL,lease_until=NULL WHERE id=?",resultStatus,result==null?null:json.write(result),result==null?null:result.jdkVersion(),Timestamp.from(clock.instant()),id.toString());
        jdbc.update("UPDATE workspace_compile SET error_code=? WHERE job_id=?",code,id.toString());
        events.append(scope.workspace(),"COMPILE_FINISHED","SERVER",Map.of("jobId",id,"status",resultStatus));
    }
    private Job job(ResultSet rs,int row) throws SQLException {
        JsonNode value=rs.getString("result")==null?json.tree("{}"):json.tree(rs.getString("result"));
        List<Diagnostic> diagnostics=value.has("diagnostics")?List.of(json.read(value.path("diagnostics").toString(),Diagnostic[].class)):List.of();
        // Hidden checks and their diagnostics are never returned. Only the learner's standalone source is diagnosable.
        return new Job(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("workspace_id")),UUID.fromString(rs.getString("release_id")),uuid(rs.getString("attempt_id")),rs.getString("status"),rs.getString("source_hash"),rs.getString("jdk_version"),diagnostics,"",value.path("outputTruncated").asBoolean(),rs.getString("error_code"),rs.getTimestamp("created_at").toInstant(),instant(rs.getTimestamp("finished_at")));
    }
    private long count(String sql,Object...args){Long count=jdbc.queryForObject(sql,Long.class,args);return count==null?0:count;}
    private static UUID uuid(String value){return value==null?null:UUID.fromString(value);}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    @Override public UUID submit(Request request){return create(request.requesterId(),request.workspaceId(),request.releaseId(),request.source(),request.mode().name(),request.idempotencyKey()).id();}
    @Override public Result findOwnJob(UUID requesterId,UUID jobId){var job=get(requesterId,jobId);return new Result(job.id(),Status.valueOf(job.status()),job.sourceHash(),job.jdkVersion(),job.diagnostics());}
    @Override public void cancelOwnJob(UUID requesterId,UUID jobId){cancel(requesterId,jobId,UUID.randomUUID().toString());}
}
