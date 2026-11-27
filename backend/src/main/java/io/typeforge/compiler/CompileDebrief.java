package io.typeforge.compiler;

import io.typeforge.compiler.CompilationGateway.Diagnostic;
import io.typeforge.compiler.CompileService.Job;
import io.typeforge.shared.DomainException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Instruction derived from the public part of a finished worker receipt. No hidden checks or raw I/O. */
public final class CompileDebrief {
    public record Comparison(UUID jobId, String status, boolean sameSource, boolean visibleOutputChanged,
                             int previousDiagnostics, int currentDiagnostics) {}
    public record Review(UUID jobId, String sourceHash, String status, String phase, String observation,
                         String investigate, String nextExperiment, Comparison comparison) {}
    private record Guidance(String phase, String observation, String investigate, String next) {}

    private CompileDebrief() {}

    public static Review from(Job current, Job previous) {
        requireFinished(current);
        Comparison comparison = null;
        if (previous != null) {
            if (!current.workspaceId().equals(previous.workspaceId()) || !current.releaseId().equals(previous.releaseId()))
                throw DomainException.missing();
            if (current.id().equals(previous.id()) || !current.language().equals(previous.language()) || !current.mode().equals(previous.mode()))
                throw DomainException.invalid("Compare distinct jobs using the same language and mode");
            requireFinished(previous);
            comparison = new Comparison(previous.id(), previous.status(), current.sourceHash().equals(previous.sourceHash()),
                    !Objects.equals(current.output(), previous.output()), previous.diagnostics().size(), current.diagnostics().size());
        }
        Guidance g = guidance(current);
        return new Review(current.id(), current.sourceHash(), current.status(), g.phase(), g.observation(),
                g.investigate(), g.next(), comparison);
    }

    private static void requireFinished(Job job) {
        if (job.finishedAt() == null || Set.of("QUEUED", "RUNNING").contains(job.status()))
            throw DomainException.conflict("The worker has not returned a final receipt");
    }

    private static Guidance guidance(Job job) {
        String language = "PYTHON".equals(job.language()) ? "Python" : "Java";
        var diagnostics = job.diagnostics();
        Diagnostic first = diagnostics.stream().filter(d -> "ERROR".equals(d.severity())).findFirst()
                .orElse(diagnostics.isEmpty() ? null : diagnostics.get(0));
        String position = first == null || first.line() < 1 ? "第一条公开诊断" : "第 " + first.line() + " 行的公开诊断";
        if ("COMPILE_ERROR".equals(job.status())) {
            boolean types = diagnostics.stream().anyMatch(d -> d.code().contains("incompatible") || d.code().contains("cant.apply") || d.code().contains("prob.found.req"));
            return new Guidance("COMPILE", language + " 在编译阶段拒绝了这份源码。", types
                    ? "从" + position + "开始，写下实参的静态类型和形参所需类型。若出现参数化类型，把容器和类型实参分别比较。"
                    : "从" + position + "开始，对照源文件中对应的表达式和声明；修正第一处后再判断其余诊断是否仍存在。",
                    types ? "复制当前例子，一次只改一处声明或调用参数，先预测类型检查结果，再比较两份回执。"
                          : "保留相同语言和输入，只修改第一处有依据的问题，再看首条诊断是否改变。");
        }
        if ("RUNTIME_ERROR".equals(job.status()))
            return new Guidance("RUN", language + " 已进入运行阶段，程序以错误结束。",
                    "从错误输出中找出异常名称和首个属于自己源码的位置，记录到达该位置时的参数或变量值。",
                    "保留源码，用一个最小可复现输入和一个正常输入各运行一次，比较两次执行分歧的位置。");
        if ("TIMED_OUT".equals(job.status()) || "RESOURCE_LIMIT".equals(job.status()))
            return new Guidance("LIMIT", "隔离工作进程报告了时间或资源限制。",
                    "检查循环的退出条件、每次迭代的进展及集合增长，分别估算它们随输入规模如何变化。",
                    "将输入规模缩小，再只改变一个规模参数，比较能否完成。在证据不足时保留多种解释。");
        if ("SUCCEEDED".equals(job.status())) {
            boolean unchecked = diagnostics.stream().map(Diagnostic::code).map(s -> s.toLowerCase(Locale.ROOT))
                    .anyMatch(s -> s.contains("unchecked") || s.contains("raw.class") || s.contains("rawtypes"));
            if (unchecked)
                return new Guidance("TYPE_WARNING", "Java 任务已完成，编译器记录了原始类型或未检查操作的警告。",
                        "沿" + position + "追踪值从何处写入、稍后以什么类型读出。写入端失去的静态检查可能在使用端才表现出来。",
                        "用参数化类型替换原始类型并保留同一写入操作，预测警告会消失还是变为编译拒绝，再运行核对。");
            if ("COMPILE_ONLY".equals(job.mode()))
                return new Guidance("COMPILE", "这份 Java 源码通过了编译检查。", "分别指出静态类型检查能保证的性质，以及必须执行才能观察的量。",
                        "为同一源码指定一个输入，先写下预期输出，再切到运行模式检验。");
            return new Guidance("RUN", language + " 程序正常结束。" + (job.outputTruncated() ? " 返回的输出已截断。" : ""),
                    "选取一个输出值，逐步追踪输入、变换和输出之间的对应关系。若没有输出，检查程序是否执行了输出语句。",
                    "先写下边界输入的预期结果，再用同一源码运行，将可见输出与预测逐项核对。");
        }
        return new Guidance("NO_OBSERVATION", "任务已取消或运行环境未给出可用于判断程序行为的结果。",
                "先核对任务状态和运行环境，此次记录无法支持对程序正确性的判断。", "环境可用后重新提交同一份源码，再从首份完整的编译或运行结果开始分析。");
    }
}
