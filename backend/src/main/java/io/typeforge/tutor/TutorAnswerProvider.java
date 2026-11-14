package io.typeforge.tutor;

import io.typeforge.curriculum.CurriculumService.EvidenceView;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed boundary: the provider receives only admitted learner context and published evidence. */
public interface TutorAnswerProvider {
    record Prompt(UUID requestId,String question,int hintLevel,List<String> recentQuestions,
                  List<Map<String,Object>> learnerContext,List<EvidenceView> evidence) {
        public Prompt { recentQuestions=List.copyOf(recentQuestions);learnerContext=List.copyOf(learnerContext);evidence=List.copyOf(evidence); }
    }
    record Usage(long promptTokens,long completionTokens,long totalTokens) {}
    record Answer(String text,List<UUID> citedChunkIds,String limitations,Usage usage,String providerModelId) {
        public Answer { citedChunkIds=List.copyOf(citedChunkIds); }
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
