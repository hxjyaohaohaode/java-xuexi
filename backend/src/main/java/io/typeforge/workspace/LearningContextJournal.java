package io.typeforge.workspace;

import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable, extractive learner memory. The authoritative workspace projection is checked on every use. */
@Service
public final class LearningContextJournal {
    public record Recall(String kind,UUID sourceId,int revision,String contentHash,String text) {}
    public record Checkpoint(UUID id,UUID workspaceId,UUID snapshotId,UUID conceptId,String sourceHash,String digest,
                             List<Recall> facts,int sourceCount,int originalChars,int retainedChars,int omittedCount) {
        public Checkpoint { facts=List.copyOf(facts); }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceService workspaces;
    private final JsonCodec json;

    public LearningContextJournal(JdbcTemplate jdbc,TransactionTemplate tx,WorkspaceService workspaces,JsonCodec json) {
        this.jdbc=jdbc;this.tx=tx;this.workspaces=workspaces;this.json=json;
    }

    public Checkpoint capture(UUID owner,UUID workspace,UUID snapshotId,UUID conceptId) {
        return tx.execute(status->{
            var scope=workspaces.lockOwned(owner,workspace);
            var snapshot=workspaces.readContext(owner,workspace,snapshotId);
            if(conceptId!=null && jdbc.queryForList("SELECT concept_id FROM release_concept WHERE release_id=? AND concept_id=?",
                String.class,scope.releaseId().toString(),conceptId.toString()).isEmpty()) throw DomainException.missing();
            UUID id=UUID.nameUUIDFromBytes(("learning-context/v1/"+workspace+"/"+snapshotId+"/"+conceptId).getBytes(StandardCharsets.UTF_8));
            var existing=load(workspace,id);
            if(!existing.isEmpty()) return verified(existing.get(0),snapshot);
            var eligible=new ArrayList<Recall>();
            for(var ref:snapshot.references()) {
                String text=sourceText(workspace,ref,conceptId);
                if(text!=null) eligible.add(new Recall(ref.kind(),ref.id(),ref.revision(),ref.contentHash(),text));
            }
            var facts=new ArrayList<Recall>();
            // Snapshots retain explicit attempt order (otherwise newest first); goals and memories are oldest first.
            for(String kind:List.of("GOAL","MEMORY","ATTEMPT")) {
                var source=eligible.stream().filter(f->f.kind().equals(kind)).toList();
                int count=Math.min(source.size(),kind.equals("GOAL")?2:3);
                for(int i=0;i<count;i++) {
                    var fact=source.get(kind.equals("ATTEMPT")?i:source.size()-1-i);
                    facts.add(new Recall(kind,fact.sourceId(),fact.revision(),fact.contentHash(),compact(fact.text(),kind.equals("ATTEMPT")?240:160)));
                }
            }
            int original=eligible.stream().mapToInt(f->length(f.text())).sum();
            int retained=facts.stream().mapToInt(f->length(f.text())).sum();
            var checkpoint=new Checkpoint(id,workspace,snapshotId,conceptId,snapshot.contentHash(),"",facts,eligible.size(),original,retained,eligible.size()-facts.size());
            String digest=digest(checkpoint);
            checkpoint=new Checkpoint(id,workspace,snapshotId,conceptId,snapshot.contentHash(),digest,facts,eligible.size(),original,retained,eligible.size()-facts.size());
            jdbc.update("INSERT INTO learning_context_checkpoint(id,workspace_id,snapshot_id,concept_id,source_hash,digest,payload) VALUES(?,?,?,?,?,?,?)",
                id.toString(),workspace.toString(),snapshotId.toString(),conceptId==null?null:conceptId.toString(),snapshot.contentHash(),digest,json.write(checkpoint));
            return checkpoint;
        });
    }

    public Checkpoint read(UUID owner,UUID workspace,UUID id) {
        return tx.execute(status->{
            workspaces.lockOwned(owner,workspace);
            var rows=load(workspace,id);
            if(rows.isEmpty()) throw DomainException.missing();
            var entry=rows.get(0);
            return verified(entry,workspaces.readContext(owner,workspace,entry.snapshotId()));
        });
    }

    /** Rehydrates a bounded full source from the same manifest, including items omitted under pressure. */
    public Recall recall(UUID owner,UUID workspace,UUID checkpointId,String kind,UUID sourceId) {
        return tx.execute(status->{
            var checkpoint=read(owner,workspace,checkpointId);
            var context=workspaces.readContext(owner,workspace,checkpoint.snapshotId());
            var ref=context.references().stream().filter(r->r.kind().equals(kind) && r.id().equals(sourceId)).findFirst().orElseThrow(DomainException::missing);
            String full=sourceText(workspace,ref,checkpoint.conceptId());
            if(full==null) throw DomainException.missing();
            return new Recall(kind,ref.id(),ref.revision(),ref.contentHash(),full);
        });
    }

    private String sourceText(UUID workspace,Reference ref,UUID concept) {
        return switch(ref.kind()) {
            case "GOAL" -> jdbc.query("SELECT goal_text FROM learning_goal WHERE id=? AND workspace_id=? AND origin_kind='LEARNER'",(rs,n)->rs.getString(1),ref.id().toString(),workspace.toString()).stream().findFirst().orElse(null);
            case "MEMORY" -> jdbc.query("SELECT content_json FROM learner_memory WHERE id=? AND workspace_id=? AND basis='USER_STATED' AND status='ACTIVE'",
                (rs,n)->json.tree(rs.getObject(1)).path("summary").asText(),ref.id().toString(),workspace.toString()).stream().findFirst().orElse(null);
            case "ATTEMPT" -> {
                if(concept!=null && jdbc.queryForList("""
                    SELECT ec.concept_id FROM exercise_attempt a JOIN exercise_concept ec ON ec.exercise_id=a.exercise_id
                    AND ec.exercise_revision=a.exercise_revision WHERE a.id=? AND ec.concept_id=?
                    """,String.class,ref.id().toString(),concept.toString()).isEmpty()) yield null;
                yield jdbc.queryForObject("""
                    SELECT a.score,a.maximum,a.answer FROM exercise_attempt a JOIN workspace_attempt w ON w.attempt_id=a.id
                    WHERE a.id=? AND w.workspace_id=? AND a.status='GRADED'
                    """,(rs,n)->"得分 "+rs.getBigDecimal(1).stripTrailingZeros().toPlainString()+"/"+rs.getBigDecimal(2).stripTrailingZeros().toPlainString()
                    +"；已提交 "+json.write(json.tree(rs.getObject(3))),ref.id().toString(),workspace.toString());
            }
            default -> null;
        };
    }
    private List<Checkpoint> load(UUID workspace,UUID id) {
        return jdbc.query("SELECT payload,digest FROM learning_context_checkpoint WHERE workspace_id=? AND id=?",(rs,n)->{
            var value=json.read(rs.getString(1),Checkpoint.class);
            if(!value.id().equals(id) || !value.workspaceId().equals(workspace) || !value.digest().equals(rs.getString(2))
                || !value.digest().equals(digest(value))) throw DomainException.conflict("Learning checkpoint integrity changed");
            return value;
        },workspace.toString(),id.toString());
    }
    private Checkpoint verified(Checkpoint value,WorkspaceService.Context snapshot) {
        if(!value.snapshotId().equals(snapshot.id()) || !value.sourceHash().equals(snapshot.contentHash())) throw DomainException.conflict("Learning checkpoint no longer matches its source");
        return value;
    }
    private String digest(Checkpoint c) {
        return json.hash(new Checkpoint(c.id(),c.workspaceId(),c.snapshotId(),c.conceptId(),c.sourceHash(),"",c.facts(),c.sourceCount(),c.originalChars(),c.retainedChars(),c.omittedCount()));
    }
    public static int length(String value){return value.codePointCount(0,value.length());}
    public static String compact(String text,int limit) {
        int size=length(text);
        if(size<=limit)return text;
        int tail=Math.min(45,limit/4),head=limit-tail-3;
        return text.substring(0,text.offsetByCodePoints(0,head))+" … "+text.substring(text.offsetByCodePoints(0,size-tail));
    }
}
