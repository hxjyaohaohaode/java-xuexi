package io.typeforge;

import io.typeforge.lab.FiniteTypeEvaluator;
import io.typeforge.lab.TypeRelationEvaluator.Verdict;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Only fixed repository cases reach javac. No application or learner input is executed. */
class FiniteTypeEvaluatorTest {
    private final FiniteTypeEvaluator evaluator=new FiniteTypeEvaluator();
    static Stream<Arguments> assignments() {return Stream.of(
        Arguments.of("Integer","Number"),Arguments.of("Number","Integer"),Arguments.of("String","CharSequence"),Arguments.of("CharSequence","String"),
        Arguments.of("List<Integer>","List<Number>"),Arguments.of("List<Integer>","List<? extends Number>"),Arguments.of("List<Number>","List<? super Integer>"),
        Arguments.of("List<? super Number>","List<? super Integer>"),Arguments.of("List<? super Integer>","List<? super Number>"),
        Arguments.of("List<? extends Integer>","List<? extends Number>"),Arguments.of("List<? extends Number>","List<? extends Integer>"),
        Arguments.of("List<? super Number>","List<? extends Object>"),Arguments.of("List<?>","List<? extends Object>"),Arguments.of("List<? extends Object>","List<?>"),
        Arguments.of("ArrayList<Integer>","Collection<? extends Number>"),Arguments.of("ArrayList<Integer>","Iterable<?>"),Arguments.of("List<Integer>","ArrayList<Integer>"),
        Arguments.of("List<String>","List<? super Number>"),Arguments.of("List<Object>","List<? super Number>"),Arguments.of("List<? super Object>","List<Object>"),
        Arguments.of("List<? extends String>","List<String>"),Arguments.of("ArrayList<? super Object>","Collection<Object>"),Arguments.of("List<Object>","List<? super Object>"),
        Arguments.of("Object","List<?>"),Arguments.of("List<Integer>","Object"),Arguments.of("null","List<? extends Number>"));}
    @ParameterizedTest @MethodSource("assignments")
    void assignmentSubsetAgreesWithRealJavac(String source,String target) throws IOException {
        String code="import java.util.*; class FixedCase { static void run("+(source.equals("null")?"":source+" value")+"){ "+target+" result="+(source.equals("null")?"null":"value")+"; } }";
        boolean accepted=compiles(code);var result=evaluator.assignment(source,target);
        assertNotEquals(Verdict.UNSUPPORTED,result.verdict());assertEquals(accepted,result.verdict()==Verdict.ALLOWED,code);
    }
    static Stream<Arguments> operations() {return Stream.of(
        Arguments.of("List<? extends Number>","Number","READ"),Arguments.of("List<? extends Number>","Integer","READ"),
        Arguments.of("List<? super Integer>","Object","READ"),Arguments.of("List<? super Integer>","Number","READ"),
        Arguments.of("List<?>","Object","READ"),Arguments.of("Iterable<Integer>","Number","READ"),
        Arguments.of("List<? extends Number>","Integer","WRITE"),Arguments.of("List<? extends Number>","null","WRITE"),
        Arguments.of("List<? super Number>","Integer","WRITE"),Arguments.of("List<? super Integer>","Number","WRITE"),
        Arguments.of("List<Number>","Integer","WRITE"),Arguments.of("List<Integer>","Number","WRITE"),
        Arguments.of("List<?>","null","WRITE"),Arguments.of("List<?>","String","WRITE"),Arguments.of("Iterable<Integer>","Integer","WRITE"));}
    @ParameterizedTest @MethodSource("operations")
    void readWriteSubsetAgreesWithRealJavac(String container,String value,String operation) throws IOException {
        String parameters=container+" container";String body;
        if(operation.equals("READ")) body=value+" result=container.iterator().next();";
        else {if(!value.equals("null")) parameters+=", "+value+" value";body="container.add("+(value.equals("null")?"null":"value")+");";}
        boolean accepted=compiles("import java.util.*; class FixedCase { static void run("+parameters+"){ "+body+" } }");
        assertEquals(accepted,evaluator.readWrite(container,value,operation).verdict()==Verdict.ALLOWED);
    }
    @Test void unsupportedSyntaxIsNeverGuessedOrCompiled() {
        for(String source:List.of("List", "List<List<Integer>>","int","Integer[]","List<T>","java.util.List<Integer>","List<? extends Number & Comparable<?>>","class X { static { System.exit(0); } }")) assertEquals(Verdict.UNSUPPORTED,evaluator.assignment(source,"Object").verdict(),source);
        assertEquals(Verdict.UNSUPPORTED,evaluator.assignment("Object","null").verdict());
        assertEquals(Verdict.UNSUPPORTED,evaluator.readWrite("List<Number>","int","WRITE").verdict());
    }
    private static boolean compiles(String fixedSource) throws IOException {
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var files=compiler.getStandardFileManager(diagnostics,null,null);var output=new ForwardingJavaFileManager<>(files) {
            @Override public JavaFileObject getJavaFileForOutput(Location location,String className,JavaFileObject.Kind kind,FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("memory:///"+className+kind.extension),kind) {@Override public OutputStream openOutputStream(){return new ByteArrayOutputStream();}};
            }
        }) {
            var source=new SimpleJavaFileObject(URI.create("string:///FixedCase.java"),JavaFileObject.Kind.SOURCE) {@Override public CharSequence getCharContent(boolean ignoreEncodingErrors){return fixedSource;}};
            return Boolean.TRUE.equals(compiler.getTask(null,output,diagnostics,List.of("--release","17","-proc:none","-Xlint:all","-Werror"),null,List.of(source)).call());
        }
    }
}
