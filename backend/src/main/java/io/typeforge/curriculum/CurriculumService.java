package io.typeforge.curriculum;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.exercise.TextRubric;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads immutable release membership. No public exercise record contains an answer specification. */
@Service
public final class CurriculumService {
    public record SourceView(UUID id, String title, String url, String edition, String usageNote) {}
    public record SourceLocator(SourceView source, String section) {}
    public record PublishedChunk(UUID chunkId, int revision, UUID releaseId, UUID conceptId,
                                 String title, String body, String contentHash, List<SourceLocator> sources) {
        public PublishedChunk { sources = List.copyOf(sources); }
    }
    public record EvidenceView(UUID chunkId, int revision, UUID releaseId, String title, String excerpt,
                               SourceView source, double rankScore, String contentHash, UUID conceptId,
                               String sourceSection, @JsonInclude(JsonInclude.Include.NON_NULL) String fullText) {}
    public record ConceptView(UUID id, String code, String title, UUID releaseId,
                              List<UUID> prerequisiteIds, List<EvidenceView> evidence,
                              List<UUID> relatedExerciseIds, CourseCompanions.Supplement companion) {}
    public record OptionView(String id, String text) {}
    public record ExerciseView(UUID id, int revision, UUID releaseId, String kind, String prompt,
                               int difficulty, List<UUID> conceptIds, List<OptionView> options,String answerKind,TextRubric.Policy textPolicy) {}
    public record ExercisePage(List<ExerciseView> items, String nextCursor) {}

    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final CourseCompanions companions;
    public CurriculumService(JdbcTemplate jdbc, JsonCodec json, CourseCompanions companions) {
        this.jdbc = jdbc;
        this.json = json;
        this.companions = companions;
    }

    public UUID activeRelease() {
        var values = jdbc.query("SELECT r.id FROM active_release a JOIN content_release r ON r.id=a.release_id WHERE a.singleton_id=1 AND r.status='PUBLISHED'",
            (rs, n) -> UUID.fromString(rs.getString(1)));
        if (values.isEmpty()) throw new DomainException("COURSE_NOT_CONFIGURED", 503, "No published course release is active");
        return values.get(0);
    }

    public void requirePublished(UUID releaseId) {
        if (releaseId == null || jdbc.queryForList("SELECT id FROM content_release WHERE id=? AND status='PUBLISHED'", releaseId.toString()).isEmpty()) {
            throw DomainException.missing();
        }
    }

    public List<ConceptView> concepts(UUID releaseId) {
        return readConcepts(releaseId, null);
    }

    private List<ConceptView> readConcepts(UUID releaseId, UUID selected) {
        List<UUID> conceptIds = selected == null ? List.of() : List.of(selected);
        // A single lesson can return its complete, hash-verified text. The catalogue stays compact.
        Map<UUID, List<EvidenceView>> evidenceByConcept = new LinkedHashMap<>();
        for (var card : chunks(releaseId, conceptIds)) {
            evidenceByConcept.computeIfAbsent(card.conceptId(), ignored -> new ArrayList<>()).add(evidence(card, 0, selected != null));
        }
        var exercises = relatedExercises(releaseId);
        String filter = selected == null ? "" : " AND c.id=?";
        Object[] args = selected == null ? new Object[] {releaseId.toString()} : new Object[] {releaseId.toString(), selected.toString()};
        return jdbc.query("SELECT c.id,c.code,rc.title,rc.prerequisite_ids FROM release_concept rc JOIN concept c ON c.id=rc.concept_id WHERE rc.release_id=?" + filter + " ORDER BY c.code",
            (rs, n) -> {
                UUID id = UUID.fromString(rs.getString("id"));
                return new ConceptView(id, rs.getString("code"), rs.getString("title"), releaseId,
                    uuidList(json.tree(rs.getObject("prerequisite_ids"))),
                    evidenceByConcept.getOrDefault(id, List.of()), exercises.getOrDefault(id, List.of()), companions.forConcept(id));
            }, args);
    }

    public ConceptView concept(UUID releaseId, UUID conceptId) {
        return readConcepts(releaseId, conceptId).stream().findFirst().orElseThrow(DomainException::missing);
    }

    public ExercisePage exercises(UUID releaseId, UUID conceptId, String cursor, int limit) {
        requirePublished(releaseId);
        if (limit < 1 || limit > 100) throw DomainException.invalid("Limit must be 1..100");
        String after = "";
        if (cursor != null && !cursor.isBlank()) {
            try { after = UUID.fromString(cursor).toString(); }
            catch (IllegalArgumentException e) { throw DomainException.invalid("Invalid exercise cursor"); }
        }
        String filter = conceptId == null ? "" : " AND EXISTS (SELECT 1 FROM exercise_concept ec WHERE ec.exercise_id=e.id AND ec.exercise_revision=e.revision AND ec.concept_id=?)";
        var arguments = new ArrayList<Object>(List.of(releaseId.toString(), after));
        if (conceptId != null) arguments.add(conceptId.toString());
        arguments.add(limit + 1);
        var rows = jdbc.query("SELECT e.id,e.revision FROM release_exercise re JOIN exercise e ON e.id=re.exercise_id AND e.revision=re.exercise_revision WHERE re.release_id=? AND e.id>? AND e.status='REVIEWED'" + filter + " ORDER BY e.id LIMIT ?",
            (rs, n) -> Map.entry(UUID.fromString(rs.getString(1)), rs.getInt(2)), arguments.toArray());
        boolean more = rows.size() > limit;
        var page = rows.stream().limit(limit).map(r -> exercise(releaseId, r.getKey(), r.getValue())).toList();
        return new ExercisePage(page, more ? page.get(page.size() - 1).id().toString() : null);
    }

    public ExerciseView exercise(UUID releaseId, UUID exerciseId, int revision) {
        requirePublished(releaseId);
        if (exerciseId == null || revision < 1) throw DomainException.invalid("Valid exercise and revision are required");
        var rows = jdbc.query("SELECT e.id,e.revision,e.kind,e.prompt,e.difficulty,e.answer_spec FROM release_exercise re JOIN exercise e ON e.id=re.exercise_id AND e.revision=re.exercise_revision WHERE re.release_id=? AND e.id=? AND e.revision=? AND e.status='REVIEWED'",
            (rs, n) -> {
                JsonNode answer = json.tree(rs.getObject("answer_spec"));
                var options = new ArrayList<OptionView>();
                JsonNode rawOptions = answer.path("options");
                if (rawOptions.isArray()) for (JsonNode option : rawOptions) {
                    // Explicitly select public fields. Never serialize the source JSON or arbitrary option metadata.
                    options.add(new OptionView(option.path("id").asText(), option.path("text").asText()));
                }
                var concepts = jdbc.query("SELECT concept_id FROM exercise_concept WHERE exercise_id=? AND exercise_revision=? ORDER BY concept_id",
                    (cr, i) -> UUID.fromString(cr.getString(1)), exerciseId.toString(), revision);
                String kind=rs.getString("kind");
                boolean text=TextRubric.configured(answer);
                if(text && !TextRubric.supports(kind)) throw new DomainException("INVALID_PUBLISHED_EXERCISE",503,"Short-answer policy does not match the published exercise kind");
                return new ExerciseView(exerciseId, revision, releaseId, kind, rs.getString("prompt"), rs.getInt("difficulty"), concepts, List.copyOf(options),
                    text?"TEXT":java.util.Set.of("SIGNATURE","REFACTOR","FILL").contains(kind)?"CODE":"CHOICE",text?TextRubric.policy(TextRubric.parse(answer)):null);
            }, releaseId.toString(), exerciseId.toString(), revision);
        if (rows.isEmpty()) throw DomainException.missing();
        return rows.get(0);
    }

    public List<PublishedChunk> chunks(UUID releaseId, List<UUID> conceptIds) {
        requirePublished(releaseId);
        if (conceptIds == null || conceptIds.size() > 16 || conceptIds.stream().anyMatch(java.util.Objects::isNull)) throw DomainException.invalid("Invalid concept filters");
        var args = new ArrayList<Object>();
        args.add(releaseId.toString());
        String filter = "";
        if (!conceptIds.isEmpty()) {
            filter = " AND k.concept_id IN (" + String.join(",", java.util.Collections.nCopies(conceptIds.size(), "?")) + ")";
            conceptIds.forEach(id -> args.add(id.toString()));
        }
        var rows = jdbc.query("SELECT k.id,k.revision,k.concept_id,k.title,k.body,k.content_hash FROM release_chunk rc JOIN knowledge_chunk k ON k.id=rc.chunk_id AND k.revision=rc.chunk_revision JOIN release_concept cc ON cc.release_id=rc.release_id AND cc.concept_id=k.concept_id WHERE rc.release_id=? AND k.status='REVIEWED'" + filter + " ORDER BY k.id LIMIT 2001",
            (rs, n) -> new PublishedChunk(UUID.fromString(rs.getString("id")), rs.getInt("revision"), releaseId,
                UUID.fromString(rs.getString("concept_id")), rs.getString("title"), rs.getString("body"), rs.getString("content_hash"), List.of()), args.toArray());
        if (rows.size() > 2000) throw new DomainException("INDEX_CAPACITY_EXCEEDED", 503, "This corpus requires a dedicated search index");
        Map<String, List<SourceLocator>> sources = new LinkedHashMap<>();
        jdbc.query("SELECT cs.chunk_id,cs.chunk_revision,cs.section_ref,s.id,s.title,s.url,s.edition,s.usage_note FROM release_chunk rc JOIN chunk_source cs ON cs.chunk_id=rc.chunk_id AND cs.chunk_revision=rc.chunk_revision JOIN knowledge_source s ON s.id=cs.source_id WHERE rc.release_id=? ORDER BY cs.chunk_id,s.id",
            rs -> {
                String key = rs.getString("chunk_id") + ":" + rs.getInt("chunk_revision");
                sources.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new SourceLocator(new SourceView(UUID.fromString(rs.getString("id")), rs.getString("title"), rs.getString("url"), rs.getString("edition"), rs.getString("usage_note")), rs.getString("section_ref")));
            }, releaseId.toString());
        var result = new ArrayList<PublishedChunk>();
        for (var card : rows) {
            var refs = sources.getOrDefault(card.chunkId() + ":" + card.revision(), List.of());
            if (refs.isEmpty() || !json.sha256(card.body()).equals(card.contentHash())) {
                throw new DomainException("CONTENT_INTEGRITY_FAILURE", 503, "Published knowledge evidence is incomplete or changed");
            }
            result.add(new PublishedChunk(card.chunkId(), card.revision(), releaseId, card.conceptId(), card.title(), card.body(), card.contentHash(), refs));
        }
        return List.copyOf(result);
    }

    public static EvidenceView evidence(PublishedChunk card, double score) {
        return evidence(card, score, false);
    }

    private static EvidenceView evidence(PublishedChunk card, double score, boolean includeFullText) {
        var source = card.sources().get(0);
        String body = card.body();
        int end = Math.min(body.length(), 3000);
        if (end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) end--;
        String excerpt = body.substring(0, end);
        return new EvidenceView(card.chunkId(), card.revision(), card.releaseId(), card.title(), excerpt,
            source.source(), score, card.contentHash(), card.conceptId(), source.section(), includeFullText && end < body.length() ? body : null);
    }

    private Map<UUID, List<UUID>> relatedExercises(UUID release) {
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        jdbc.query("""
            SELECT concept_id, id FROM (
                SELECT ec.concept_id, e.id,
                       ROW_NUMBER() OVER (PARTITION BY ec.concept_id ORDER BY e.id) AS position_in_concept
                FROM release_exercise re
                JOIN exercise e ON e.id = re.exercise_id AND e.revision = re.exercise_revision
                JOIN exercise_concept ec ON ec.exercise_id = e.id AND ec.exercise_revision = e.revision
                WHERE re.release_id = ? AND e.status = 'REVIEWED'
            ) ranked WHERE position_in_concept <= 20 ORDER BY concept_id, id
            """, rs -> {
                UUID concept = UUID.fromString(rs.getString("concept_id"));
                result.computeIfAbsent(concept, ignored -> new ArrayList<>()).add(UUID.fromString(rs.getString("id")));
            }, release.toString());
        return result;
    }

    private static List<UUID> uuidList(JsonNode value) {
        var result = new ArrayList<UUID>();
        for (var item : value) result.add(UUID.fromString(item.asText()));
        return List.copyOf(result);
    }
}
