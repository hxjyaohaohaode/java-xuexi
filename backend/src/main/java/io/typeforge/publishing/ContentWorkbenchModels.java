package io.typeforge.publishing;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.ingestion.IngestionModels.SourceReference;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Editorial DTOs are never returned from learner endpoints. */
public final class ContentWorkbenchModels {
    private ContentWorkbenchModels() {}
    public record ConceptInput(UUID id, String code, String title, List<UUID> prerequisiteIds) {}
    public record Concept(UUID id, String code, String title, List<UUID> prerequisiteIds) {}
    public record ContentRef(UUID id, int revision) {}
    public record ChunkInput(UUID id, int revision, int baseRevision, UUID conceptId, String title, String body,
                             List<SourceReference> sources) {}
    public record Chunk(UUID id, int revision, UUID conceptId, String title, String body, String contentHash,
                        String reviewHash, String status, UUID authorId, List<SourceReference> sources) {}
    public record ChoiceOption(String id, String text) {}
    public record ChoiceAnswer(List<ChoiceOption> options, List<String> optionIds, String explanation, List<String> misconceptions) {}
    public record ExerciseInput(UUID id, int revision, int baseRevision, UUID familyId, String kind, int difficulty,
                                String prompt, List<UUID> conceptIds, ChoiceAnswer answerSpec, List<ContentRef> knowledgeRefs) {}
    public record Exercise(UUID id, int revision, UUID familyId, String kind, int difficulty, String prompt,
                           List<UUID> conceptIds, ChoiceAnswer answerSpec, List<ContentRef> knowledgeRefs,
                           String reviewHash, String status, UUID authorId) {}
    public record ReviewInput(String targetKind, UUID targetId, int targetRevision, String expectedContentHash,
                              String decision, String note) {}
    public record Review(UUID id, String targetKind, UUID targetId, int targetRevision, UUID authorId, UUID reviewerId,
                         String reviewedHash, String decision, String note, Instant createdAt) {}
    public record ReleaseInput(String version, List<ContentRef> chunks, List<ContentRef> exercises) {}
    public record PublishInput(String expectedManifestHash, int expectedActiveRevision) {}
    public record Release(UUID id, String version, String status, String manifestHash, UUID createdBy, Instant publishedAt,
                          JsonNode manifest, String indexStatus, String indexVersion, boolean active, int activeRevision) {}
    public record DraftSummary(UUID id, int revision, String title, String status, UUID authorId) {}
}
