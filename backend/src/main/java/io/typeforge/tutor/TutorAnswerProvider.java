package io.typeforge.tutor;

import io.typeforge.curriculum.CurriculumService.EvidenceView;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed boundary: the provider receives only admitted learner context and published evidence. */
public interface TutorAnswerProvider {
    enum Purpose { TUTOR, PLANNER, CONCEPT, EXAMPLE, REPAIR, REVIEW }
    record Prompt(UUID requestId,String question,int hintLevel,List<String> recentQuestions,
                  List<Map<String,Object>> learnerContext,List<EvidenceView> evidence,Purpose purpose,List<StudyContextJournal.Incoming> handoffs) {
        public Prompt { recentQuestions=List.copyOf(recentQuestions);learnerContext=List.copyOf(learnerContext);evidence=List.copyOf(evidence);handoffs=List.copyOf(handoffs); }
    }
    record Usage(long promptTokens,long completionTokens,long totalTokens) {}
    record Answer(String text,List<UUID> citedChunkIds,String limitations,Usage usage,String providerModelId,Map<String,String> reviewEdits) {
        public Answer(String text,List<UUID> citedChunkIds,String limitations,Usage usage,String providerModelId) {
            this(text,citedChunkIds,limitations,usage,providerModelId,Map.of());
        }
        public Answer { citedChunkIds=List.copyOf(citedChunkIds);reviewEdits=Map.copyOf(reviewEdits); }
        public Answer(String text,List<UUID> citedChunkIds,String limitations,Usage usage) { this(text,citedChunkIds,limitations,usage,null); }
    }
    final class Failure extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final String code;
        private final transient Usage usage;
        public Failure(String code) { this(code,null); }
        public Failure(String code,Usage usage) { super(code);this.code=code;this.usage=usage; }
        public String code() { return code; }
        public Usage usage() { return usage; }
    }
    Answer answer(Prompt prompt);
}
