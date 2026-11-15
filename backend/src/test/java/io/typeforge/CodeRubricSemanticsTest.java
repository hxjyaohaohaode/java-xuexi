package io.typeforge;

import io.typeforge.compiler.CodeRubric;
import io.typeforge.compiler.CompileProtocol;
import io.typeforge.lab.CodeLabCatalog;
import io.typeforge.shared.DomainException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Only fixed repository fixtures enter the test JVM compiler. No runtime/application path calls this. */
class CodeRubricSemanticsTest {
    private static final String GOOD="public class Main { public static <T> T first(java.util.List<T> values){return values.get(0);} }";
    private static final String EMPTY="public class Main {}";
    private static final String RAW="public class Main { public static Object first(java.util.List values){return values.get(0);} }";
    private static final List<CodeRubric.Rule> RULES=List.of(
        new CodeRubric.Rule("static void verify(){ Integer x=Main.first(java.util.List.of(1)); }",true,List.of()),
        new CodeRubric.Rule("static void verify(){ String x=Main.first(java.util.List.of(1)); }",false,List.of("compiler.err.prob.found.req")));
    private record Outcome(boolean passed,List<String> codes,boolean harnessOnly) {}
    private static JavaFileObject source(String name,String text){return new SimpleJavaFileObject(URI.create("string:///"+name+".java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return text;}};}
    private Outcome compile(String source,CompileProtocol.Check check) throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var standard=compiler.getStandardFileManager(diagnostics,null,java.nio.charset.StandardCharsets.UTF_8);
            var files=new ForwardingJavaFileManager<>(standard){@Override public JavaFileObject getJavaFileForOutput(Location location,String name,JavaFileObject.Kind kind,FileObject sibling){return new SimpleJavaFileObject(URI.create("bytes:///"+name+kind.extension),kind){@Override public java.io.OutputStream openOutputStream(){return new java.io.ByteArrayOutputStream();}};}}){
            var units=new ArrayList<JavaFileObject>();units.add(source("Main",source));if(check!=null)units.add(source("Probe_"+check.id(),"final class Probe_"+check.id()+" { "+check.source()+" }"));
            boolean ok=compiler.getTask(null,files,diagnostics,List.of("--release","17","-proc:none","-Xlint:all","-Werror"),null,units).call();
            var errors=diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).toList();
            return new Outcome(ok,errors.stream().map(Diagnostic::getCode).toList(),!errors.isEmpty() && errors.stream().allMatch(d->d.getSource()!=null && d.getSource().getName().contains("Probe_")));
        }
    }
    @Test void fixedGenericSignaturePassesPositiveAndFailsOnlyTheExpectedNegativeCheck() throws Exception {
        var rules=CodeRubric.validateRules(RULES);assertTrue(compile(GOOD,null).passed());var results=new ArrayList<CompileProtocol.CheckResult>();
        for(var check:rules){var result=compile(GOOD,check);results.add(new CompileProtocol.CheckResult(check.id(),result.passed()?"SUCCEEDED":"COMPILE_ERROR",result.codes(),result.harnessOnly()));}
        assertTrue(CodeRubric.passes(rules,results));
    }
    @Test void emptyCompilableAndRawUnsafeProgramsDoNotPassTheTypeContract() throws Exception {
        assertTrue(compile(EMPTY,null).passed());var results=new ArrayList<CompileProtocol.CheckResult>();var checks=CodeRubric.validateRules(RULES);
        for(var check:checks){var result=compile(EMPTY,check);results.add(new CompileProtocol.CheckResult(check.id(),result.passed()?"SUCCEEDED":"COMPILE_ERROR",result.codes(),result.harnessOnly()));}
        assertFalse(CodeRubric.passes(checks,results));assertFalse(compile(RAW,null).passed());
    }
    @Test void unrelatedNegativeErrorsMissingChecksAndDuplicateResultsCannotPass(){
        var checks=CodeRubric.validateRules(RULES);var positive=new CompileProtocol.CheckResult("check0","SUCCEEDED",List.of(),false);
        assertFalse(CodeRubric.passes(checks,List.of(positive)));
        assertFalse(CodeRubric.passes(checks,List.of(positive,positive)));
        assertFalse(CodeRubric.passes(checks,List.of(positive,new CompileProtocol.CheckResult("check1","COMPILE_ERROR",List.of("compiler.err.duplicate.class"),true))));
        assertFalse(CodeRubric.passes(checks,List.of(positive,new CompileProtocol.CheckResult("check1","COMPILE_ERROR",List.of("compiler.err.prob.found.req"),false))));
        assertThrows(DomainException.class,()->CodeRubric.validateRules(List.of(RULES.get(0),RULES.get(0))));
    }
    @Test void everyPublishedCodeLabExampleHasVerifiedPositiveAndNegativeFixedSource() throws Exception {
        assertEquals(6,CodeLabCatalog.examples().size());
        for(var example:CodeLabCatalog.examples()){
            assertTrue(compile(example.positiveSource(),null).passed(),example.id()+" positive fixture");
            assertFalse(compile(example.negativeSource(),null).passed(),example.id()+" negative fixture");
        }
    }
}
