package io.typeforge.notebook;
import java.util.List;
import java.util.UUID;
public interface NotebookCompiler {
    record Request(UUID ownerId, UUID workspaceId, UUID releaseId, List<UUID> attemptIds) {
        public Request { attemptIds = List.copyOf(attemptIds); }
    }
    UUID requestBuild(Request request);
}
