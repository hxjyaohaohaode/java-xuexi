package io.typeforge.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class InterestApplicationTest {
    @TempDir Path output;

    @Test void java17ChecksTheActualLabUnitForBothAnswersAndRunsEveryReviewedPositiveCase() throws Exception {
        List<InterestApplications.Recipe> recipes;
        try (var input=new ClassPathResource("knowledge/interest-applications.json").getInputStream()) {
            recipes=Arrays.asList(new ObjectMapper().readValue(input,InterestApplications.Recipe[].class));
        }
        assertEquals(16,recipes.size());
        assertEquals(16,recipes.stream().map(InterestApplications.Recipe::conceptId).distinct().count());
        var theme=new InterestApplications.Theme(null,"","Entry","Feature","Note","",true);
        for (var recipe:recipes) {
            for (boolean accepted:List.of(true,false)) {
                var work=Files.createDirectory(output.resolve(recipe.code()+(accepted?"Accepted":"Rejected")));
                var source=recipe.javaSource(theme,accepted);
                String selected=theme.fill(accepted?recipe.allowed():recipe.rejected());
                assertTrue(source.contains("\n        "+selected+"\n"));
                var proposedLine=source.lines().takeWhile(line->!line.strip().equals(selected)).count()+1;
                compile(work,source,accepted,recipe.code().equals("G16"),proposedLine);
                if(accepted) {
                    String stdout=runReviewedClass(work);
                    assertFalse(stdout.isBlank(),recipe.code());
                    if(recipe.code().equals("G06")) assertEquals("通过视图读到：Feature",stdout.strip());
                    if(recipe.code().equals("G16")) {
                        assertTrue(stdout.contains("容器中实际保存：Note"));
                        assertTrue(stdout.contains("按 Feature 读取时发生 ClassCastException"));
                    }
                }
            }
        }
    }

    private void compile(Path work,String source,boolean accepted,boolean raw,long proposedLine) throws IOException {
        var sourceFile=Files.writeString(work.resolve("Main.java"),source);
        var compiler=ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler,"The course verification requires a Java 17+ JDK");
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)) {
            var verified=compiler.getTask(null,manager,diagnostics,List.of("--release","17","-proc:none","-Xlint:unchecked","-d",work.toString()),
                null,manager.getJavaFileObjectsFromPaths(List.of(sourceFile))).call();
            assertEquals(accepted,verified,()->source+"\n"+diagnostics.getDiagnostics());
        }
        var errors=diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).toList();
        if(!accepted) assertEquals(List.of(proposedLine),errors.stream().map(Diagnostic::getLineNumber).distinct().toList());
        if(accepted) {
            assertTrue(errors.isEmpty());
            var hasWarning=diagnostics.getDiagnostics().stream().anyMatch(d->d.getKind()==Diagnostic.Kind.MANDATORY_WARNING || d.getKind()==Diagnostic.Kind.WARNING);
            assertEquals(raw,hasWarning,"Only raw insertion should compile with a warning");
        }
    }

    /** Every source here is composed entirely from the repository's reviewed fixtures. */
    private String runReviewedClass(Path work) throws IOException,InterruptedException {
        var java=Path.of(System.getProperty("java.home"),"bin","java");
        Path log=work.resolve("output.txt");
        var process=new ProcessBuilder(java.toString(),"-Dfile.encoding=UTF-8","-cp",work.toString(),"Main")
            .redirectError(log.resolveSibling("errors.txt").toFile()).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(8,TimeUnit.SECONDS));
            assertEquals(0,process.exitValue(),()->work.toString());
            return Files.readString(log);
        } finally {process.destroyForcibly();}
    }
}
