package io.typeforge.lab;
import java.util.List;
public interface TypeRelationEvaluator {
    enum Verdict { ALLOWED, DENIED, UNSUPPORTED }
    sealed interface TypeExpression permits Named, Parameterized, Wildcard {}
    record Named(String name) implements TypeExpression {}
    record Parameterized(String name, List<TypeExpression> arguments) implements TypeExpression {
        public Parameterized { arguments = List.copyOf(arguments); }
    }
    enum Bound { UNBOUNDED, EXTENDS, SUPER }
    record Wildcard(Bound bound, Named type) implements TypeExpression {}
    record Evaluation(Verdict verdict, String ruleCode, String explanation, String rulesVersion) {}
    // Future adapter must explicitly reject syntax outside the supported teaching subset.
    Evaluation evaluate(TypeExpression source, TypeExpression target);
}
