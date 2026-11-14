package io.typeforge.publishing;
import java.util.UUID;
public interface ContentPublisher {
    record PublishCommand(UUID releaseId, UUID editorId, String expectedManifestHash,
                          int expectedActiveRevision, String idempotencyKey) {}
    // Requires independent review, a validated SQL corpus and a CAS-protected active pointer.
    void publish(PublishCommand command);
}
