package io.typeforge.artifacts;
import java.util.UUID;
public interface ArtifactStudio<T> {
    record Revision<T>(UUID artifactId, int revision, String schemaVersion, T content, String contentHash) {}
    Revision<T> loadOwn(UUID ownerId, UUID artifactId, int revision);
    Revision<T> saveOwn(UUID ownerId, UUID artifactId, int expectedRevision, T content);
}
