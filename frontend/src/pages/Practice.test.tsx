import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { api } from '../api';
import { WorkspaceContext, type WorkspaceState } from '../context';
import { Practice } from './Practice';
import type { Attempt, Exercise } from '../types';

it('renders the published SINGLE kind as radio and sends one CHOICE option id', async () => {
  const exercise: Exercise = {
    id: 'exercise-1',
    revision: 1,
    releaseId: 'release-1',
    kind: 'SINGLE',
    prompt: 'Which generic array creation is legal?',
    difficulty: 2,
    conceptIds: [],
    options: [
      { id: 'A', text: 'new List<?>[2]' },
      { id: 'B', text: 'new List<String>[2]' },
    ],
  };
  const attempt: Attempt = {
    id: 'attempt-1',
    workspaceId: 'workspace-1',
    releaseId: 'release-1',
    exerciseId: exercise.id,
    exerciseRevision: 1,
    status: 'GRADED',
    hintLevel: 0,
    score: 0,
    maximum: 100,
    feedback: 'Generic arrays with a concrete argument are not reifiable.',
    answer: { kind: 'CHOICE', optionIds: ['B'] },
    createdAt: '2026-01-01T00:00:00Z',
    graderVersion: 'choice-exact-v1',
    misconceptionCodes: ['REIFIABLE_ARRAY'],
    evidenceRef: { kind: 'ATTEMPT', id: 'attempt-1', revision: 1, contentHash: 'a'.repeat(64) },
  };
  vi.spyOn(api, 'get').mockImplementation((path) =>
    Promise.resolve(
      path.startsWith('/concepts')
        ? { items: [] }
        : path.startsWith('/exercises?')
          ? { items: [exercise], nextCursor: null }
          : path.startsWith('/attempts/')
            ? attempt
            : [],
    ),
  );
  const request = vi.spyOn(api, 'request').mockResolvedValue(attempt);
  const context: WorkspaceState = {
    workspace: {
      id: 'workspace-1',
      releaseId: 'release-1',
      title: 'Test',
      guidanceMode: 'ON_DEMAND',
      revision: 1,
      eventSequence: 0,
      status: 'ACTIVE',
    },
    session: { userId: 'owner', username: 'student', roles: ['STUDENT'] },
    capabilities: { runtimeVersion: 'test', enabled: [], unavailable: [], businessReady: false },
    tick: 0,
    refresh: vi.fn(),
    events: [],
    navigate: vi.fn(),
    selection: '',
    can: () => true,
  };
  const user = userEvent.setup();
  render(
    <WorkspaceContext.Provider value={context}>
      <Practice />
    </WorkspaceContext.Provider>,
  );
  const first = await screen.findByRole('radio', { name: /new List<\?>/ });
  const second = screen.getByRole('radio', { name: /new List<String>/ });
  await user.click(first);
  await user.click(second);
  expect(first).not.toBeChecked();
  expect(second).toBeChecked();
  await user.click(screen.getByRole('button', { name: '提交作答' }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith(
      '/attempts',
      'POST',
      expect.objectContaining({ answer: { kind: 'CHOICE', optionIds: ['B'] } }),
      { idempotent: true },
    ),
  );
  await screen.findByRole('heading', { name: '服务端反馈' });
});

it('submits a real code kind and keeps a pending result ungraded until the server supplies evidence', async () => {
  const exercise: Exercise = {
    id: 'code-question',
    revision: 2,
    releaseId: 'release-1',
    kind: 'SIGNATURE',
    prompt: '编写可验证的泛型签名',
    difficulty: 2,
    conceptIds: [],
    options: [],
  };
  const pending: Attempt = {
    id: 'pending-code',
    workspaceId: 'workspace-1',
    releaseId: 'release-1',
    exerciseId: exercise.id,
    exerciseRevision: 2,
    status: 'PENDING',
    hintLevel: 0,
    score: null,
    maximum: null,
    feedback: '等待专属正反例检查',
    answer: { kind: 'CODE', source: 'public class Main {}' },
    createdAt: '2026-01-01T00:00:00Z',
    graderVersion: 'code-v1',
    misconceptionCodes: [],
    evidenceRef: null,
    jobId: 'compile-job',
  };
  const finishes: ((value: Attempt) => void)[] = [];
  vi.spyOn(api, 'get').mockImplementation((path) =>
    path === '/attempts/pending-code'
      ? new Promise<Attempt>((resolve) => {
          finishes.push(resolve);
        })
      : Promise.resolve(
          path.startsWith('/concepts')
            ? { items: [] }
            : path.startsWith('/exercises?')
              ? { items: [exercise], nextCursor: null }
              : [],
        ),
  );
  const request = vi.spyOn(api, 'request').mockResolvedValue(pending);
  const state: WorkspaceState = {
    workspace: {
      id: 'workspace-1',
      title: '代码练习',
      releaseId: 'release-1',
      revision: 1,
      eventSequence: 0,
      guidanceMode: 'ON_DEMAND',
      status: 'ACTIVE',
    },
    session: { userId: 'owner', username: 'student', roles: ['LEARNER'] },
    capabilities: {
      runtimeVersion: 'test',
      enabled: ['CODE_GRADING'],
      unavailable: [],
      businessReady: false,
    },
    tick: 0,
    refresh: vi.fn(),
    events: [],
    navigate: vi.fn(),
    selection: '',
    can: (cap) => cap === 'CODE_GRADING',
  };
  const user = userEvent.setup();
  render(
    <WorkspaceContext.Provider value={state}>
      <Practice />
    </WorkspaceContext.Provider>,
  );
  const editor = await screen.findByLabelText(/代码作答 · Main.java/);
  await user.clear(editor);
  await user.type(editor, 'public class Main {{}');
  await user.click(screen.getByRole('button', { name: '提交代码作答' }));
  await screen.findByText('等待专属正反例检查');
  expect(editor).toBeDisabled();
  expect(document.querySelector('.feedback .score')).toBeNull();
  expect(screen.queryByRole('button', { name: '加入可复练错题集' })).toBeNull();
  expect(request).toHaveBeenCalledWith(
    '/attempts',
    'POST',
    expect.objectContaining({ answer: { kind: 'CODE', source: 'public class Main {}' } }),
    { idempotent: true },
  );
  await act(async () => {
    for (const finish of finishes)
      finish({
        ...pending,
        status: 'GRADED',
        score: 60,
        maximum: 100,
        feedback: '专属反例检查未通过',
        evidenceRef: { kind: 'ATTEMPT', id: pending.id, revision: 1, contentHash: 'a'.repeat(64) },
      });
  });
  expect(await screen.findByText('专属反例检查未通过')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '加入可复练错题集' })).toBeEnabled();
});
