import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import App from './App';
import { ApiError, api } from './api';
import type { EvaluationSuite } from './evaluation-types';
import { testConcept, testWorkspace } from './test/workspace';
import type { Session } from './types';

afterEach(() => {
  history.replaceState(null, '', '/');
  api.clearPrivateState();
});

it.each(['logout', 'expired session', 'initial session 401'])(
  'preserves an authenticated deep link but never carries its private selection across %s',
  async (boundary) => {
    const first: Session = { userId: 'first-owner', username: 'first-student', roles: ['LEARNER'] };
    const second: Session = {
      userId: 'second-owner',
      username: 'second-student',
      roles: ['LEARNER'],
    };
    const suite: EvaluationSuite = {
      id: 'private-suite',
      workspaceId: 'first-workspace',
      releaseId: 'release-1',
      title: '第一账户的固定评测',
      description: '',
      revision: 1,
      status: 'ACTIVE',
      caseCount: 1,
      contentHash: 'a'.repeat(64),
      corpusHash: 'b'.repeat(64),
      releaseManifestHash: 'c'.repeat(64),
      indexVersion: 'lexical-v1',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      cases: [
        {
          key: 'case-1',
          query: '不存在的词项',
          conceptId: null,
          expectation: 'NO_RESULTS',
          expectedChunks: [],
          note: '',
        },
      ],
    };
    const reads: { owner: string; path: string }[] = [];
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    vi.spyOn(api, 'warmCsrf').mockResolvedValue();
    vi.spyOn(api, 'request').mockImplementation(async (path, method = 'GET') => {
      if (path === '/auth/session') {
        if (method === 'GET') {
          if (boundary === 'initial session 401')
            throw new ApiError(401, 'UNAUTHENTICATED', '会话已过期');
          return first;
        }
        if (method === 'POST') return second;
        if (method === 'DELETE') return undefined;
      }
      throw new Error(`Unexpected mutation in identity boundary regression: ${method} ${path}`);
    });
    vi.spyOn(api, 'get').mockImplementation(async (path) => {
      const owner = api.scope;
      reads.push({ owner, path });
      if (path === '/workspaces?limit=100')
        return [
          {
            ...testWorkspace().workspace,
            id: owner === first.userId ? 'first-workspace' : 'second-workspace',
          },
        ];
      if (path === '/capabilities') return testWorkspace(['EVALUATION_WORKBENCH']).capabilities;
      if (path === '/releases/active') return { releaseId: 'release-1' };
      if (path.includes('/events?')) return [];
      if (path.startsWith('/concepts?')) return { releaseId: 'release-1', items: [testConcept] };
      if (path.includes('/evaluation-suites?'))
        return { items: owner === first.userId ? [suite] : [], nextCursor: null, limit: 30 };
      if (path === '/evaluation-suites/private-suite') {
        if (owner !== first.userId)
          throw new Error('The second identity attempted the previous private suite');
        return suite;
      }
      throw new Error(`Unexpected read in identity boundary regression: ${path}`);
    });
    history.replaceState(null, '', '/#evaluation/private-suite');
    const user = userEvent.setup();
    const view = render(<App />);
    if (boundary !== 'initial session 401') {
      await screen.findByRole('heading', { name: suite.title });
      expect(location.hash).toBe('#evaluation/private-suite');
      expect(reads).toContainEqual({
        owner: first.userId,
        path: '/evaluation-suites/private-suite',
      });
      if (boundary === 'logout') await user.click(screen.getByRole('button', { name: '退出登录' }));
      else await act(async () => api.onUnauthenticated?.());
    }
    await screen.findByRole('heading', { name: '回到你的工作台' });
    expect(location.hash).toBe('#learn');
    await user.type(screen.getByLabelText('用户名', { exact: false }), second.username);
    await user.type(screen.getByLabelText('密码', { exact: true }), 'A-realistic-test-password');
    await user.click(screen.getByRole('button', { name: '进入工作台' }));
    await screen.findByRole('heading', { name: '泛型与类型安全' });
    await waitFor(() =>
      expect(screen.getByLabelText('当前工作空间')).toHaveValue('second-workspace'),
    );
    expect(
      reads.filter(
        (entry) => entry.owner === second.userId && entry.path.startsWith('/evaluation-suites/'),
      ),
    ).toEqual([]);
    expect(screen.queryByRole('heading', { name: suite.title })).not.toBeInTheDocument();
    view.unmount();
  },
);
