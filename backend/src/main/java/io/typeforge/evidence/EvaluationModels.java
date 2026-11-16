package io.typeforge.evidence;

import io.typeforge.curriculum.CurriculumService.SourceLocator;
import io.typeforge.curriculum.CurriculumService.SourceView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private, user-labelled retrieval benchmarks. No DTO contains exercise answers or human contribution claims. */
public final class EvaluationModels {
    private EvaluationModels() {}
    public record CaseInput(String key,String query,UUID conceptId,String expectation,List<UUID> expectedChunkIds,String note) {}
    public record SuiteInput(String title,String description,List<CaseInput> cases) {}
    public record RevisionInput(int expectedRevision,String title,String description,List<CaseInput> cases) {}
    public record ArchiveInput(int expectedRevision) {}
    public record RunInput(int suiteRevision,int topK) {}
    public record ChunkReference(UUID chunkId,int revision,UUID conceptId,String title,String contentHash) {}
    public record CorpusEntry(UUID chunkId,int revision,UUID conceptId,String title,String contentHash,List<SourceLocator> sources) {}
    public record CaseView(String key,String query,UUID conceptId,String expectation,List<ChunkReference> expectedChunks,String note) {}
    public record Snapshot(UUID releaseId,String title,String description,List<CaseView> cases,String releaseManifestHash,String corpusHash,String indexVersion) {}
    public record SuiteSummary(UUID id,UUID workspaceId,UUID releaseId,String title,String description,int revision,String status,int caseCount,String contentHash,String corpusHash,Instant createdAt,Instant updatedAt) {}
    public record Suite(UUID id,UUID workspaceId,UUID releaseId,String title,String description,int revision,String status,int caseCount,String contentHash,String corpusHash,String releaseManifestHash,String indexVersion,Instant createdAt,Instant updatedAt,List<CaseView> cases) {}
    public record Hit(int rank,UUID chunkId,int revision,UUID conceptId,String title,String excerpt,String contentHash,double rankScore,SourceView source,String sourceSection,boolean relevant) {}
    public record CaseResult(String caseKey,String query,UUID conceptId,String expectation,List<UUID> expectedChunkIds,List<Hit> hits,int relevantCount,Integer firstRelevantRank,Double precisionAtK,Double recallAtK,Double reciprocalRank,boolean passed,long elapsedMillis) {}
    public record Metrics(int totalCases,int matchCases,int noResultCases,int passedCases,int failedCases,Double meanPrecisionAtK,Double meanRecallAtK,Double meanReciprocalRank,Double hitRateAtK,Double noResultAccuracy,long totalElapsedMillis) {}
    public record Run(UUID id,UUID suiteId,UUID workspaceId,UUID releaseId,int suiteRevision,String suiteTitle,String suiteHash,String corpusHash,String indexVersion,String evaluatorVersion,int topK,int caseCount,int completedCases,String status,String errorCode,Instant createdAt,Instant startedAt,Instant finishedAt,Metrics metrics,String resultHash,String reportPath,List<CaseResult> results) {}
    public record Report(String format,String scope,UUID runId,UUID suiteId,int suiteRevision,Instant completedAt,Snapshot suite,String suiteHash,List<CorpusEntry> corpus,String evaluatorVersion,int topK,List<CaseResult> results,Metrics metrics,String resultHash,Map<String,String> metricDefinitions,List<String> limitations) {}
    public record Download(byte[] bytes,String contentHash,String filename) {}
}
