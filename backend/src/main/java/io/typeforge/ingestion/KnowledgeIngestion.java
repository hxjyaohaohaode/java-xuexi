package io.typeforge.ingestion;
import java.util.UUID;
public interface KnowledgeIngestion {
    record ImportRequest(UUID editorId, UUID sourceId, String originalHash, String markdown, String processorVersion) {}
    UUID importDraft(ImportRequest request);
}
