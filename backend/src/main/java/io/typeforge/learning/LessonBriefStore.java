package io.typeforge.learning;

import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reuses revocable memory provenance, without exposing lesson instructions to unrelated snapshots. */
@Component
final class LessonBriefStore {
    record Entry(PersonalCourseware.Brief brief, Reference reference) {}
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final EventJournal events;
    private final WorkspaceService workspaces;

    LessonBriefStore(JdbcTemplate jdbc, JsonCodec json, EventJournal events, WorkspaceService workspaces) {
        this.jdbc=jdbc; this.json=json; this.events=events; this.workspaces=workspaces;
    }

    Entry resolve(UUID owner, UUID workspace, UUID concept, PersonalCourseware.Brief requested, WorkspaceService.Context frozen) {
        // Caller holds the owner and workspace row locks. A frozen snapshot cannot acquire a new source.
        String suffix=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(16)
            .putLong(concept.getMostSignificantBits()).putLong(concept.getLeastSignificantBits()).array());
        String active="LESSON:C:"+suffix, archived="LESSON:H:"+suffix;
        if (frozen!=null) {
            if (requested!=null) throw DomainException.invalid("Choose either a frozen snapshot or a new lesson brief");
            for (var ref:frozen.references()) if (ref.kind().equals("MEMORY")) {
                var matching=find(workspace," AND id=? AND kind IN (?,?)",List.of(ref.id().toString(),active,archived));
                if (!matching.isEmpty()) return matching.get(0);
            }
            return null;
        }
        var existing=find(workspace," AND kind=?",List.of(active));
        var current=existing.isEmpty()?null:existing.get(0);
        if (requested==null) return current;
        if (requested.interest().isEmpty() && requested.requirements().isEmpty()) {
            for (var entry:find(workspace," AND kind IN (?,?)",List.of(active,archived)))
                workspaces.revokeMemory(owner,workspace,entry.reference().id(),entry.reference().revision());
            return null;
        }
        if (current!=null && current.brief().equals(requested)) return current;
        if (current!=null) jdbc.update("UPDATE learner_memory SET kind=? WHERE workspace_id=? AND id=?",
            archived,workspace.toString(),current.reference().id().toString());
        UUID id=UUID.randomUUID();
        String summary="本课兴趣："+requested.interest()+"\n本课要求："+requested.requirements();
        var payload=Map.of("summary",summary,"conceptId",concept.toString(),"interest",requested.interest(),"requirements",requested.requirements());
        jdbc.update("INSERT INTO learner_memory(id,workspace_id,kind,basis,content_json,support_refs,status) VALUES(?,?,?,'USER_STATED',?,'[]','ACTIVE')",
            id.toString(),workspace.toString(),active,json.write(payload));
        events.append(workspace,"MEMORY_STATED","CLIENT",Map.of("memoryId",id,"revision",1));
        return new Entry(requested,new Reference("MEMORY",id,1,json.hash(payload)));
    }

    private List<Entry> find(UUID workspace,String clause,List<String> parameters) {
        var args=new java.util.ArrayList<String>(); args.add(workspace.toString()); args.addAll(parameters);
        return jdbc.query("SELECT id,revision,content_json FROM learner_memory WHERE workspace_id=? AND status='ACTIVE'"+clause,(rs,n)->{
            var payload=json.tree(rs.getString(3));
            return new Entry(new PersonalCourseware.Brief(payload.get("interest").asText(),payload.get("requirements").asText()),
                new Reference("MEMORY",UUID.fromString(rs.getString(1)),rs.getInt(2),json.hash(payload)));
        },args.toArray());
    }
}
