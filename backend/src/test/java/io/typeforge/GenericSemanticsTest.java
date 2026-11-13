package io.typeforge;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import io.typeforge.shared.Transfers;
import static org.junit.jupiter.api.Assertions.*;
class GenericSemanticsTest {
    @TempDir Path output;
    static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of("invariant", "void f(List<Integer> a){ List<Number> b=a; }", false, false),
            Arguments.of("extends-assignment", "void f(List<Integer> a){ List<? extends Number> b=a; }", true, false),
            Arguments.of("extends-write", "void f(List<? extends Number> a){ a.add(1); }", false, false),
            Arguments.of("extends-null", "void f(List<? extends Number> a){ a.add(null); a.clear(); }", true, false),
            Arguments.of("super-write", "void f(List<? super Integer> a){ a.add(1); Object b=a.get(0); }", true, false),
            Arguments.of("super-read", "void f(List<? super Integer> a){ Integer b=a.get(0); }", false, false),
            Arguments.of("wildcard-write", "void f(List<?> a){ a.add(new Object()); }", false, false),
            Arguments.of("capture-fails", "void f(List<?> a){ a.set(0,a.get(0)); }", false, false),
            Arguments.of("capture-helper", "void f(List<?> a){ g(a); } <T> void g(List<T> a){ a.set(0,a.get(0)); }", true, false),
            Arguments.of("capture-unrelated", "void f(List<?> a,List<?> b){ a.set(0,b.get(0)); }", false, false),
            Arguments.of("primitive-argument", "List<int> x;", false, false),
            Arguments.of("new-type-parameter", "<T> T f(){ return new T(); }", false, false),
            Arguments.of("generic-array", "Object x=new List<String>[2];", false, false),
            Arguments.of("wildcard-array", "Object x=new List<?>[2];", true, false),
            Arguments.of("instanceof-concrete", "boolean f(Object a){ return a instanceof List<String>; }", false, false),
            Arguments.of("instanceof-wildcard", "boolean f(Object a){ return a instanceof List<?>; }", true, false),
            Arguments.of("erasure-clash", "void f(List<String> x){} void f(List<Integer> x){}", false, false),
            Arguments.of("multiple-bound", "<T extends Number & Comparable<T>> T f(T x){ return x; }", true, false),
            Arguments.of("bound-order", "<T extends Comparable<T> & Number> T f(T x){ return x; }", false, false),
            Arguments.of("raw-warning", "void f(){ List a=new ArrayList(); a.add(1); List<String> b=a; }", true, true),
            Arguments.of("generic-method", "<T> T id(T x){ return x; } String f(){ return id(\"x\"); }", true, false),
            Arguments.of("bounded-fail", "<T extends Number> T id(T x){ return x; } void f(){ id(\"x\"); }", false, false),
            Arguments.of("generic-interface", "interface Box<T>{ T get(); } static class S implements Box<String>{ public String get(){ return \"x\"; }}", true, false),
            Arguments.of("generic-throwable", "static class E<T> extends Exception {}", false, false),
            Arguments.of("generic-varargs-warning", "static <T> T first(T... values){ return values[0]; }", true, true),
            Arguments.of("safe-varargs-static", "@SafeVarargs static <T> T first(T... values){ return values[0]; }", true, false),
            Arguments.of("safe-varargs-overridable", "@SafeVarargs <T> T first(T... values){ return values[0]; }", false, false),
            Arguments.of("safe-varargs-not-proof", "@SafeVarargs static void f(List<String>... values){ Object[] aliases=values; aliases[0]=List.of(42); String s=values[0].get(0); }", true, true)
        );
    }
    @ParameterizedTest(name="{0}") @MethodSource("cases")
    void validatesWithRealJavac(String name, String members, boolean expected, boolean warning) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK, not a JRE");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///Probe.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) {
                return "import java.util.*; class Probe { " + members + " }";
            }
        };
        try (var files = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, files, diagnostics,
                List.of("--release", "17", "-proc:none", "-Xlint:all", "-d", output.toString()), null, List.of(unit)).call();
            assertEquals(expected, ok, () -> name+": "+diagnostics.getDiagnostics());
            boolean hasWarning = diagnostics.getDiagnostics().stream().anyMatch(d ->
                d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING);
            if (expected) assertEquals(warning, hasWarning, () -> diagnostics.getDiagnostics().toString());
            if (!expected) assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
        }
    }
    @Test void pecsCopiesIntoBroaderContainer() {
        List<Integer> source = List.of(1, 2);
        List<Number> target = new ArrayList<>();
        Transfers.copy(source, target);
        assertEquals(List.of(1, 2), target);
    }
    @Test void extendsIsNotImmutable() {
        List<? extends Number> values = new ArrayList<>(List.of(1, 2));
        values.clear();
        assertTrue(values.isEmpty());
    }
    static class Parent<T> { T get() { return null; } }
    static class StringChild extends Parent<String> {
        @Override String get() { return "value"; }
    }
    static class SignatureHolder { List<String> names; }
    static class BoundedMethod { <T extends Number> T echo(T value) { return value; } }
    @Test void compilerGeneratesBridgeForErasedOverride() {
        var methods = List.of(StringChild.class.getDeclaredMethods());
        assertTrue(methods.stream().anyMatch(m -> m.isBridge() && m.getReturnType() == Object.class));
        assertTrue(methods.stream().anyMatch(m -> !m.isBridge() && m.getReturnType() == String.class));
    }
    @Test void declarationSignatureCanRetainTypeArguments() throws Exception {
        var type = SignatureHolder.class.getDeclaredField("names").getGenericType();
        var parameterized = assertInstanceOf(java.lang.reflect.ParameterizedType.class, type);
        assertEquals(String.class, parameterized.getActualTypeArguments()[0]);
    }
    @Test void boundedTypeParameterErasesToItsLeftmostBound() throws Exception {
        var method = BoundedMethod.class.getDeclaredMethod("echo", Number.class);
        assertEquals(Number.class, method.getReturnType());
    }
    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    void rawAliasCanCauseRuntimeCastFailure() {
        List<String> typed = new ArrayList<>();
        List raw = typed;
        raw.add(42);
        assertThrows(ClassCastException.class, () -> { String ignored = typed.get(0); });
    }
}
