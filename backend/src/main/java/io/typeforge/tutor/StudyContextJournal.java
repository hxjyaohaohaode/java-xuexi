package io.typeforge.tutor;

import io.typeforge.orchestration.DurableAgentRuntime.Lease;
import io.typeforge.orchestration.DurableAgentRuntime.RunView;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.LearningContextJournal;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** A committed DAG edge is the only way for one role's draft to become another role's input. */
public final class StudyContextJournal {
    public record Receipt(UUID id,String stepKey,String role,int planRevision,long runEpoch,List<UUID> parentCheckpointIds,
                          int sourceCount,int originalChars,int retainedChars,int omittedCount) {}
    public record Incoming(String stepKey,Reference source,String excerpt) {}
    record RoleContext(Receipt receipt,UUID learningContextId,List<Incoming> incoming) {}
    record Prepared(TutorAnswerProvider.Prompt prompt,String body) {}
    private final JdbcTemplate jdbc;
    private final WorkspaceService workspaces;
    private final LearningContextJournal learning;
    private final JsonCodec json;

    StudyContextJournal(JdbcTemplate jdbc,WorkspaceService workspaces,LearningContextJournal learning,JsonCodec json) {
        this.jdbc=jdbc;this.workspaces=workspaces;this.learning=learning;this.json=json;
    }
    // Caller holds the workspace lock, and commits this row atomically with its admitted step.
    void admit(UUID owner,UUID workspace,RunView run,Lease lease,UUID snapshot,UUID request) {
        var concept=courseConcept(jdbc,run.id());
        var checkpoint=learning.capture(owner,workspace,snapshot,concept);
        var existing=jdbc.queryForList("""
            SELECT request_id,learning_context_id FROM study_context_checkpoint WHERE run_id=? AND plan_revision=? AND run_epoch=? AND step_key=?
            """,run.id().toString(),lease.planRevision(),lease.runEpoch(),lease.stepKey());
        if(!existing.isEmpty()) {
            var prior=existing.get(0);
            if(!java.util.Objects.equals(prior.get("request_id"),request==null?null:request.toString()) ||
                !checkpoint.id().toString().equals(prior.get("learning_context_id"))) throw DomainException.conflict("Teaching checkpoint input changed");
            return;
        }
        var target=run.plan().steps().stream().filter(s->s.id().equals(lease.stepKey())).findFirst().orElseThrow();
        var parents=new ArrayList<UUID>();
        var incoming=new ArrayList<Incoming>();
        int raw=checkpoint.originalChars(),retained=checkpoint.retainedChars();
        for(var key:target.dependencies()) {
            var previous=run.steps().stream().filter(s->s.key().equals(key) && s.status().equals("SUCCEEDED")).findFirst().orElseThrow();
            for(var output:previous.outputRefs()) {
                // Match the exact successful output, including a planner preserved across graph revisions.
                List<String> predecessor;
                if(output.kind().equals("SNAPSHOT")) {
                    workspaces.readContext(owner,workspace,output.id());
                    predecessor=jdbc.queryForList("""
                        SELECT c.id FROM study_context_checkpoint c JOIN learning_context_checkpoint l ON l.id=c.learning_context_id
                        WHERE c.run_id=? AND c.step_key=? AND l.snapshot_id=? AND c.plan_revision<=? AND c.run_epoch<=?
                        ORDER BY c.plan_revision DESC,c.run_epoch DESC LIMIT 1
                        """,String.class,run.id().toString(),key,output.id().toString(),lease.planRevision(),lease.runEpoch());
                } else {
                    var ref=new Reference("ARTIFACT",output.id(),output.revision(),output.contentHash());
                    workspaces.validateReferences(owner,workspace,List.of(ref));
                    predecessor=jdbc.queryForList("""
                        SELECT c.id FROM study_context_checkpoint c JOIN study_step_execution s ON s.run_id=c.run_id
                        AND s.step_key=c.step_key AND s.run_epoch=c.run_epoch AND s.plan_revision=c.plan_revision
                        WHERE c.run_id=? AND c.step_key=? AND s.artifact_id=? AND c.plan_revision<=? AND c.run_epoch<=?
                        ORDER BY c.plan_revision DESC,c.run_epoch DESC LIMIT 1
                        """,String.class,run.id().toString(),key,output.id().toString(),lease.planRevision(),lease.runEpoch());
                    var content=json.tree(jdbc.queryForObject("SELECT content_json FROM artifact_revision WHERE artifact_id=? AND revision=?",
                        String.class,output.id().toString(),output.revision()));
                    String text="";
                    for(var block:content.path("blocks")) if(block.path("id").asText().equals("answer"))text=block.path("text").asText();
                    String excerpt=LearningContextJournal.compact(text,12000);
                    raw+=LearningContextJournal.length(text);retained+=LearningContextJournal.length(excerpt);
                    incoming.add(new Incoming(key,ref,excerpt));
                }
                if(!predecessor.isEmpty()) { var id=UUID.fromString(predecessor.get(0));if(!parents.contains(id))parents.add(id); }
            }
        }
        var receipt=new Receipt(UUID.randomUUID(),target.id(),target.role(),lease.planRevision(),lease.runEpoch(),List.copyOf(parents),
            checkpoint.sourceCount()+incoming.size(),raw,retained,checkpoint.omittedCount());
        jdbc.update("""
            INSERT INTO study_context_checkpoint(id,run_id,step_key,plan_revision,run_epoch,learning_context_id,request_id,receipt,handoff_data,handoff_hash)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,receipt.id().toString(),run.id().toString(),target.id(),lease.planRevision(),lease.runEpoch(),checkpoint.id().toString(),
            request==null?null:request.toString(),json.write(receipt),json.write(incoming),json.hash(List.of(receipt,incoming)));
    }
    static UUID courseConcept(JdbcTemplate jdbc,UUID run) {
        return jdbc.query("""
            SELECT p.concept_id FROM courseware_study_run cr JOIN personal_courseware p
            ON p.id=cr.plan_id AND p.workspace_id=cr.workspace_id WHERE cr.run_id=?
            """,(rs,n)->UUID.fromString(rs.getString(1)),run.toString()).stream().findFirst().orElse(null);
    }
    List<Receipt> receipts(UUID run) {
        return jdbc.query("SELECT receipt FROM study_context_checkpoint WHERE run_id=? ORDER BY plan_revision,run_epoch,step_key",
            (rs,n)->json.read(rs.getString(1),Receipt.class),run.toString());
    }
    RoleContext forRequest(UUID owner,UUID workspace,UUID request) {
        // The caller acquired owner/workspace/request locks when claiming this ticket.
        var items=jdbc.query("""
            SELECT c.receipt,c.learning_context_id,c.handoff_data,c.handoff_hash FROM study_context_checkpoint c
            JOIN agent_run r ON r.id=c.run_id WHERE c.request_id=? AND r.workspace_id=?
            """,(rs,n)->{
                var receipt=json.read(rs.getString(1),Receipt.class);
                var handoffs=List.of(json.read(rs.getString(3),Incoming[].class));
                if(!json.hash(List.of(receipt,handoffs)).equals(rs.getString(4))) throw DomainException.conflict("Teaching handoff integrity changed");
                return new RoleContext(receipt,UUID.fromString(rs.getString(2)),handoffs);
            },request.toString(),workspace.toString());
        if(items.isEmpty())return null;
        var context=items.get(0);
        learning.read(owner,workspace,context.learningContextId());
        workspaces.validateReferences(owner,workspace,context.incoming().stream().map(Incoming::source).toList());
        return context;
    }
    void commitPrepared(UUID request,TutorAnswerProvider.Prompt prompt,String body) {
        String wire=body==null?"":body;
        int wrote=jdbc.update("""
            UPDATE study_context_checkpoint SET prepared_prompt=?,wire_body=?,prepared_hash=?
            WHERE request_id=? AND prepared_hash IS NULL
            """,json.write(prompt),wire,json.hash(List.of(prompt,wire)),request.toString());
        if(wrote!=1) throw DomainException.conflict("Prepared teaching turn was already frozen");
    }
    Prepared readPrepared(UUID owner,UUID workspace,UUID request) {
        if(forRequest(owner,workspace,request)==null)return null;
        var values=jdbc.query("""
            SELECT prepared_prompt,wire_body,prepared_hash FROM study_context_checkpoint WHERE request_id=? AND prepared_hash IS NOT NULL
            """,(rs,n)->{
                var prompt=json.read(rs.getString(1),TutorAnswerProvider.Prompt.class);
                String body=rs.getString(2);
                if(!prompt.requestId().equals(request) || !json.hash(List.of(prompt,body)).equals(rs.getString(3))) throw DomainException.conflict("Prepared teaching turn integrity changed");
                return new Prepared(prompt,body.isEmpty()?null:body);
            },request.toString());
        return values.isEmpty()?null:values.get(0);
    }
}
