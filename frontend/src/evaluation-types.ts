// v0.6 M11 exact frontend contract. API prefix /api/v1. All routes require a real session.
// Capability EVALUATION_WORKBENCH; any learner, only their own workspace/suites/runs.
// All mutations require CSRF; creates/revisions/runs additionally require Idempotency-Key.
// Suite save is atomic full-case replacement into a NEW immutable revision. Preserve editor draft on 409.
export interface Page<T> {
  items: T[];
  nextCursor: string | null;
  limit: number;
}
export interface EvaluationCaseInput {
  key: string;
  query: string;
  conceptId: string | null;
  expectation: 'MATCH' | 'NO_RESULTS';
  expectedChunkIds: string[];
  note: string;
}
export interface EvaluationSuiteInput {
  title: string;
  description: string;
  cases: EvaluationCaseInput[];
}
export interface EvaluationRevisionInput extends EvaluationSuiteInput {
  expectedRevision: number;
}
export interface EvaluationChunkReference {
  chunkId: string;
  revision: number;
  conceptId: string;
  title: string;
  contentHash: string;
}
export interface EvaluationCase {
  key: string;
  query: string;
  conceptId: string | null;
  expectation: 'MATCH' | 'NO_RESULTS';
  expectedChunks: EvaluationChunkReference[];
  note: string;
}
export interface EvaluationSuiteSummary {
  id: string;
  workspaceId: string;
  releaseId: string;
  title: string;
  description: string;
  revision: number;
  status: 'ACTIVE' | 'ARCHIVED';
  caseCount: number;
  contentHash: string;
  corpusHash: string;
  createdAt: string;
  updatedAt: string;
}
export interface EvaluationSuite extends EvaluationSuiteSummary {
  releaseManifestHash: string;
  indexVersion: string;
  cases: EvaluationCase[];
}
export interface EvaluationHit {
  rank: number;
  chunkId: string;
  revision: number;
  conceptId: string;
  title: string;
  excerpt: string;
  contentHash: string;
  rankScore: number;
  source: { id: string; title: string; url: string; edition: string; usageNote: string };
  sourceSection: string;
  relevant: boolean;
}
export interface EvaluationCaseResult {
  caseKey: string;
  query: string;
  conceptId: string | null;
  expectation: 'MATCH' | 'NO_RESULTS';
  expectedChunkIds: string[];
  hits: EvaluationHit[];
  relevantCount: number;
  firstRelevantRank: number | null;
  precisionAtK: number | null;
  recallAtK: number | null;
  reciprocalRank: number | null;
  passed: boolean;
  elapsedMillis: number;
}
export interface EvaluationMetrics {
  totalCases: number;
  matchCases: number;
  noResultCases: number;
  passedCases: number;
  failedCases: number;
  meanPrecisionAtK: number | null;
  meanRecallAtK: number | null;
  meanReciprocalRank: number | null;
  hitRateAtK: number | null;
  noResultAccuracy: number | null;
  totalElapsedMillis: number;
}
export interface EvaluationRun {
  id: string;
  suiteId: string;
  workspaceId: string;
  releaseId: string;
  suiteRevision: number;
  suiteTitle: string;
  suiteHash: string;
  corpusHash: string;
  indexVersion: string;
  evaluatorVersion: string;
  topK: number;
  caseCount: number;
  completedCases: number;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  errorCode: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  metrics: EvaluationMetrics | null;
  resultHash: string | null;
  reportPath: string | null;
  results: EvaluationCaseResult[];
}
// GET /workspaces/{workspaceId}/evaluation-suites?limit=30&cursor=<UUID>&includeArchived=false => Page<EvaluationSuiteSummary>
// POST same {EvaluationSuiteInput} + Idempotency-Key => 201 EvaluationSuite
// GET /evaluation-suites/{id} => EvaluationSuite (latest)
// GET /evaluation-suites/{id}/revisions/{revision} => EvaluationSuite (immutable version; status is current archive state)
// POST /evaluation-suites/{id}/revisions {EvaluationRevisionInput} + Idempotency-Key => 201 EvaluationSuite
// POST /evaluation-suites/{id}/archive {expectedRevision:number} => EvaluationSuite; cancels pending runs; immutable history retained
// GET /evaluation-suites/{id}/runs?limit=30&cursor=<UUID> => Page<EvaluationRun>; list results=[] (details from GET run)
// POST /evaluation-suites/{id}/runs {suiteRevision:number,topK:number} + Idempotency-Key => 202 EvaluationRun
// GET /evaluation-runs/{id} => EvaluationRun including persisted case results; poll only QUEUED/RUNNING, 1200ms non-overlap
// POST /evaluation-runs/{id}/cancel (no body) => EvaluationRun; repeat cancellation safe, other terminal states 409
// GET /evaluation-runs/{id}/report => authenticated JSON attachment (only COMPLETED), immutable bytes, private/no-store, X-Content-SHA256
// Limits: title1..120, description0..2000; cases1..40; key [A-Za-z0-9_-]{1,64} unique; query1..500; note0..1000;
// MATCH expects1..8 unique chunk IDs from SAME published release (and concept when filtered); NO_RESULTS expects[];
// topK1..8; no score threshold. MATCH passed means all expected IDs returned. NO_RESULTS passed iff empty.
// Precision@k = relevant/k, recall = relevant/expected, reciprocal rank =1/firstRelevantRank or0. Macro averages over MATCH only.
// noResultAccuracy averages NO_RESULTS only. Missing groups are null, never fake zero. Metrics exist only on COMPLETED.
// Query labels are user's own expected evidence, NOT independently reviewed teaching-quality or mastery scores.
