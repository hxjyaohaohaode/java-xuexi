package io.typeforge.workspace;
import java.util.List;
import java.util.UUID;
public interface WorkspaceContextStore {
    record Reference(String kind, UUID id, int revision, String contentHash) {}
    record Snapshot(UUID id, UUID workspaceId, int revision, List<Reference> references) {
        public Snapshot { references = List.copyOf(references); }
    }
    Snapshot snapshotOwn(UUID ownerId, UUID workspaceId);
    void revokeMemory(UUID ownerId, UUID workspaceId, UUID memoryId, int expectedRevision);
}
