package io.typeforge.tutor;
import java.util.List;
import java.util.UUID;
import io.typeforge.retrieval.KnowledgeRetriever;
public interface TutorGateway {
    record Request(UUID userId, UUID conversationId, UUID releaseId, String question, int hintLevel,
                   List<KnowledgeRetriever.Evidence> evidence) {
        public Request { evidence = List.copyOf(evidence); }
    }
    enum Status { COMPLETED, INSUFFICIENT_EVIDENCE, FAILED }
    record Answer(Status status, String text, List<UUID> citedChunkIds, List<UUID> suggestedExerciseIds, String limitation) {
        public Answer { citedChunkIds = List.copyOf(citedChunkIds); suggestedExerciseIds = List.copyOf(suggestedExerciseIds); }
    }
    Answer answer(Request request);
}
