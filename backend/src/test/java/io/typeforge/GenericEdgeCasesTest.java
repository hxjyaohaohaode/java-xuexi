package io.typeforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Version-pinned teaching counterexamples. Only these repository-owned literals
 * enter JavaCompiler; this test is not a compiler endpoint or an execution sandbox.
 */
class GenericEdgeCasesTest {
    @TempDir Path output;

    record Fixture(String id, String conceptCode, String specificationSection,
                   int release, String members, boolean compiles, boolean warns) {
        @Override public String toString() {
            return id + " [" + conceptCode + ", Java " + release + ", " + specificationSection + "]";
        }
    }

    static Stream<Fixture> fixtures() {
        return Stream.of(
            new Fixture("instanceof-refined-type", "G15", "JLS 15.20.2", 17,
                "boolean f(List<Integer> value) { return value instanceof ArrayList<Integer>; }", true, false),
            new Fixture("instanceof-refinement-before-java16", "G15", "JLS 15.20.2", 15,
                "boolean f(List<Integer> value) { return value instanceof ArrayList<Integer>; }", false, false),
            new Fixture("instanceof-unrelated-argument", "G15", "JLS 15.20.2", 17,
                "boolean f(List<Integer> value) { return value instanceof ArrayList<String>; }", false, false),
            new Fixture("instanceof-unknown-argument", "G15", "JLS 15.20.2", 17,
                "boolean f(Object value) { return value instanceof List<String>; }", false, false),
            new Fixture("static-cannot-use-class-parameter", "G02", "JLS 8.1.2", 17,
                "static class Box<T> { static T value; }", false, false),
            new Fixture("static-method-has-independent-parameter", "G04", "JLS 8.4.4", 17,
                "static class Box<T> { static <U> U identity(U value) { return value; } }", true, false),
            new Fixture("recursive-bound-string", "G07", "JLS 4.4", 17,
                "<T extends Comparable<? super T>> T max(T a, T b) { return a.compareTo(b) >= 0 ? a : b; } String f() { return max(\"a\", \"b\"); }", true, false),
            new Fixture("recursive-bound-object-rejected", "G07", "JLS 4.4", 17,
                "<T extends Comparable<? super T>> T max(T a, T b) { return a; } Object f() { return max(new Object(), new Object()); }", false, false),
            new Fixture("duplicate-erased-interface-bound", "G08", "JLS 4.4", 17,
                "<T extends Comparable<String> & Comparable<Integer>> void f(T value) {}", false, false),
            new Fixture("wildcard-is-not-object-list", "G09", "JLS 4.5.1", 17,
                "void f(List<?> value) { List<Object> objects = value; }", false, false),
            new Fixture("super-does-not-accept-arbitrary-number", "G11", "JLS 5.1.10", 17,
                "void f(List<? super Integer> value) { value.add(1.5); }", false, false),
            new Fixture("super-can-accept-number-list", "G11", "JLS 4.5.1", 17,
                "void accept(List<? super Integer> value) { value.add(1); } void f(List<Number> value) { accept(value); }", true, false),
            new Fixture("capture-permits-same-list-rewrite", "G13", "JLS 5.1.10", 17,
                "void f(List<? extends Number> value) { rewrite(value); } <T> void rewrite(List<T> value) { value.set(0, value.get(0)); }", true, false),
            new Fixture("type-variable-array-creation", "G15", "JLS 15.10.1", 17,
                "<T> T[] create() { return new T[1]; }", false, false),
            new Fixture("wildcard-cannot-instantiate", "G09", "JLS 15.9", 17,
                "Object value = new ArrayList<?>();", false, false),
            new Fixture("nested-wildcard-argument-is-legal", "G09", "JLS 15.9", 17,
                "List<List<?>> value = new ArrayList<List<?>>();", true, false),
            new Fixture("type-variable-in-throws", "G04", "JLS 8.4.6", 17,
                "<E extends Exception> void raise(E error) throws E { throw error; }", true, false),
            new Fixture("type-variable-cannot-be-catch-type", "G04", "JLS 14.20", 17,
                "<E extends Exception> void f() { try { throw new Exception(); } catch (E error) {} }", false, false),
            new Fixture("private-safe-varargs-java17", "G16", "JLS 9.6.4.7", 17,
                "@SafeVarargs private <T> T first(T... values) { return values[0]; }", true, false),
            new Fixture("unchecked-cast-still-warns", "G16", "JLS 5.1.6", 17,
                "List<String> f(Object value) { return (List<String>) value; }", true, true)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void verifiesTeachingClaimUsingPinnedLanguageRelease(Fixture fixture) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A full JDK compiler is required for language evidence");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var unit = new SimpleJavaFileObject(URI.create("string:///Probe.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "import java.util.*; class Probe { " + fixture.members() + " }";
            }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--release", Integer.toString(fixture.release()), "-proc:none", "-Xlint:all",
                    "-Xlint:-options", "-d", output.toString()), null, List.of(unit)).call();
            assertEquals(fixture.compiles(), compiled, () -> fixture + ": " + diagnostics.getDiagnostics());
            boolean errors = diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
            assertEquals(!fixture.compiles(), errors);
            if (compiled) {
                boolean warnings = diagnostics.getDiagnostics().stream().anyMatch(d ->
                    d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING);
                assertEquals(fixture.warns(), warnings, () -> fixture + ": " + diagnostics.getDiagnostics());
            }
        }
    }

    @Test void compileTimeWildcardPermissionDoesNotGuaranteeCollectionMutability() {
        List<? extends Number> immutable = List.of(1, 2);
        assertThrows(UnsupportedOperationException.class, immutable::clear);
        List<? extends Number> mutable = new ArrayList<>(List.of(1, 2));
        mutable.clear();
        assertTrue(mutable.isEmpty());
    }

    @Test void arraysAreCovariantButValidateTheirActualComponentTypeAtRuntime() {
        Number[] numbers = new Integer[] {1};
        assertThrows(ArrayStoreException.class, () -> numbers[0] = 1.5);
        assertEquals(1, numbers[0]);
    }

    @Test void differentGenericArgumentsDoNotCreateDifferentRuntimeClasses() {
        assertEquals(new ArrayList<String>().getClass(), new ArrayList<Integer>().getClass());
        List<String> names = new ArrayList<>();
        assertFalse(names instanceof java.util.LinkedList<?>);
    }

    static class InterfaceBound {
        <T extends Runnable & java.io.Serializable> T echo(T value) { return value; }
    }

    @Test void anInterfaceCanBeTheLeftmostErasureBound() throws NoSuchMethodException {
        var method = InterfaceBound.class.getDeclaredMethod("echo", Runnable.class);
        assertEquals(Runnable.class, method.getReturnType());
        assertEquals(2, method.getTypeParameters()[0].getBounds().length);
    }
}
