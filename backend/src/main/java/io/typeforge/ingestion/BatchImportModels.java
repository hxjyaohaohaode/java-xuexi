package io.typeforge.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A private editor preview is distinct from an immutable capture and from published knowledge. */
public final class BatchImportModels {
    private BatchImportModels() {}
    public record Item(UUID id,String fileName,String mediaType,int byteSize,String fileHash,String originalHash,
                       String parser,String status,String errorCode,String message,String originalMarkdown,
                       String cleanedMarkdown,UUID captureId) {}
    public record Batch(UUID id,UUID sourceId,UUID ownerId,int revision,String status,String processingNote,
                        Instant createdAt,Instant expiresAt,List<Item> items) {}
    public record Summary(UUID id,UUID sourceId,int revision,String status,String processingNote,
                          Instant createdAt,Instant expiresAt,int itemCount,int readyCount,int failedCount,int committedCount) {}
    public record Selection(UUID itemId,String cleanedMarkdown,String processingNote) {}
    public record CommitInput(int expectedRevision,List<Selection> items) {}
    public record CancelInput(int expectedRevision) {}
}
