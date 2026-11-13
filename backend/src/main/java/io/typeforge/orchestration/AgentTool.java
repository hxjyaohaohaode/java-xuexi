package io.typeforge.orchestration;

import java.time.Instant;
import java.util.UUID;

/** Java typing is supplemented by runtime JSON Schema and authorization in adapters. */
public interface AgentTool<I, O> {
    record Context(UUID ownerId, UUID workspaceId, UUID runId, int planRevision,
                   long runEpoch, String executionKey, Instant deadline) {}
    String name();
    String inputSchemaId();
    String outputSchemaId();
    O execute(I input, Context context);
}
