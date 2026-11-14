import type { ArtifactContent, WorkspaceEvent } from './types';

export function mergeEvents(cursor: number, events: WorkspaceEvent[], workspaceId: string) {
  const fresh = events
    .filter((e) => e.workspaceId === workspaceId && e.sequence > cursor)
    .sort((a, b) => a.sequence - b.sequence);
  const accepted: WorkspaceEvent[] = [];
  let next = cursor;
  for (const event of fresh) {
    if (event.sequence <= next) continue;
    if (event.sequence !== next + 1) return { cursor: next, events: accepted, gap: true };
    accepted.push(event);
    next = event.sequence;
  }
  return { cursor: next, events: accepted, gap: false };
}
/** A response may commit only while its owning workspace/generation is current. */
export class ScopeFence {
  private epoch = 0;
  advance() {
    return ++this.epoch;
  }
  current(ticket: number) {
    return ticket === this.epoch;
  }
}
export interface SourceDraft {
  baseRevision: number;
  content: ArtifactContent;
  dirty: boolean;
}
export function reconcileDraft(
  draft: SourceDraft,
  remote: { revision: number; content: ArtifactContent },
) {
  // A read started before a successful save must not rewind that save's baseline.
  if (remote.revision < draft.baseRevision) return { draft, conflict: false };
  return draft.dirty
    ? { draft, conflict: remote.revision !== draft.baseRevision }
    : {
        draft: { baseRevision: remote.revision, content: remote.content, dirty: false },
        conflict: false,
      };
}
export function submitOnEnter(event: {
  key: string;
  shiftKey: boolean;
  nativeEvent?: { isComposing?: boolean };
  isComposing?: boolean;
}) {
  return (
    event.key === 'Enter' &&
    !event.shiftKey &&
    !event.isComposing &&
    !event.nativeEvent?.isComposing
  );
}
export function readSaved<T>(key: string, fallback: T): T {
  try {
    return JSON.parse(sessionStorage.getItem(key) || 'null') ?? fallback;
  } catch {
    return fallback;
  }
}
export function writeSaved(key: string, value: unknown) {
  try {
    sessionStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* An unavailable local draft store never means a server save succeeded. */
  }
}
