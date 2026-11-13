package io.typeforge.workspace;

import java.util.UUID;

/** Synchronous derived-state invalidation participates in the caller's workspace transaction. */
public interface ReferenceInvalidator {
    void invalidate(UUID workspaceId,String sourceKind,UUID sourceId,int nextRevision);
}
