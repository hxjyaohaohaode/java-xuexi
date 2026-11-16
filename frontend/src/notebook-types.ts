import type { ContentRef, Exercise, LabResult } from './types';

export type ActivityKind =
  'TYPE_FLOW' | 'REPAIR_DIFF' | 'COUNTERFACTUAL' | 'EXPERT_HELP' | 'REVIEW_LINKS';
export type TypeInput = {
  sourceType: string;
  targetType: string;
  operation: 'ASSIGN' | 'READ' | 'WRITE';
  prediction: 'ALLOWED' | 'DENIED' | 'UNKNOWN';
};
export type TypeFlowConfig = {
  sourceType: string;
  targetType: string;
  parameters: { name: 'sourceType' | 'targetType'; candidates: string[] }[];
  operations: TypeInput['operation'][];
};
export type NotebookNavigation = {
  artifactId: string;
  revision: number;
  kind: string;
  title: string;
};
export type NotebookOptions = {
  artifactId: string;
  artifactRevision: number;
  originBlockId: string;
  availableKinds: ActivityKind[];
  typeFlowDefaults: TypeFlowConfig;
  counterfactualCandidates: Exercise[];
  artifactCandidates: NotebookNavigation[];
  reviewIds: string[];
};
export type NotebookResult = {
  status: 'ACCEPTED' | 'COMPLETED';
  attemptId: string | null;
  compileJobId: string | null;
  tutorRequestId: string | null;
  message: string;
  evidenceRefs: ContentRef[];
  eventId: string;
  artifactRevision: number;
  blockId: string;
  stateRevision: number;
  labSession: LabResult | null;
  selectedExercise: Exercise | null;
  navigation: NotebookNavigation | null;
  baselineCompileJobId: string | null;
};
export type NotebookBlockState = {
  blockId: string;
  stateRevision: number;
  typeInput: TypeInput | null;
  selectedExerciseRef: ContentRef | null;
  hintLevel: number;
  lastResult: NotebookResult | null;
};
export type NotebookState = {
  artifactId: string;
  artifactRevision: number;
  blocks: NotebookBlockState[];
};
export type NotebookExtensions = {
  answer?:
    | { kind: 'CHOICE'; optionIds: string[] }
    | { kind: 'CODE'; source: string }
    | { kind: 'TEXT'; value: string }
    | null;
  selection?: string;
  hintLevel?: number;
  typeInput?: TypeInput;
  parameterInput?: {
    name: 'sourceType' | 'targetType';
    value: string;
    operation: TypeInput['operation'];
    prediction: TypeInput['prediction'];
  };
  exerciseRef?: ContentRef;
  artifactRef?: ContentRef;
};
