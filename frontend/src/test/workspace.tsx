import type { ReactNode } from 'react';
import { vi } from 'vitest';
import { WorkspaceContext, type WorkspaceState } from '../context';
import type { Concept } from '../types';

export const testConcept: Concept = {
  id: 'concept-1',
  releaseId: 'release-1',
  code: 'PECS',
  title: '生产者与消费者',
  prerequisiteIds: [],
  relatedExerciseIds: [],
  evidence: [
    {
      chunkId: 'chunk-1',
      revision: 2,
      releaseId: 'release-1',
      title: '通配符读写边界',
      excerpt: '上界读取与下界写入的固定课程依据。',
      source: {
        id: 'source-1',
        title: 'Java 语言规范',
        url: 'https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html',
        edition: '17',
        usageNote: '教学引用',
      },
      rankScore: 1,
      contentHash: 'a'.repeat(64),
      conceptId: 'concept-1',
      sourceSection: '4.5.1',
    },
  ],
};
export function testWorkspace(enabled: string[] = []): WorkspaceState {
  return {
    session: { userId: 'test-owner', username: 'test-student', roles: ['LEARNER'] },
    workspace: {
      id: 'workspace-1',
      releaseId: 'release-1',
      title: '学习空间',
      guidanceMode: 'ON_DEMAND',
      revision: 1,
      eventSequence: 0,
      status: 'ACTIVE',
    },
    capabilities: { enabled, unavailable: [], businessReady: false, runtimeVersion: 'test' },
    tick: 0,
    events: [],
    refresh: vi.fn(),
    navigate: vi.fn(),
    selection: '',
    can: (name) => enabled.includes(name),
  };
}
export function TestWorkspace({ value, children }: { value: WorkspaceState; children: ReactNode }) {
  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}
export function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (error: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
