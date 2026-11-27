package io.typeforge.operations;
import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.shared.JsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class FrameworkController {
    public record Health(String status, String scope) {}
    public record Module(String id, String name, String owner, String status) {}
    public record Framework(String phase, boolean businessReady, List<Module> modules) {}
    private final JdbcTemplate jdbc;
    private final List<Module> modules;
    private final List<RuntimeCapabilityProvider> adapters;
    public FrameworkController(JdbcTemplate jdbc,JsonCodec json,List<RuntimeCapabilityProvider> adapters) throws IOException {
        this.jdbc=jdbc;this.adapters=List.copyOf(adapters);
        JsonNode source=json.tree(new ClassPathResource("contracts/modules.json").getContentAsString(StandardCharsets.UTF_8));
        var values=new ArrayList<Module>();
        source.forEach(m->values.add(new Module(m.path("id").asText(),m.path("name").asText(),m.path("owner").asText(),m.path("status").asText())));
        modules=List.copyOf(values);
    }
    @GetMapping("/api/v1/health")
    public Health health() {
        jdbc.queryForObject("SELECT 1",Integer.class);
        return new Health("UP", "process-and-database; external adapters are reported separately by capabilities");
    }
    @GetMapping("/api/v1/framework")
    public Framework framework() {
        return new Framework("connected-learning-studio", false, modules);
    }
    @GetMapping("/api/v1/capabilities")
    public Map<String,Object> capabilities() {
        var enabled=new ArrayList<>(List.of("SESSION_AUTH","PUBLISHED_COURSE_READ","BM25_RETRIEVAL","CHOICE_GRADING","EXERCISE_HINTS","ORDERED_EVENTS","OUTBOX_PROJECTIONS","CONTEXT_AND_REVOCATION","ARTIFACT_DSL_VERSIONING","NOTEBOOK_SOURCE","LOCAL_EVIDENCE_WORKFLOW","DURABLE_DAG_CONTROL","USER_PREFERENCES","READING_EVENTS","FINITE_TYPE_LAB","NOTEBOOK_ACTIONS","STATIC_EXPORT","TUTOR_EVIDENCE_ONLY","EDITORIAL_PUBLISHING","MANUAL_INGESTION","OFFICE_PDF_EXPORT","ACCOUNT_LIFECYCLE","LEARNING_ANALYTICS","READING_HISTORY","OPERATIONS_METRICS"));
        var unavailable=new ArrayList<>(List.of("MEDIA_GENERATION"));
        adapters.stream().flatMap(adapter->adapter.enabledCapabilities().stream()).sorted().distinct().filter(capability->!enabled.contains(capability)).forEach(enabled::add);
        for(String capability:List.of("TUTOR_MODEL","LLM_PLANNING","COMPILE_ONLY","CODE_GRADING","PRIVATE_ASSETS","UNTRUSTED_CODE_EXECUTION")) if(!enabled.contains(capability)) unavailable.add(capability);
        if(!enabled.contains("TUTOR_MODEL")) unavailable.add("LLM_TUTOR");
        if(new ClassPathResource("static/index.html").exists()) enabled.add("FRONTEND");else unavailable.add("FRONTEND");
        return Map.of("runtimeVersion","0.6","businessReady",false,
            "enabled",List.copyOf(enabled),"unavailable",List.copyOf(unavailable),
            "learningEvidence","Only server-graded first attempts from distinct families contribute independent samples",
            "localWorkflow","Evidence workflow is deterministic. Tutor EVIDENCE_ONLY and configured MODEL are separate request modes.");
    }
}
