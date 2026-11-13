package io.typeforge.retrieval;
import java.net.URI;
import java.util.List;
import java.util.UUID;
public interface KnowledgeRetriever {
    record Query(String text, UUID releaseId, List<UUID> conceptIds, int topK) {
        public Query {
            conceptIds = List.copyOf(conceptIds);
            if (topK < 1 || topK > 8) throw new IllegalArgumentException("topK must be 1..8");
        }
    }
    record Evidence(UUID chunkId, int revision, UUID releaseId, String title, String excerpt, URI source, double rankScore) {}
    List<Evidence> search(Query query);
}
