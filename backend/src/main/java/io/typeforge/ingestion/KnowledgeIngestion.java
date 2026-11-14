package io.typeforge.ingestion;
import java.util.UUID;
public interface KnowledgeIngestion {
    /** Identity is resolved from the session by the HTTP adapter; hashes are calculated on the server. */
    record ImportRequest(UUID editorId, UUID sourceId, String originalMarkdown, String cleanedMarkdown,
                         String processingNote, String idempotencyKey) {}
    /** Returns a captured source receipt, never an automatically approved knowledge card. */
    UUID importDraft(ImportRequest request);
}
