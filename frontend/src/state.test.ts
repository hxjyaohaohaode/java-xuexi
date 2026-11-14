import { describe, expect, it } from 'vitest';
import { mergeEvents, reconcileDraft, ScopeFence, submitOnEnter } from './state';
import type { ArtifactContent, WorkspaceEvent } from './types';
const event = (sequence: number, workspaceId = 'a'): WorkspaceEvent => ({
  id: `e${sequence}`,
  workspaceId,
  sequence,
  revision: sequence,
  type: 'ATTEMPT_GRADED',
  origin: 'SERVER',
  occurredAt: '2026-01-01T00:00:00Z',
  data: {},
});
describe('workspace state invariants', () => {
  it('deduplicates replay, rejects a different workspace and advances contiguous sequence only', () => {
    const r = mergeEvents(2, [event(2), event(3), event(3), event(4, 'b'), event(4)], 'a');
    expect(r.events.map((e) => e.sequence)).toEqual([3, 4]);
    expect(r.cursor).toBe(4);
    expect(r.gap).toBe(false);
  });
  it('never acknowledges past an event gap', () => {
    const r = mergeEvents(4, [event(5), event(7)], 'a');
    expect(r.cursor).toBe(5);
    expect(r.gap).toBe(true);
    expect(r.events.map((e) => e.sequence)).toEqual([5]);
  });
  it('invalidates older workspace and mutation generations', () => {
    const fence = new ScopeFence();
    const old = fence.advance();
    const latest = fence.advance();
    expect(fence.current(old)).toBe(false);
    expect(fence.current(latest)).toBe(true);
    fence.advance();
    expect(fence.current(latest)).toBe(false);
  });
  it('keeps the local dirty source and baseline when a newer remote version arrives', () => {
    const local: ArtifactContent = {
      kind: 'DOCUMENT',
      schemaVersion: '1.0',
      title: 'my draft',
      blocks: [],
    };
    const result = reconcileDraft(
      { baseRevision: 2, content: local, dirty: true },
      { revision: 3, content: { ...local, title: 'other tab' } },
    );
    expect(result.conflict).toBe(true);
    expect(result.draft.baseRevision).toBe(2);
    expect(result.draft.content).toEqual(local);
  });
  it('accepts a newer server source when there are no local edits', () => {
    const c: ArtifactContent = {
      kind: 'DOCUMENT',
      schemaVersion: '1.0',
      title: 'server',
      blocks: [],
    };
    expect(
      reconcileDraft({ baseRevision: 1, content: c, dirty: false }, { revision: 2, content: c }),
    ).toEqual({ draft: { baseRevision: 2, content: c, dirty: false }, conflict: false });
  });
  it.each([false, true])(
    'ignores a late older revision without rollback or false conflict (dirty=%s)',
    (dirty) => {
      const content: ArtifactContent = {
        kind: 'DOCUMENT',
        schemaVersion: '1.0',
        title: 'just saved, possibly edited again',
        blocks: [],
      };
      const draft = { baseRevision: 3, content, dirty };
      const result = reconcileDraft(draft, {
        revision: 2,
        content: { ...content, title: 'late pre-save read' },
      });
      expect(result.draft).toBe(draft);
      expect(result.conflict).toBe(false);
    },
  );
  it('does not submit while composing Chinese or inserting a newline', () => {
    expect(
      submitOnEnter({ key: 'Enter', shiftKey: false, nativeEvent: { isComposing: true } }),
    ).toBe(false);
    expect(submitOnEnter({ key: 'Enter', shiftKey: true })).toBe(false);
    expect(submitOnEnter({ key: 'Enter', shiftKey: false })).toBe(true);
  });
});
