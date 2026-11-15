package io.typeforge.compiler;
import java.util.List;
import java.util.UUID;
public interface CompilationGateway {
    enum Mode { COMPILE_ONLY, COMPILE_AND_RUN }
    enum Status { QUEUED, RUNNING, SUCCEEDED, COMPILE_ERROR, RUNTIME_ERROR, TIMED_OUT, RESOURCE_LIMIT, INFRA_ERROR, CANCELLED }
    record Request(UUID requesterId, UUID workspaceId, UUID releaseId, String source, Mode mode, String idempotencyKey) {}
    record Diagnostic(String code, String severity, long line, long column, String message) {}
    record Result(UUID jobId, Status status, String sourceHash, String jdkVersion, List<Diagnostic> diagnostics) {
        public Result { diagnostics = List.copyOf(diagnostics); }
    }
    UUID submit(Request request);
    Result findOwnJob(UUID requesterId, UUID jobId);
    void cancelOwnJob(UUID requesterId, UUID jobId);
}
