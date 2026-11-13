package io.typeforge.operations;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.shared.JsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit opt-in demo only. Publishes code-reviewed fixtures, never student activity
 * or fabricated independent human reviews. Production publishing is a separate workflow.
 */
@Component
@Profile("demo")
public final class DemoContentInitializer implements ApplicationRunner {
    public static final String ORIGIN = "demo-authored-code-reviewed";
    public static final UUID RELEASE_ID = stableId("release:v1");
    public static final UUID AUTHOR_ID = stableId("disabled-system-author");
    private static final String VERSION = "demo-generics-v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final Clock clock;

    public DemoContentInitializer(JdbcTemplate jdbc, TransactionTemplate tx, JsonCodec json, Clock clock) {
        this.jdbc = jdbc; this.tx = tx; this.json = json; this.clock = clock;
    }
    @Override public void run(ApplicationArguments arguments) { initialize(); }

    public void initialize() {
        JsonNode concepts = read("knowledge/concepts.json");
        JsonNode sourceManifest = read("knowledge/sources.json");
        JsonNode content = read("knowledge/runtime-lessons.json");
        validate(content, concepts, sourceManifest);
        String manifestHash = json.hash(Map.of("concepts", concepts, "sources", sourceManifest, "content", content));
        try {
            tx.executeWithoutResult(status -> {
                if (alreadyInitialized(manifestHash)) return;
                var now = Timestamp.from(clock.instant());
                jdbc.update("INSERT INTO app_user(id,username,password_hash,roles,status,created_at) VALUES(?,?,?,?,?,?)",
                    AUTHOR_ID.toString(), "typeforge-demo-content-author", "DISABLED-NO-LOGIN-CREDENTIAL", "[]", "DISABLED", now);
                var conceptIds = new HashMap<String, UUID>();
                for (JsonNode concept : concepts) {
                    UUID id = UUID.fromString(concept.path("id").asText());
                    String code = concept.path("code").asText();
                    conceptIds.put(code, id);
                    var existing = jdbc.queryForList("SELECT code FROM concept WHERE id=?", String.class, id.toString());
                    if (existing.isEmpty()) jdbc.update("INSERT INTO concept(id,code,title) VALUES(?,?,?)", id.toString(), code, concept.path("title").asText());
                    else if (!existing.get(0).equals(code)) throw new IllegalStateException("Demo concept identity conflicts with existing course data");
                }
                var sourceIds = new HashMap<String, UUID>();
                for (JsonNode source : sourceManifest) addSource(source, sourceIds);
                for (JsonNode source : content.path("additionalSources")) addSource(source, sourceIds);
                jdbc.update("INSERT INTO content_release(id,version,status,manifest_hash,created_by,published_at,created_at) VALUES(?,?,?,?,?,?,?)",
                    RELEASE_ID.toString(), VERSION, "PUBLISHED", manifestHash, AUTHOR_ID.toString(), now, now);
                for (JsonNode concept : concepts) {
                    UUID id = conceptIds.get(concept.path("code").asText());
                    var prerequisites = new ArrayList<UUID>();
                    for (JsonNode prerequisite : concept.path("prerequisites")) prerequisites.add(conceptIds.get(prerequisite.asText()));
                    jdbc.update("INSERT INTO release_concept(release_id,concept_id,title,prerequisite_ids) VALUES(?,?,?,?)",
                        RELEASE_ID.toString(), id.toString(), concept.path("title").asText(), json.write(prerequisites));
                    for (UUID prerequisite : prerequisites) {
                        if (jdbc.queryForList("SELECT concept_id FROM concept_edge WHERE concept_id=? AND prerequisite_id=?", id.toString(), prerequisite.toString()).isEmpty()) {
                            jdbc.update("INSERT INTO concept_edge(concept_id,prerequisite_id) VALUES(?,?)", id.toString(), prerequisite.toString());
                        }
                    }
                }
                for (JsonNode lesson : content.path("lessons")) {
                    String code = lesson.path("conceptCode").asText();
                    UUID chunk = stableId("chunk:" + code + ":v1");
                    String body = "演示预置学习内容；origin=" + ORIGIN + "；未执行双人正式发布审核。目标Java版本17。\n\n" + lesson.path("body").asText();
                    jdbc.update("INSERT INTO knowledge_chunk(id,revision,concept_id,title,body,content_hash,status,author_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                        chunk.toString(), 1, conceptIds.get(code).toString(), lesson.path("title").asText(), body, json.sha256(body), "REVIEWED", AUTHOR_ID.toString(), now);
                    for (JsonNode source : lesson.path("sources")) {
                        jdbc.update("INSERT INTO chunk_source(chunk_id,chunk_revision,source_id,section_ref) VALUES(?,?,?,?)",
                            chunk.toString(), 1, sourceIds.get(source.path("sourceCode").asText()).toString(), source.path("section").asText());
                    }
                    jdbc.update("INSERT INTO release_chunk(release_id,chunk_id,chunk_revision) VALUES(?,?,?)", RELEASE_ID.toString(), chunk.toString(), 1);
                }
                for (JsonNode question : content.path("exercises")) {
                    UUID exercise = stableId("exercise:" + question.path("code").asText() + ":v1");
                    var answer = Map.of("optionIds", question.path("correctOptionIds"), "options", question.path("options"),
                        "explanation", question.path("explanation").asText(), "misconceptions", question.path("misconceptions"), "origin", ORIGIN);
                    jdbc.update("INSERT INTO exercise(id,revision,family_id,kind,difficulty,prompt,answer_spec,rubric_version,status,author_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        exercise.toString(), 1, exercise.toString(), question.path("kind").asText(), question.path("difficulty").asInt(), question.path("prompt").asText(),
                        json.write(answer), "choice-v1", "REVIEWED", AUTHOR_ID.toString(), now);
                    jdbc.update("INSERT INTO exercise_concept(exercise_id,exercise_revision,concept_id,weight) VALUES(?,?,?,?)",
                        exercise.toString(), 1, conceptIds.get(question.path("conceptCode").asText()).toString(), java.math.BigDecimal.ONE);
                    jdbc.update("INSERT INTO release_exercise(release_id,exercise_id,exercise_revision) VALUES(?,?,?)", RELEASE_ID.toString(), exercise.toString(), 1);
                }
                if (jdbc.queryForList("SELECT singleton_id FROM active_release WHERE singleton_id=1").isEmpty()) {
                    jdbc.update("INSERT INTO active_release(singleton_id,release_id,revision) VALUES(?,?,?)", 1, RELEASE_ID.toString(), 1);
                }
                jdbc.update("INSERT INTO audit_event(id,actor_id,action,resource_id,trace_id,safe_metadata,created_at) VALUES(?,?,?,?,?,?,?)",
                    stableId("audit:v1").toString(), AUTHOR_ID.toString(), "DEMO_CONTENT_INITIALIZED", RELEASE_ID.toString(), "demo-content-v1",
                    json.write(Map.of("origin", ORIGIN, "humanReview", false, "concepts", 16, "lessons", 16, "exercises", content.path("exercises").size(),
                        "formalPublicationPipeline", false)), now);
            });
        } catch (DuplicateKeyException collision) {
            // A simultaneous demo startup may have completed the same atomic initialization.
            if (!alreadyInitialized(manifestHash)) throw collision;
        }
    }

    private boolean alreadyInitialized(String hash) {
        var releases = jdbc.queryForList("SELECT manifest_hash FROM content_release WHERE id=?", String.class, RELEASE_ID.toString());
        if (releases.isEmpty()) return false;
        if (!hash.equals(releases.get(0))) throw new IllegalStateException("Demo content changed: create a new version instead of overwriting published content");
        return true;
    }

    private void addSource(JsonNode source, Map<String, UUID> sources) {
        String code = source.path("code").asText();
        UUID id = source.has("id") ? UUID.fromString(source.path("id").asText()) : stableId("source:" + code);
        sources.put(code, id);
        if (!jdbc.queryForList("SELECT id FROM knowledge_source WHERE id=?", id.toString()).isEmpty()) return;
        jdbc.update("INSERT INTO knowledge_source(id,title,url,edition,usage_note,raw_hash,collected_at) VALUES(?,?,?,?,?,?,?)",
            id.toString(), source.path("title").asText(), source.path("url").asText(), source.path("version").asText(),
            "Links and original explanations only; no full text collected. origin=" + ORIGIN + "; humanReview=false.", null, null);
    }

    private JsonNode read(String resource) {
        try { return json.tree(new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new IllegalStateException("Required demo course resource is missing", e); }
    }

    private static void validate(JsonNode content, JsonNode concepts, JsonNode sources) {
        if (!ORIGIN.equals(content.path("origin").asText()) || content.path("formalHumanReview").asBoolean(true)
            || content.path("javaRelease").asInt() != 17 || concepts.size() != 16 || content.path("lessons").size() != 16) {
            throw new IllegalStateException("Invalid demo course provenance or coverage");
        }
        var codes = new HashSet<String>();
        concepts.forEach(c -> codes.add(c.path("code").asText()));
        var sourceCodes = new HashSet<String>();
        sources.forEach(s -> sourceCodes.add(s.path("code").asText()));
        content.path("additionalSources").forEach(s -> sourceCodes.add(s.path("code").asText()));
        var covered = new HashSet<String>();
        for (JsonNode lesson : content.path("lessons")) {
            String code = lesson.path("conceptCode").asText();
            if (!codes.contains(code) || !covered.add(code) || lesson.path("body").asText().isBlank() || lesson.path("sources").isEmpty()) {
                throw new IllegalStateException("Demo lesson coverage or evidence is invalid");
            }
            for (JsonNode source : lesson.path("sources")) if (!sourceCodes.contains(source.path("sourceCode").asText())) {
                throw new IllegalStateException("Demo lesson contains an unresolved source");
            }
        }
        int count = content.path("exercises").size();
        if (count < 8 || count > 12) throw new IllegalStateException("Demo exercise count must be 8..12");
        var questionCodes = new HashSet<String>();
        for (JsonNode exercise : content.path("exercises")) {
            if (!questionCodes.add(exercise.path("code").asText()) || !codes.contains(exercise.path("conceptCode").asText())
                || !"SINGLE".equals(exercise.path("kind").asText()) || exercise.path("correctOptionIds").size() != 1) {
                throw new IllegalStateException("Demo exercise identity or answer type is invalid");
            }
            var options = new HashSet<String>();
            for (JsonNode option : exercise.path("options")) if (!options.add(option.path("id").asText()) || option.path("text").asText().isBlank()) {
                throw new IllegalStateException("Demo exercise options are invalid");
            }
            if (options.size() < 2 || !options.contains(exercise.path("correctOptionIds").get(0).asText())) {
                throw new IllegalStateException("Demo exercise answer does not match its public options");
            }
        }
    }

    public static UUID stableId(String name) {
        return UUID.nameUUIDFromBytes(("typeforge:demo:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
