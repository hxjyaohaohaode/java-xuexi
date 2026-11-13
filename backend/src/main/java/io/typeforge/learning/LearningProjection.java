package io.typeforge.learning;
import java.time.Instant;
import java.util.UUID;
public interface LearningProjection {
    record AttemptGraded(UUID eventId, UUID userId, UUID attemptId, UUID releaseId, Instant occurredAt) {}
    // Receipt and projection update must commit in one database transaction.
    void accept(AttemptGraded event);
}
