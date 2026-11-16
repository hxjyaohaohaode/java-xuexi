export interface Session {
  userId: string;
  username: string;
  roles: string[];
}
export interface Capabilities {
  runtimeVersion: string;
  enabled: string[];
  unavailable: string[];
  businessReady: boolean;
  [key: string]: unknown;
}
export interface Workspace {
  id: string;
  releaseId: string;
  title: string;
  guidanceMode: 'ON_DEMAND' | 'COLLABORATIVE' | 'QUIET';
  revision: number;
  eventSequence: number;
  status: string;
}
export interface Goal {
  id: string;
  workspaceId: string;
  revision: number;
  text: string;
  acceptance: string[];
  status: 'ACTIVE' | 'ARCHIVED';
}
export interface Reference {
  kind: 'GOAL' | 'MEMORY' | 'ARTIFACT' | 'ATTEMPT' | 'KNOWLEDGE_CHUNK' | 'ASSET';
  id: string;
  revision: number;
  contentHash: string;
}
export interface ContentRef {
  id: string;
  revision: number;
}
export interface Context {
  id: string;
  workspaceId: string;
  workspaceRevision: number;
  eventSequence: number;
  references: Reference[];
  contentHash: string;
}
export interface Evidence {
  chunkId: string;
  revision: number;
  releaseId: string;
  title: string;
  excerpt: string;
  source: { id: string; title: string; url: string; edition: string; usageNote: string };
  rankScore: number;
  contentHash: string;
  conceptId: string;
  sourceSection: string;
}
export interface Concept {
  id: string;
  code: string;
  title: string;
  releaseId: string;
  prerequisiteIds: string[];
  evidence: Evidence[];
  relatedExerciseIds: string[];
}
export interface Exercise {
  id: string;
  revision: number;
  releaseId: string;
  kind: string;
  prompt: string;
  difficulty: number;
  conceptIds: string[];
  options: { id: string; text: string }[];
  answerKind?: 'CHOICE' | 'CODE' | 'TEXT';
  textPolicy?: TextPolicy | null;
}
export interface TextPolicy {
  version: 'text-exact-nfkc-v1';
  normalization: 'NFKC_WHITESPACE';
  caseSensitive: boolean;
  maxCharacters: number;
  maxUtf8Bytes: number;
  scope: string;
}
export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}
export interface Attempt {
  id: string;
  workspaceId: string;
  releaseId: string;
  exerciseId: string;
  exerciseRevision: number;
  status: string;
  hintLevel: number;
  score: number | null;
  maximum: number | null;
  feedback: string;
  answer: unknown;
  createdAt: string;
  graderVersion: string;
  misconceptionCodes: string[];
  evidenceRef: Reference | null;
  jobId?: string | null;
}
export interface ProgressItem {
  conceptId: string;
  independentSamples: number;
  weightedCorrect: number;
  weightedTotal: number;
  evidenceStatus: 'INSUFFICIENT' | 'AVAILABLE';
  algorithmVersion: string;
}
export interface Progress {
  items: ProgressItem[];
  updatedAt: string | null;
  rebuilding: boolean;
}
export interface Review {
  id: string;
  conceptId: string;
  originAttemptId: string;
  dueAt: string;
  reason: string;
  status: string;
}
export interface Memory {
  id: string;
  workspaceId: string;
  revision: number;
  basis: 'OBSERVED' | 'USER_STATED' | 'INFERRED';
  summary: string;
  supportRefs: Reference[];
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED';
}
export interface WorkspaceEvent {
  id: string;
  workspaceId: string;
  sequence: number;
  revision: number;
  type: string;
  origin: string;
  occurredAt: string;
  data: Record<string, unknown>;
}
export interface Artifact {
  id: string;
  workspaceId: string;
  kind: string;
  title: string;
  currentRevision: number;
}
export interface TextBlock {
  id: string;
  kind: 'PARAGRAPH' | 'HEADING' | 'CODE' | 'QUOTE';
  text: string;
  evidenceRefs: ContentRef[];
}
export interface SlideElement {
  id: string;
  kind: 'TEXT' | 'CODE' | 'DIAGRAM' | 'IMAGE';
  x: number;
  y: number;
  width: number;
  height: number;
  text: string;
  assetId: string | null;
  alt: string;
}
export interface Slide {
  id: string;
  title: string;
  elements: SlideElement[];
  notes: string;
  evidenceRefs: ContentRef[];
}
export interface InteractionBlock {
  id: string;
  kind: string;
  prompt: string;
  conceptIds: string[];
  exerciseRef: ContentRef | null;
  attemptId: string | null;
  allowedActions: string[];
  evidenceRefs: ContentRef[];
  config: Record<string, unknown>;
  revision: number;
}
export type ArtifactContent =
  | { kind: 'DOCUMENT'; schemaVersion: '1.0'; title: string; blocks: TextBlock[] }
  | { kind: 'SLIDES'; schemaVersion: '1.0'; aspect: '16:9' | '4:3'; slides: Slide[] }
  | { kind: 'NOTEBOOK' | 'INTERACTION'; schemaVersion: '1.0'; blocks: InteractionBlock[] }
  | {
      kind: 'DIAGRAM';
      schemaVersion: '1.0';
      nodes: { id: string; label: string; typeExpression: string }[];
      edges: {
        from: string;
        to: string;
        relation: string;
        verdict: string;
        evidenceRefs: ContentRef[];
      }[];
    };
export interface ArtifactVersion {
  artifactId: string;
  revision: number;
  schemaVersion: string;
  content: ArtifactContent;
  contentHash: string;
  inputRefs: Reference[];
  reviewStatus: 'DRAFT' | 'VERIFIED' | 'REJECTED' | 'STALE';
}
export interface Run {
  id: string;
  workspaceId: string;
  goalId: string;
  snapshotId: string;
  status: string;
  planRevision: number;
  runEpoch: number;
  plan: unknown;
  budgetMicros: number;
  tokenBudget: number;
  spentMicros: number;
  reservedMicros: number;
  spentTokens: number;
  reservedTokens: number;
  deadline: string;
  steps: {
    id: string;
    key: string;
    role: string;
    status: string;
    attempts: number;
    required: boolean;
    outputRefs: unknown[];
  }[];
  limitations: string[];
}
export interface RunEvent {
  sequence: number;
  type: string;
  payload: Record<string, unknown>;
  createdAt: string;
}
export interface Preference {
  theme: 'system' | 'light' | 'dark';
  reducedMotion: boolean;
  codeFontSize: number;
  revision: number;
}
export interface LabResult {
  id: string;
  releaseId: string;
  verdict:
    'ALLOWED' | 'DENIED' | 'UNSUPPORTED' | 'QUEUED' | 'RUNNING' | 'UNAVAILABLE' | 'CANCELLED';
  ruleCode: string;
  explanation: string;
  rulesVersion: string;
  jobId: string | null;
  predictionCorrect?: boolean | null;
}

export interface CompileJob {
  id: string;
  workspaceId: string;
  releaseId: string;
  attemptId: string | null;
  status: string;
  sourceHash: string;
  jdkVersion: string | null;
  diagnostics: {
    code: string;
    severity: string;
    line: number | null;
    column: number | null;
    message: string;
  }[];
  output: string;
  outputTruncated: boolean;
  errorCode: string | null;
  createdAt: string;
  finishedAt: string | null;
}

export interface Asset {
  id: string;
  workspaceId: string;
  filename: string;
  mimeType: string;
  byteSize: number;
  contentHash: string;
  revision: number;
  status: 'READY' | 'REVOKED';
  createdAt: string;
  width: number | null;
  height: number | null;
  durationMillis: number | null;
  downloadPath: string | null;
  validation: string;
}
export interface AssetPage {
  items: Asset[];
  nextCursor: string | null;
  usage: {
    usedBytes: number;
    limitBytes: number;
    maxFileBytes: number;
    activeFiles: number;
    maxFiles: number;
  };
}
export interface ExportJob {
  id: string;
  artifactId: string;
  artifactRevision: number;
  status: string;
  outputAssetId: string | null;
  limitations: string[];
  format: string;
  downloadPath: string | null;
  contentHash: string | null;
  sourceState: 'CURRENT' | 'SUPERSEDED' | 'STALE' | 'REJECTED' | 'CORRUPT';
  errorCode: string | null;
}
export interface Conversation {
  id: string;
  workspaceId: string;
  releaseId: string;
  title: string;
  createdAt: string;
  updatedAt?: string;
}
export interface TutorRequest {
  id: string;
  conversationId: string;
  workspaceId: string;
  snapshotId: string;
  mode: 'EVIDENCE_ONLY' | 'MODEL';
  status: string;
  epoch: number;
  question: string;
  hintLevel: number;
  answer: string | null;
  evidenceRefs: Evidence[];
  diagnosticRefs: unknown[];
  suggestedExerciseIds: string[];
  limitations: string;
  errorCode: string | null;
  sourceState: 'CURRENT' | 'STALE';
  usage: { reservedTokens: number; usedTokens: number | null; billingState?: string };
  createdAt: string;
  completedAt: string | null;
}
