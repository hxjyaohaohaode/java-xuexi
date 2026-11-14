package io.typeforge.ingestion;

import java.time.Instant;
import java.util.UUID;

/** Source and manual capture protocol; the publishing module consumes these immutable receipts. */
public final class IngestionModels {
    private IngestionModels() {}
    public record SourceInput(String title, String url, String edition, String usageNote) {}
    public record Source(UUID id, String title, String url, String edition, String usageNote, int captureCount) {}
    public record CaptureInput(String originalMarkdown, String cleanedMarkdown, String processingNote) {}
    public record Capture(UUID id, UUID sourceId, UUID authorId, String originalMarkdown, String cleanedMarkdown,
                          String originalHash, String cleanedHash, String processorVersion, String processingNote, Instant createdAt) {}
    public record SourceReference(UUID sourceId, String section, UUID ingestionId) {}
}
