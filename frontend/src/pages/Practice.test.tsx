import { render, screen, waitFor } from '@testing-library/react';
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
