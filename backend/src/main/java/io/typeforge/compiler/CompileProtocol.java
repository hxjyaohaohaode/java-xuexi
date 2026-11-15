package io.typeforge.compiler;

import java.util.List;
import java.util.UUID;

/** Private API-to-worker protocol. The HTTP learner DTO intentionally excludes checks and source. */
public final class CompileProtocol {
    private CompileProtocol() {}
    public record Check(String id,String source,boolean expectedCompiled,List<String> expectedDiagnosticCodes) {
        public Check {expectedDiagnosticCodes=List.copyOf(expectedDiagnosticCodes);}
    }
    public record Request(UUID jobId,UUID leaseToken,String source,String sourceHash,List<Check> checks) {
        public Request {checks=List.copyOf(checks);}
    }
    public record CheckResult(String id,String status,List<String> diagnosticCodes,boolean harnessOnly) {
        public CheckResult {diagnosticCodes=List.copyOf(diagnosticCodes);}
    }
    public record Result(String status,String sourceHash,String jdkVersion,List<CompilationGateway.Diagnostic> diagnostics,
                         boolean outputTruncated,List<CheckResult> checks) {
        public Result {diagnostics=List.copyOf(diagnostics);checks=List.copyOf(checks);}
    }
    public static final class Failure extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final String code;
        public Failure(String code){super(code);this.code=code;}
        public String code(){return code;}
    }
}
