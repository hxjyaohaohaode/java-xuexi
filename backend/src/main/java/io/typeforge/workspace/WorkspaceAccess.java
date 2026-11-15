package io.typeforge.workspace;

import java.util.UUID;

/** Cross-module authorization port. lockOwned is valid only inside the caller's short transaction. */
public interface WorkspaceAccess {
    record OwnedWorkspace(UUID id, UUID ownerId, UUID releaseId, int revision, long eventSequence) {}
    OwnedWorkspace lockOwned(UUID ownerId, UUID workspaceId);
    OwnedWorkspace requireOwned(UUID ownerId, UUID workspaceId);
    default void validateArtifactOutput(UUID ownerId, UUID workspaceId, UUID artifactId, int revision, String contentHash) {
        throw new IllegalStateException("Artifact output validation is not configured");
    }
    void validateSnapshot(UUID ownerId, UUID workspaceId, UUID snapshotId);
}
