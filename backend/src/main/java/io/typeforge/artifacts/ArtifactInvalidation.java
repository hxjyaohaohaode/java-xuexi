package io.typeforge.artifacts;

import io.typeforge.operations.events.EventJournal;
import io.typeforge.workspace.ReferenceInvalidator;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.shared.DomainException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Synchronous bounded lineage invalidation; no stale-acceptance window before async projections. */
@Service
public final class ArtifactInvalidation implements ReferenceInvalidator {
    static final int MAX_LINEAGE=256;
    private record Target(UUID id,int revision) {}
    private final JdbcTemplate jdbc;
    private final EventJournal events;
    public ArtifactInvalidation(JdbcTemplate jdbc,EventJournal events) { this.jdbc=jdbc;this.events=events; }
    /** Admission reserves the ability to revoke every accepted source within a bounded transaction. */
    void validateAdmission(UUID workspace,UUID artifact,int nextRevision,List<Reference> sources) {
        EventJournal.requireTransaction();
        var superseded=new HashSet<>(targets(workspace,"ARTIFACT",artifact,nextRevision-1));
        for(Reference source:sources) {
            var dependents=targets(workspace,source.kind(),source.id(),Integer.MAX_VALUE);
            long retained=dependents.stream().filter(target->!superseded.contains(target)).count();
            if(retained>=MAX_LINEAGE) throw new DomainException("LINEAGE_LIMIT",409,"Saving this revision would exceed the source's 256 active dependent revisions");
        }
    }
    @Override public void invalidate(UUID workspace,String kind,UUID source,int newRevision) {
        EventJournal.requireTransaction();
        for(Target target:targets(workspace,kind,source,newRevision-1)) {
            int changed=jdbc.update("UPDATE artifact_revision SET review_status='STALE' WHERE artifact_id=? AND revision=? AND review_status<>'STALE'",target.id().toString(),target.revision());
            if(changed>0) events.append(workspace,"ARTIFACT_STALE","SERVER",Map.of("artifactId",target.id(),"revision",target.revision(),"sourceKind",kind,"sourceId",source));
        }
    }
    private List<Target> targets(UUID workspace,String kind,UUID source,int maximumRevision) {
        if(maximumRevision<1) return List.of();
        // The index includes transitive sources. A one-hop completeness check detects old direct-only
        // data even across STALE intermediate revisions; never silently publish partial invalidation.
        var missing=jdbc.queryForList("SELECT 1 FROM artifact_input_reference ancestor JOIN artifact_input_reference child ON child.workspace_id=ancestor.workspace_id AND child.source_kind='ARTIFACT' AND child.source_id=ancestor.artifact_id AND child.source_revision=ancestor.artifact_revision WHERE ancestor.workspace_id=? AND ancestor.source_kind=? AND ancestor.source_id=? AND ancestor.source_revision<=? AND NOT EXISTS (SELECT 1 FROM artifact_input_reference closure WHERE closure.artifact_id=child.artifact_id AND closure.artifact_revision=child.artifact_revision AND closure.source_kind=ancestor.source_kind AND closure.source_id=ancestor.source_id AND closure.source_revision=ancestor.source_revision AND closure.source_hash=ancestor.source_hash) LIMIT 1",
            Integer.class,workspace.toString(),kind,source.toString(),maximumRevision);
        if(!missing.isEmpty()) throw new DomainException("LINEAGE_REBUILD_REQUIRED",409,"Legacy lineage index is incomplete; repair it before accepting writes or revocation");
        var targets=jdbc.query("SELECT ref.artifact_id,ref.artifact_revision FROM artifact_input_reference ref JOIN artifact_revision revision ON revision.artifact_id=ref.artifact_id AND revision.revision=ref.artifact_revision WHERE ref.workspace_id=? AND ref.source_kind=? AND ref.source_id=? AND ref.source_revision<=? AND revision.review_status<>'STALE' LIMIT 257",
            (rs,n)->new Target(UUID.fromString(rs.getString(1)),rs.getInt(2)),workspace.toString(),kind,source.toString(),maximumRevision);
        if(targets.size()>MAX_LINEAGE) throw new DomainException("LINEAGE_REBUILD_REQUIRED",409,"Legacy lineage exceeds the admitted active fanout bound; repair is required");
        return targets;
    }
}
