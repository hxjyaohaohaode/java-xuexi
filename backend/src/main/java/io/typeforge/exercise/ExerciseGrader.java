package io.typeforge.exercise;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import io.typeforge.compiler.CompilationGateway;
public interface ExerciseGrader {
    record GradeInput(UUID attemptId, String answer, String rubricVersion, CompilationGateway.Result compilation) {}
    record Grade(BigDecimal score, BigDecimal maximum, List<String> misconceptionCodes, String graderVersion) {
        public Grade { misconceptionCodes = List.copyOf(misconceptionCodes); }
    }
    // Deterministic rubric implementation belongs here. LLM responses must not supply the score.
    Grade grade(GradeInput input);
}
