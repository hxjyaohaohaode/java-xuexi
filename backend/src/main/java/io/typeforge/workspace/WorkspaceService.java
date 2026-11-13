package io.typeforge.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkspaceService implements WorkspaceAccess, WorkspaceContextStore {
    public record Workspace(UUID id,UUID releaseId,String title,String guidanceMode,int revision,long eventSequence,String status) {}
    public record Goal(UUID id,UUID workspaceId,int revision,String text,List<String> acceptance,String status) {}
    public record Context(UUID id,UUID workspaceId,int workspaceRevision,long eventSequence,List<Reference> references,String contentHash) {}
    public record Memory(UUID id,UUID workspaceId,int revision,String basis,String summary,List<Reference> supportRefs,String status) {}
    public record Projection(long sourceSequence,long processedSequence,long activityCount,boolean caughtUp,long deadLetters) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final EventJournal events;
    private final Clock clock;
    private final ReferenceInvalidator invalidation;
    public WorkspaceService(JdbcTemplate jdbc,TransactionTemplate tx,JsonCodec json,EventJournal events,Clock clock,ReferenceInvalidator invalidation) {
        this.jdbc=jdbc; this.tx=tx; this.json=json; this.events=events; this.clock=clock;this.invalidation=invalidation;
    }
    @Override public OwnedWorkspace requireOwned(UUID ownerId,UUID workspaceId) { return owned(ownerId,workspaceId,false); }
    @Override public OwnedWorkspace lockOwned(UUID ownerId,UUID workspaceId) {
        EventJournal.requireTransaction();
        // Parent-user foreign keys also acquire locks. Use user -> workspace everywhere,
        // including operations that only later insert an idempotency or projection row.
        if(jdbc.queryForList("SELECT id FROM app_user WHERE id=? AND status='ACTIVE' FOR UPDATE",ownerId.toString()).isEmpty()) throw DomainException.missing();
        return owned(ownerId,workspaceId,true);
    }
    private OwnedWorkspace owned(UUID owner,UUID id,boolean lock) {
        var rows=jdbc.query("SELECT w.id,w.owner_id,w.release_id,w.revision,w.event_sequence,w.status FROM learning_workspace w WHERE w.id=? AND w.owner_id=? AND EXISTS (SELECT 1 FROM app_user u WHERE u.id=w.owner_id AND u.status='ACTIVE')"+(lock?" FOR UPDATE":""),
            (rs,n) -> {
                if (lock && !"ACTIVE".equals(rs.getString("status"))) throw DomainException.conflict("Workspace is archived");
                return new OwnedWorkspace(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("owner_id")),UUID.fromString(rs.getString("release_id")),rs.getInt("revision"),rs.getLong("event_sequence"));
            },id.toString(),owner.toString());
        if (rows.isEmpty()) throw DomainException.missing();
        return rows.get(0);
    }
    public Workspace create(UUID owner,UUID release,String title,String mode,String key) {
        requireText(title,128); requireMode(mode); requireKey(key);
        return tx.execute(status -> {
            // Serialize only creation for this user; ownership rows always precede workspace locks.
            if (jdbc.queryForList("SELECT id FROM app_user WHERE id=? AND status='ACTIVE' FOR UPDATE",owner.toString()).isEmpty()) throw DomainException.missing();
            String requestHash=json.hash(Map.of("releaseId",release,"title",title,"guidanceMode",mode));
            UUID previous=replay(owner,"createWorkspace",key,requestHash);
            if (previous!=null) return get(owner,previous);
            if (jdbc.queryForList("SELECT id FROM content_release WHERE id=? AND status='PUBLISHED'",release.toString()).isEmpty()) throw DomainException.invalid("A published course release is required");
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO learning_workspace(id,owner_id,release_id,title,guidance_mode) VALUES(?,?,?,?,?)",id.toString(),owner.toString(),release.toString(),title,mode);
            events.append(id,"WORKSPACE_CREATED","SERVER",Map.of("releaseId",release));
            remember(owner,"createWorkspace",key,requestHash,id);
            return get(owner,id);
        });
    }
    public List<Workspace> list(UUID owner,int limit) {
        if (limit<1 || limit>100) throw DomainException.invalid("Limit must be 1..100");
        return jdbc.query("SELECT * FROM learning_workspace WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT ?",this::workspace,owner.toString(),limit);
    }
    public Workspace get(UUID owner,UUID id) {
        requireOwned(owner,id);
        return jdbc.queryForObject("SELECT * FROM learning_workspace WHERE id=?",this::workspace,id.toString());
    }
    private Workspace workspace(ResultSet rs,int row) throws SQLException {
        return new Workspace(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("release_id")),rs.getString("title"),rs.getString("guidance_mode"),rs.getInt("revision"),rs.getLong("event_sequence"),rs.getString("status"));
    }
    public Workspace update(UUID owner,UUID id,int expectedRevision,String title,String mode) {
        requireText(title,128); requireMode(mode);
        return tx.execute(status -> {
            var current=lockOwned(owner,id);
            if (current.revision()!=expectedRevision) throw DomainException.conflict("Workspace changed; refresh before editing");
            jdbc.update("UPDATE learning_workspace SET title=?,guidance_mode=? WHERE id=?",title,mode,id.toString());
            events.append(id,"WORKSPACE_UPDATED","CLIENT",Map.of("guidanceMode",mode));
            return get(owner,id);
        });
    }
    public Goal createGoal(UUID owner,UUID workspace,String text,List<String> acceptance,String key) {
        requireText(text,4000); requireKey(key);
        if (acceptance==null || acceptance.isEmpty() || acceptance.size()>20) throw DomainException.invalid("1..20 acceptance conditions required");
        acceptance.forEach(s -> requireText(s,500));
        return tx.execute(status -> {
            lockOwned(owner,workspace);
            String hash=json.hash(Map.of("workspaceId",workspace,"text",text,"acceptance",acceptance));
            UUID previous=replay(owner,"createLearningGoal",key,hash);
            if (previous!=null) return goal(owner,workspace,previous);
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO learning_goal(id,workspace_id,goal_text,acceptance) VALUES(?,?,?,?)",id.toString(),workspace.toString(),text,json.write(acceptance));
            events.append(workspace,"GOAL_CREATED","CLIENT",Map.of("goalId",id,"revision",1));
            remember(owner,"createLearningGoal",key,hash,id);
            return goal(owner,workspace,id);
        });
    }
    public Goal goal(UUID owner,UUID workspace,UUID id) {
        requireOwned(owner,workspace);
        var result=jdbc.query("SELECT * FROM learning_goal WHERE id=? AND workspace_id=?",(rs,n) -> new Goal(id,workspace,rs.getInt("revision"),rs.getString("goal_text"),List.of(json.read(rs.getString("acceptance"),String[].class)),rs.getString("status")),id.toString(),workspace.toString());
        if (result.isEmpty()) throw DomainException.missing();
        return result.get(0);
    }
    public EventJournal.Event recordClientEvent(UUID owner,UUID workspace,UUID eventId,String type,List<Reference> refs,int expectedRevision) {
        if (!Set.of("VIEW_CHANGED","SELECTION_COMMITTED","HELP_REQUESTED","DRAFT_CHANGED").contains(type)) throw DomainException.invalid("Clients cannot assert learning facts");
        if (refs==null || refs.size()>16) throw DomainException.invalid("Too many references");
        return tx.execute(status -> {
            lockOwned(owner,workspace);
            var existing=jdbc.queryForList("SELECT workspace_id,sequence_no,event_type,payload FROM workspace_event WHERE id=?",eventId.toString());
            Object request=Map.of("references",refs,"expectedRevision",expectedRevision);
            if (!existing.isEmpty()) {
                var prior=existing.get(0);
                if (!workspace.toString().equals(prior.get("workspace_id")) || !type.equals(prior.get("event_type")) || !json.hash(json.tree(prior.get("payload")).path("data")).equals(json.hash(request))) throw DomainException.conflict("Event id reused with different input");
                return events.read(workspace,((Number)prior.get("sequence_no")).longValue()-1,1).get(0);
            }
            var current=requireOwned(owner,workspace);
            if (current.revision()!=expectedRevision) throw DomainException.conflict("Workspace changed before event submission");
            validateReferences(owner,workspace,refs);
            events.append(eventId,workspace,type,"CLIENT",request);
            return events.read(workspace,current.eventSequence(),1).get(0);
        });
    }
    public List<EventJournal.Event> events(UUID owner,UUID workspace,long after,int limit) {
        var current=requireOwned(owner,workspace);
        if (after>current.eventSequence()) throw DomainException.invalid("Cursor is beyond this workspace stream");
        return events.read(workspace,after,limit);
    }
    public Context createContext(UUID owner,UUID workspace) {
        return tx.execute(status -> {
            var current=lockOwned(owner,workspace);
            var refs=new ArrayList<Reference>();
            jdbc.query("SELECT id,revision,goal_text,acceptance,status FROM learning_goal WHERE workspace_id=? AND status='ACTIVE' ORDER BY created_at,id LIMIT 20",rs -> {
                refs.add(new Reference("GOAL",UUID.fromString(rs.getString("id")),rs.getInt("revision"),json.hash(Map.of("text",rs.getString("goal_text"),"acceptance",json.tree(rs.getString("acceptance"))))));
            },workspace.toString());
            jdbc.query("SELECT id,revision,content_json FROM learner_memory WHERE workspace_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?) ORDER BY created_at,id LIMIT 20",rs -> {
                refs.add(new Reference("MEMORY",UUID.fromString(rs.getString("id")),rs.getInt("revision"),json.hash(json.tree(rs.getString("content_json")))));
            },workspace.toString(),Timestamp.from(clock.instant()));
            jdbc.query("SELECT a.id,r.revision,r.content_hash FROM learning_artifact a JOIN artifact_revision r ON r.artifact_id=a.id AND r.revision=a.current_revision WHERE a.workspace_id=? AND r.review_status<>'STALE' ORDER BY a.created_at DESC,a.id LIMIT 16",rs -> {
                refs.add(new Reference("ARTIFACT",UUID.fromString(rs.getString("id")),rs.getInt("revision"),rs.getString("content_hash")));
            },workspace.toString());
            jdbc.query("SELECT a.id,a.answer,a.feedback,a.score FROM exercise_attempt a JOIN workspace_attempt wa ON wa.attempt_id=a.id WHERE wa.workspace_id=? AND a.user_id=? AND a.status='GRADED' ORDER BY a.created_at DESC,a.id LIMIT 8",rs -> {
                String hash=json.hash(Map.of("answer",json.tree(rs.getString("answer")),"feedback",json.tree(rs.getString("feedback")),"score",rs.getBigDecimal("score")));
                refs.add(new Reference("ATTEMPT",UUID.fromString(rs.getString("id")),1,hash));
            },workspace.toString(),owner.toString());
            UUID id=UUID.randomUUID();
            String hash=json.hash(Map.of("workspaceId",workspace,"revision",current.revision(),"sequence",current.eventSequence(),"references",refs));
            jdbc.update("INSERT INTO context_snapshot(id,workspace_id,workspace_revision,event_sequence,references_json,content_hash) VALUES(?,?,?,?,?,?)",id.toString(),workspace.toString(),current.revision(),current.eventSequence(),json.write(refs),hash);
            return new Context(id,workspace,current.revision(),current.eventSequence(),List.copyOf(refs),hash);
        });
    }
    @Override public Snapshot snapshotOwn(UUID owner,UUID workspace) {
        Context context=createContext(owner,workspace);
        return new Snapshot(context.id(),workspace,context.workspaceRevision(),context.references());
    }
    /** Read an immutable saved snapshot; callers performing work hold the workspace transaction lock. */
    public Context readContext(UUID owner,UUID workspace,UUID snapshotId) {
        requireOwned(owner,workspace);
        var rows=jdbc.query("SELECT workspace_revision,event_sequence,references_json,content_hash FROM context_snapshot WHERE id=? AND workspace_id=?",
            (rs,n)->new Context(snapshotId,workspace,rs.getInt(1),rs.getLong(2),List.of(json.read(rs.getString(3),Reference[].class)),rs.getString(4)),snapshotId.toString(),workspace.toString());
        if (rows.isEmpty()) throw DomainException.missing();
        Context context=rows.get(0);
        validateReferences(owner,workspace,context.references());
        String expected=json.hash(Map.of("workspaceId",workspace,"revision",context.workspaceRevision(),"sequence",context.eventSequence(),"references",context.references()));
        if(!expected.equals(context.contentHash())) throw DomainException.conflict("Context snapshot integrity changed");
        return context;
    }
    @Override public void validateSnapshot(UUID owner,UUID workspace,UUID snapshotId) {
        readContext(owner,workspace,snapshotId);
    }
    public void validateReferences(UUID owner,UUID workspace,List<Reference> refs) {
        var scope=requireOwned(owner,workspace);
        if (refs==null || refs.size()>64) throw DomainException.invalid("At most 64 context references are allowed");
        var keys=new HashSet<String>();
        for (Reference ref:refs) {
            if (ref==null || ref.id()==null || ref.kind()==null || ref.revision()<1 || ref.contentHash()==null || !ref.contentHash().matches("[a-f0-9]{64}") || !keys.add(ref.kind()+":"+ref.id())) throw DomainException.invalid("Invalid or duplicate context reference");
            String hash;
            switch (ref.kind()) {
                case "GOAL" -> {
                    Goal goal=goal(owner,workspace,ref.id());
                    if (goal.revision()!=ref.revision() || !goal.status().equals("ACTIVE")) throw DomainException.conflict("Goal reference changed");
                    hash=json.hash(Map.of("text",goal.text(),"acceptance",goal.acceptance()));
                }
                case "MEMORY" -> {
                    var rows=jdbc.query("SELECT content_json FROM learner_memory WHERE id=? AND workspace_id=? AND revision=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)",(rs,n)->rs.getString(1),ref.id().toString(),workspace.toString(),ref.revision(),Timestamp.from(clock.instant()));
                    if (rows.isEmpty()) throw DomainException.conflict("Memory is revoked, expired or changed");
                    hash=json.hash(json.tree(rows.get(0)));
                }
                case "ARTIFACT" -> {
                    var rows=jdbc.queryForList("SELECT r.content_hash FROM learning_artifact a JOIN artifact_revision r ON r.artifact_id=a.id AND r.revision=a.current_revision WHERE a.id=? AND a.workspace_id=? AND r.revision=? AND r.review_status<>'STALE'",String.class,ref.id().toString(),workspace.toString(),ref.revision());
                    if (rows.isEmpty()) throw DomainException.conflict("Artifact reference is no longer current");
                    hash=rows.get(0);
                }
                case "KNOWLEDGE_CHUNK" -> {
                    var rows=jdbc.queryForList("SELECT c.content_hash FROM knowledge_chunk c JOIN release_chunk rc ON rc.chunk_id=c.id AND rc.chunk_revision=c.revision JOIN content_release r ON r.id=rc.release_id WHERE c.id=? AND c.revision=? AND rc.release_id=? AND c.status='REVIEWED' AND r.status='PUBLISHED'",String.class,ref.id().toString(),ref.revision(),scope.releaseId().toString());
                    if (rows.isEmpty()) throw DomainException.conflict("Knowledge evidence is unavailable in this release");
                    hash=rows.get(0);
                }
                case "ATTEMPT" -> {
                    if (ref.revision()!=1) throw DomainException.invalid("Attempts have a single immutable result revision");
                    var rows=jdbc.query("SELECT a.answer,a.feedback,a.score FROM exercise_attempt a JOIN workspace_attempt wa ON wa.attempt_id=a.id WHERE a.id=? AND a.user_id=? AND a.release_id=? AND wa.workspace_id=? AND a.status='GRADED'",(rs,n)->Map.of("answer",json.tree(rs.getString("answer")),"feedback",json.tree(rs.getString("feedback")),"score",rs.getBigDecimal("score")),ref.id().toString(),owner.toString(),scope.releaseId().toString(),workspace.toString());
                    if (rows.isEmpty()) throw DomainException.missing();
                    hash=json.hash(rows.get(0));
                }
                default -> throw DomainException.invalid("Reference kind is not supported by this runtime");
            }
            if (!hash.equals(ref.contentHash())) throw DomainException.conflict("Reference content hash does not match authoritative state");
        }
    }
    public Memory stateMemory(UUID owner,UUID workspace,String summary,String key) {
        requireText(summary,2000); requireKey(key);
        return tx.execute(status -> {
            lockOwned(owner,workspace);
            String hash=json.hash(Map.of("workspaceId",workspace,"summary",summary));
            UUID previous=replay(owner,"stateMemory",key,hash);
            if (previous!=null) return memory(owner,workspace,previous);
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO learner_memory(id,workspace_id,kind,basis,content_json,support_refs,status) VALUES(?,?,'PREFERENCE','USER_STATED',?,'[]','ACTIVE')",id.toString(),workspace.toString(),json.write(Map.of("summary",summary)));
            events.append(workspace,"MEMORY_STATED","CLIENT",Map.of("memoryId",id,"revision",1));
            remember(owner,"stateMemory",key,hash,id);
            return memory(owner,workspace,id);
        });
    }
    public List<Memory> memories(UUID owner,UUID workspace) {
        requireOwned(owner,workspace);
        return jdbc.query("SELECT * FROM learner_memory WHERE workspace_id=? ORDER BY created_at,id LIMIT 100",(rs,n)->memoryRow(rs,workspace),workspace.toString());
    }
    private Memory memory(UUID owner,UUID workspace,UUID id) {
        requireOwned(owner,workspace);
        var rows=jdbc.query("SELECT * FROM learner_memory WHERE id=? AND workspace_id=?",(rs,n)->memoryRow(rs,workspace),id.toString(),workspace.toString());
        if (rows.isEmpty()) throw DomainException.missing();
        return rows.get(0);
    }
    private Memory memoryRow(ResultSet rs,UUID workspace) throws SQLException {
        String state=rs.getString("status");
        Timestamp expires=rs.getTimestamp("expires_at");
        if (state.equals("ACTIVE") && expires!=null && !expires.toInstant().isAfter(clock.instant())) state="EXPIRED";
        return new Memory(UUID.fromString(rs.getString("id")),workspace,rs.getInt("revision"),rs.getString("basis"),state.equals("ACTIVE")?json.tree(rs.getString("content_json")).path("summary").asText():"",List.of(json.read(rs.getString("support_refs"),Reference[].class)),state);
    }
    @Override public void revokeMemory(UUID owner,UUID workspace,UUID id,int expectedRevision) {
        tx.executeWithoutResult(status -> {
            lockOwned(owner,workspace);
            Memory current=memory(owner,workspace,id);
            if (current.revision()!=expectedRevision) throw DomainException.conflict("Memory changed before revocation");
            if (current.status().equals("REVOKED")) throw DomainException.conflict("Memory is already revoked");
            jdbc.update("UPDATE learner_memory SET status='REVOKED',revision=revision+1,content_json='{}',support_refs='[]' WHERE id=?",id.toString());
            events.append(workspace,"MEMORY_REVOKED","CLIENT",Map.of("memoryId",id,"revision",expectedRevision+1));
            invalidation.invalidate(workspace,"MEMORY",id,expectedRevision+1);
        });
    }
    public Projection projection(UUID owner,UUID workspace) {
        var scope=requireOwned(owner,workspace);
        var rows=jdbc.queryForList("SELECT processed_sequence,activity_count FROM workspace_projection WHERE workspace_id=?",workspace.toString());
        long sequence=rows.isEmpty()?0:((Number)rows.get(0).get("processed_sequence")).longValue();
        long count=rows.isEmpty()?0:((Number)rows.get(0).get("activity_count")).longValue();
        Long dead=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND published_at IS NULL AND attempts>=5",Long.class,workspace.toString());
        return new Projection(scope.eventSequence(),sequence,count,sequence==scope.eventSequence(),dead==null?0:dead);
    }
    public UUID workspaceFor(UUID owner,String kind,UUID resource) {
        String table=switch(kind) { case "MEMORY"->"learner_memory"; case "ARTIFACT"->"learning_artifact"; case "RUN"->"agent_run"; default->throw DomainException.invalid("Unsupported resource lookup"); };
        var ids=jdbc.queryForList("SELECT r.workspace_id FROM "+table+" r JOIN learning_workspace w ON w.id=r.workspace_id WHERE r.id=? AND w.owner_id=?",String.class,resource.toString(),owner.toString());
        if (ids.isEmpty()) throw DomainException.missing();
        UUID id=UUID.fromString(ids.get(0)); requireOwned(owner,id); return id;
    }
    public UUID replay(UUID owner,String operation,String key,String hash) {
        var rows=jdbc.queryForList("SELECT request_hash,resource_id FROM idempotency_record WHERE user_id=? AND operation=? AND idempotency_key=?",owner.toString(),operation,key);
        if (rows.isEmpty()) return null;
        if (!hash.equals(rows.get(0).get("request_hash"))) throw DomainException.conflict("Idempotency key reused with different input");
        return UUID.fromString(rows.get(0).get("resource_id").toString());
    }
    public void remember(UUID owner,String operation,String key,String hash,UUID resource) {
        EventJournal.requireTransaction();
        jdbc.update("INSERT INTO idempotency_record(user_id,operation,idempotency_key,request_hash,resource_id,expires_at) VALUES(?,?,?,?,?,?)",owner.toString(),operation,key,hash,resource.toString(),Timestamp.from(clock.instant().plusSeconds(86400)));
    }
    public static void requireText(String value,int max) {
        if (value==null || value.isBlank() || value.length()>max) throw DomainException.invalid("Text must be nonblank and within its limit");
    }
    public static void requireKey(String key) {
        if (key==null || !key.matches("[A-Za-z0-9_.:-]{8,64}")) throw DomainException.invalid("Idempotency-Key must be 8..64 safe characters");
    }
    private static void requireMode(String mode) {
        if (mode==null || !Set.of("ON_DEMAND","COLLABORATIVE","QUIET").contains(mode)) throw DomainException.invalid("Unsupported guidance mode");
    }
}
