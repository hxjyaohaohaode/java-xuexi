package io.typeforge.retrieval;

import io.typeforge.curriculum.CurriculumService;
import io.typeforge.curriculum.CurriculumService.EvidenceView;
import io.typeforge.curriculum.CurriculumService.PublishedChunk;
import io.typeforge.shared.DomainException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Bounded, exact lexical BM25 over published SQL cards; no model fallback or forced nonempty results. */
@Service
public final class LocalKnowledgeSearch implements KnowledgeRetriever {
    public static final String INDEX_VERSION = "bm25-script-boundaries-v2";
    // Latin/other identifiers must stop before Han: PECS用于 must index both pecs and 用于.
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]+|[\\p{L}_$&&[^\\p{IsHan}]][\\p{L}\\p{N}_$&&[^\\p{IsHan}]]*|\\?");
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    public record SearchResult(UUID releaseId, String indexVersion, List<EvidenceView> items, boolean insufficientEvidence) {}
    private record Document(PublishedChunk card, Map<String, Integer> terms, int length) {}
    private final CurriculumService curriculum;

    public LocalKnowledgeSearch(CurriculumService curriculum) { this.curriculum = curriculum; }

    public SearchResult search(UUID releaseId, String text, UUID conceptId, int topK) {
        return searchPublished(releaseId, text, conceptId == null ? List.of() : List.of(conceptId), topK);
    }

    @Override public List<Evidence> search(Query query) {
        if (query == null) throw DomainException.invalid("A knowledge query is required");
        return searchPublished(query.releaseId(), query.text(), query.conceptIds(), query.topK()).items().stream()
            .map(e -> new Evidence(e.chunkId(), e.revision(), e.releaseId(), e.title(), e.excerpt(), URI.create(e.source().url()), e.rankScore())).toList();
    }

    private SearchResult searchPublished(UUID releaseId, String text, List<UUID> concepts, int topK) {
        if (text == null || text.isBlank() || text.length() > 500 || topK < 1 || topK > 8) {
            throw DomainException.invalid("Query must contain 1..500 characters and topK must be 1..8");
        }
        // Validate release and source integrity even for a query that has no usable tokens.
        List<PublishedChunk> cards = curriculum.chunks(releaseId, concepts);
        var queryTerms = frequencies(text).keySet();
        if (queryTerms.isEmpty() || cards.isEmpty()) return new SearchResult(releaseId, INDEX_VERSION, List.of(), true);
        var docs = new ArrayList<Document>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0;
        for (var card : cards) {
            var counts = frequencies(card.title() + " " + card.title() + " " + card.body());
            int length = counts.values().stream().mapToInt(Integer::intValue).sum();
            docs.add(new Document(card, counts, length));
            counts.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
            totalLength += length;
        }
        double averageLength = Math.max(1.0, totalLength / docs.size());
        var hits = new ArrayList<EvidenceView>();
        for (var doc : docs) {
            double score = 0;
            for (String term : queryTerms) {
                int frequency = doc.terms().getOrDefault(term, 0);
                if (frequency == 0) continue;
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1.0 + (docs.size() - df + 0.5) / (df + 0.5));
                score += idf * frequency * (K1 + 1) / (frequency + K1 * (1 - B + B * doc.length() / averageLength));
            }
            if (score > 0) hits.add(CurriculumService.evidence(doc.card(), score));
        }
        hits.sort(Comparator.comparingDouble(EvidenceView::rankScore).reversed().thenComparing(e -> e.chunkId().toString()));
        var result = hits.stream().limit(topK).toList();
        return new SearchResult(releaseId, INDEX_VERSION, result, result.isEmpty());
    }

    private static Map<String, Integer> frequencies(String text) {
        var result = new LinkedHashMap<String, Integer>();
        var matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String value = matcher.group();
            int[] points = value.codePoints().toArray();
            if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN) {
                if (points.length == 1) result.merge(value, 1, Integer::sum);
                else for (int i = 0; i + 1 < points.length; i++) result.merge(new String(points, i, 2), 1, Integer::sum);
            } else result.merge(value, 1, Integer::sum);
        }
        return result;
    }
}
