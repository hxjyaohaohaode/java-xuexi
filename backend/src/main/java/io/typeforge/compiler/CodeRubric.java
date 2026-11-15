package io.typeforge.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.shared.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A bounded compile-contract rubric, never a claim about runtime behavior or arbitrary program correctness. */
public final class CodeRubric {
    public static final String VERSION="compile-contract-v1";
    public record Rule(String source,Boolean expectedCompiled,List<String> expectedDiagnosticCodes) {}
    private CodeRubric() {}
    public static List<CompileProtocol.Check> parse(JsonNode spec) {
        if(!spec.path("compileChecks").isArray()) throw new DomainException("CODE_RUBRIC_UNAVAILABLE",503,"This code exercise has no reviewed compile-contract rubric");
        var rules=new ArrayList<Rule>();
        for(JsonNode row:spec.path("compileChecks")) {
            if(!row.path("source").isTextual() || !row.path("expectedCompiled").isBoolean() || !row.path("expectedDiagnosticCodes").isArray()) throw new DomainException("INVALID_PUBLISHED_EXERCISE",503,"The code rubric requires editorial repair");
            var codes=new ArrayList<String>();for(JsonNode code:row.path("expectedDiagnosticCodes")) {if(!code.isTextual()) throw DomainException.invalid("Diagnostic code must be text");codes.add(code.asText());}
            rules.add(new Rule(row.path("source").asText(),row.path("expectedCompiled").asBoolean(),codes));
        }
        try {return validateRules(rules);}
        catch(DomainException invalid){throw new DomainException("INVALID_PUBLISHED_EXERCISE",503,"The code rubric requires editorial repair");}
    }
    public static List<CompileProtocol.Check> validateRules(List<Rule> rules) {
        if(rules==null || rules.size()<2 || rules.size()>8 || rules.stream().anyMatch(java.util.Objects::isNull)) throw DomainException.invalid("Code exercises need 2..8 positive and negative compile checks");
        boolean positive=false,negative=false;var checks=new ArrayList<CompileProtocol.Check>();
        for(int i=0;i<rules.size();i++) {
            Rule rule=rules.get(i);
            if(rule.source()==null || rule.source().isBlank() || rule.source().getBytes(StandardCharsets.UTF_8).length>8192 || rule.source().indexOf('\0')>=0 || !StandardCharsets.UTF_8.newEncoder().canEncode(rule.source())
                || rule.expectedCompiled()==null || rule.expectedDiagnosticCodes()==null || rule.expectedDiagnosticCodes().size()>8) throw DomainException.invalid("Invalid bounded compile-contract check");
            // The worker supplies the class wrapper. A check contains class-body declarations only.
            for(String code:rule.expectedDiagnosticCodes()) if(code==null || !code.matches("compiler\\.err\\.[a-z0-9_.]{1,100}")) throw DomainException.invalid("Negative checks require precise javac error codes");
            if(rule.expectedCompiled()) {positive=true;if(!rule.expectedDiagnosticCodes().isEmpty()) throw DomainException.invalid("A passing check must have no expected errors");}
            else {negative=true;if(rule.expectedDiagnosticCodes().isEmpty()) throw DomainException.invalid("A negative check must name its admissible javac error codes");}
            checks.add(new CompileProtocol.Check("check"+i,rule.source(),rule.expectedCompiled(),rule.expectedDiagnosticCodes()));
        }
        if(!positive || !negative) throw DomainException.invalid("A code rubric must include both a positive check and a negative check");
        return List.copyOf(checks);
    }
    public static boolean passes(List<CompileProtocol.Check> expected,List<CompileProtocol.CheckResult> actual) {
        if(expected.size()!=actual.size() || actual.stream().map(CompileProtocol.CheckResult::id).distinct().count()!=actual.size()) return false;
        for(var check:expected) {
            var result=actual.stream().filter(value->value.id().equals(check.id())).findFirst();if(result.isEmpty())return false;
            var value=result.get();
            if(check.expectedCompiled()) {if(!value.status().equals("SUCCEEDED") || !value.diagnosticCodes().isEmpty())return false;}
            else if(!value.status().equals("COMPILE_ERROR") || !value.harnessOnly() || value.diagnosticCodes().isEmpty() || !check.expectedDiagnosticCodes().containsAll(value.diagnosticCodes()))return false;
        }
        return true;
    }
}
