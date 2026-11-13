package io.typeforge.publishing;
import java.util.UUID;
public interface ContentPublisher {
    record PublishCommand(UUID releaseId, UUID editorId, String expectedManifestHash) {}
    // Requires independent review, READY index and atomic active-release pointer update.
    void publish(PublishCommand command);
}
