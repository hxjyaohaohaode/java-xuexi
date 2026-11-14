package io.typeforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.typeforge.artifacts.ArtifactService;
import io.typeforge.identity.IdentityService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:typeforge-workspace-closure;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles({"test","demo"})
@Transactional
class WorkspaceArtifactReferenceTest {
    @Autowired WorkspaceService workspaces;
    @Autowired ArtifactService artifacts;
    @Autowired IdentityService identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired Clock clock;

    @Test void oldArtifactOnlyContextCannotBypassNaturallyExpiredTransitiveMemory() {
        UUID owner=identities.register("closure-"+UUID.randomUUID(),"Isolated-closure-fixture-123!").userId();
        var workspace=workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"Context closure regression","ON_DEMAND",key());
        var memory=workspaces.stateMemory(owner,workspace.id(),"A preference whose retention expires independently of an event",key());
        var memoryRef=workspaces.createContext(owner,workspace.id()).references().stream().filter(ref->ref.kind().equals("MEMORY")).findFirst().orElseThrow();
        var first=document(owner,workspace.id(),List.of(memoryRef));
        var second=document(owner,workspace.id(),List.of(new Reference("ARTIFACT",first.artifactId(),first.revision(),first.contentHash())));
        var captured=workspaces.createContext(owner,workspace.id());
        // Simulate a bounded snapshot in which top-N selection omitted the concrete MEMORY.
        // Only A and B are named, so the result cannot pass via a direct-memory check.
        List<Reference> artifactOnly=captured.references().stream().filter(ref->ref.kind().equals("ARTIFACT")).toList();
        assertEquals(2,artifactOnly.size());assertFalse(artifactOnly.contains(memoryRef));
        UUID snapshot=UUID.randomUUID();
        String hash=json.hash(Map.of("workspaceId",workspace.id(),"revision",captured.workspaceRevision(),"sequence",captured.eventSequence(),"references",artifactOnly));
        jdbc.update("INSERT INTO context_snapshot(id,workspace_id,workspace_revision,event_sequence,references_json,content_hash) VALUES(?,?,?,?,?,?)",
            snapshot.toString(),workspace.id().toString(),captured.workspaceRevision(),captured.eventSequence(),json.write(artifactOnly),hash);
        assertEquals(artifactOnly,workspaces.readContext(owner,workspace.id(),snapshot).references());
        // Passive expiry: no revoke method, no revision bump and no STALE projection.
        jdbc.update("UPDATE learner_memory SET expires_at=? WHERE id=?",Timestamp.from(clock.instant().minusSeconds(1)),memory.id().toString());
        assertEquals("DRAFT",artifacts.version(owner,first.artifactId(),1).reviewStatus());
        assertEquals("DRAFT",artifacts.version(owner,second.artifactId(),1).reviewStatus());
        DomainException rejected=assertThrows(DomainException.class,()->workspaces.readContext(owner,workspace.id(),snapshot));
        assertEquals(409,rejected.status());assertTrue(rejected.getMessage().contains("expired"));
        assertThrows(DomainException.class,()->workspaces.validateReferences(owner,workspace.id(),List.of(new Reference("ARTIFACT",second.artifactId(),1,second.contentHash()))));
    }
    private ArtifactService.Version document(UUID owner,UUID workspace,List<Reference> refs) {
        var artifact=artifacts.create(owner,workspace,"DOCUMENT","Context source",null,key());
        var content=json.tree(json.write(Map.of("kind","DOCUMENT","schemaVersion","1.0","title","Context source","blocks",List.of(Map.of("id","body","kind","PARAGRAPH","text","A derived fixture document","evidenceRefs",List.of())))));
        return artifacts.save(owner,artifact.id(),0,content,refs);
    }
    private static String key() { return UUID.randomUUID().toString(); }
}
