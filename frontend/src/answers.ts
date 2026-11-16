import type { Exercise, TextPolicy } from './types';

export function exerciseAnswerKind(exercise: Pick<Exercise, 'kind' | 'answerKind'>) {
  if (exercise.answerKind) return exercise.answerKind;
  // Persisted v0.5 records did not yet carry the explicit discriminator.
  if (['SIGNATURE', 'REFACTOR', 'FILL'].includes(exercise.kind)) return 'CODE';
  if (['SINGLE', 'MULTIPLE', 'COMPILE_PREDICTION', 'READING', 'DIAGNOSIS'].includes(exercise.kind))
    return 'CHOICE';
  return 'UNSUPPORTED';
}

export function textAnswerLimits(policy?: TextPolicy | null) {
  return {
    characters: Math.min(4000, policy?.maxCharacters || 4000),
    bytes: Math.min(16000, policy?.maxUtf8Bytes || 16000),
  };
}

export function validTextAnswer(value: string, policy?: TextPolicy | null) {
  const limits = textAnswerLimits(policy);
  return (
    !!value.trim() &&
    !value.includes('\0') &&
    value.length <= limits.characters &&
    new TextEncoder().encode(value).byteLength <= limits.bytes
  );
}
