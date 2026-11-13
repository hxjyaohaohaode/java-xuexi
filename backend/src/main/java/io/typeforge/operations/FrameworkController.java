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
    public FrameworkController(JdbcTemplate jdbc,JsonCodec json) throws IOException {
        this.jdbc=jdbc;
        JsonNode source=json.tree(new ClassPathResource("contracts/modules.json").getContentAsString(StandardCharsets.UTF_8));
        var values=new ArrayList<Module>();
        source.forEach(m->values.add(new Module(m.path("id").asText(),m.path("name").asText(),m.path("owner").asText(),m.path("status").asText())));
        modules=List.copyOf(values);
    }
    @GetMapping("/api/v1/health")
    public Health health() {
        jdbc.queryForObject("SELECT 1",Integer.class);
        return new Health("UP", "process-and-database; model/compiler/media not configured");
    }
    @GetMapping("/api/v1/framework")
    public Framework framework() {
        return new Framework("durable-learning-runtime", false, modules);
    }
    @GetMapping("/api/v1/capabilities")
    public Map<String,Object> capabilities() {
        return Map.of("runtimeVersion","0.3","businessReady",false,
            "enabled",List.of("SESSION_AUTH","PUBLISHED_COURSE_READ","BM25_RETRIEVAL","CHOICE_GRADING","ORDERED_EVENTS","OUTBOX_PROJECTIONS","CONTEXT_AND_REVOCATION","ARTIFACT_DSL_VERSIONING","NOTEBOOK_SOURCE","LOCAL_EVIDENCE_WORKFLOW","DURABLE_DAG_CONTROL"),
            "unavailable",List.of("LLM_PLANNING","LLM_TUTOR","UNTRUSTED_CODE_EXECUTION","MEDIA_GENERATION","BINARY_EXPORT","FRONTEND","EDITORIAL_PUBLISHING"),
            "learningEvidence","Only server-graded first attempts from distinct families contribute independent samples",
            "localWorkflow","Deterministic evidence retrieval; no model call and no invented natural-language answer");
    }
}
