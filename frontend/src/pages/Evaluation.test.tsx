import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { ApiError, api } from '../api';
import { TestWorkspace, deferred, testConcept, testWorkspace } from '../test/workspace';
import type { EvaluationRun, EvaluationSuite } from '../evaluation-types';
import { Evaluation } from './Evaluation';

const suite: EvaluationSuite = {
  id: 'suite-1',
  workspaceId: 'workspace-1',
  releaseId: 'release-1',
  title: '我的固定检索样例',
  description: '核对证据范围',
  revision: 1,
  status: 'ACTIVE',
  caseCount: 1,
  contentHash: 'b'.repeat(64),
  corpusHash: 'c'.repeat(64),
  releaseManifestHash: 'd'.repeat(64),
  indexVersion: 'lexical-v1',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  cases: [
    {
      key: 'case-1',
      query: '通配符',
      conceptId: null,
      expectation: 'MATCH',
      expectedChunks: [
        {
          chunkId: 'chunk-1',
          revision: 2,
          conceptId: 'concept-1',
          title: '通配符读写边界',
          contentHash: 'a'.repeat(64),
        },
      ],
      note: '核对实际来源',
    },
  ],
};
const page = (items: unknown[]) => ({ items, nextCursor: null, limit: 30 });
function view(selected = '') {
  const context = testWorkspace(['EVALUATION_WORKBENCH']);
  context.selection = selected;
  return render(
    <TestWorkspace value={context}>
      <Evaluation />
    </TestWorkspace>,
  );
}

it('does not invent expected evidence after a read failure and preserves a rejected save with controls locked while pending', async () => {
  vi.spyOn(api, 'get').mockImplementation((path) =>
    path.startsWith('/concepts')
      ? Promise.reject(new ApiError(503, 'UNAVAILABLE', '课程证据暂时不可读'))
      : Promise.resolve(page([])),
  );
  const pending = deferred<EvaluationSuite>();
  const request = vi.spyOn(api, 'request').mockReturnValue(pending.promise);
  const user = userEvent.setup();
  view();
  await user.type(screen.getByLabelText('评测集标题'), '有明确边界的评测');
  await user.type(screen.getByLabelText('实际检索查询'), '未命中的有限查询');
  expect(screen.getByRole('button', { name: '保存评测集' })).toBeDisabled();
  await screen.findByText('课程证据暂时不可读');
  await user.selectOptions(screen.getByLabelText('期望结果'), 'NO_RESULTS');
  await user.click(screen.getByRole('button', { name: '保存评测集' }));
  expect(screen.getByLabelText('实际检索查询')).toBeDisabled();
  expect(screen.getByRole('button', { name: '添加样例' })).toBeDisabled();
  expect(request).toHaveBeenCalledWith(
    '/workspaces/workspace-1/evaluation-suites',
    'POST',
    expect.objectContaining({
      cases: [expect.objectContaining({ expectation: 'NO_RESULTS', expectedChunkIds: [] })],
    }),
    { idempotent: true },
  );
  await act(async () => pending.reject(new ApiError(503, 'SAVE_UNAVAILABLE', '保存暂时不可用')));
  await screen.findByText('保存暂时不可用');
  expect(screen.getByLabelText('实际检索查询')).toHaveValue('未命中的有限查询');
  expect(screen.getByRole('button', { name: '保存评测集' })).toBeEnabled();
  expect(screen.queryByText(/服务端已保存评测集版本/)).not.toBeInTheDocument();
});

it('requires an explicit rebase after a concurrent suite update and keeps the complete local annotation', async () => {
  let remote = suite;
  vi.spyOn(api, 'get').mockImplementation((path) =>
    path.startsWith('/concepts')
      ? Promise.resolve({ items: [testConcept] })
      : path === '/evaluation-suites/suite-1'
        ? Promise.resolve(remote)
        : Promise.resolve(page([remote])),
  );
  const request = vi
    .spyOn(api, 'request')
    .mockImplementation((_path, _method, body) =>
      Promise.resolve({ ...remote, revision: 3, title: (body as { title: string }).title }),
    );
  const user = userEvent.setup();
  view(suite.id);
  const query = await screen.findByLabelText('实际检索查询');
  await user.clear(query);
  await user.type(query, '本标签页保留的查询');
  remote = {
    ...suite,
    revision: 2,
    cases: [{ ...suite.cases[0], query: '另一个窗口已保存的查询' }],
  };
  await user.click(screen.getByRole('button', { name: '刷新检索评测' }));
  await screen.findByText('服务端已有版本 2，你的草稿仍基于版本 1');
  expect(query).toHaveValue('本标签页保留的查询');
  expect(screen.getByRole('button', { name: '保存评测集' })).toBeDisabled();
  await user.click(screen.getByRole('button', { name: '保留草稿，更新保存基线' }));
  await user.click(
    within(screen.getByRole('dialog')).getByRole('button', { name: '保留草稿并更新基线' }),
  );
  await user.click(screen.getByRole('button', { name: '保存评测集' }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith(
      '/evaluation-suites/suite-1/revisions',
      'POST',
      expect.objectContaining({
        expectedRevision: 2,
        cases: [
          expect.objectContaining({
            query: '本标签页保留的查询',
            expectedChunkIds: ['chunk-1'],
            note: '核对实际来源',
          }),
        ],
      }),
      { idempotent: true },
    ),
  );
});

it('waits for confirmed cancellation and never shows complete metrics or a report for an interrupted run', async () => {
  let run: EvaluationRun = {
    id: 'run-1',
    suiteId: suite.id,
    workspaceId: suite.workspaceId,
    releaseId: suite.releaseId,
    suiteRevision: 1,
    suiteTitle: suite.title,
    suiteHash: suite.contentHash,
    corpusHash: suite.corpusHash,
    indexVersion: 'lexical-v1',
    evaluatorVersion: 'retrieval-v1',
    topK: 3,
    caseCount: 1,
    completedCases: 0,
    status: 'RUNNING',
    errorCode: null,
    createdAt: suite.createdAt,
    startedAt: suite.createdAt,
    finishedAt: null,
    metrics: null,
    resultHash: null,
    reportPath: null,
    results: [],
  };
  vi.spyOn(api, 'get').mockImplementation((path) =>
    path.startsWith('/concepts')
      ? Promise.resolve({ items: [testConcept] })
      : path === '/evaluation-suites/suite-1'
        ? Promise.resolve(suite)
        : path === '/evaluation-runs/run-1'
          ? Promise.resolve(run)
          : path.includes('/runs?')
            ? Promise.resolve(page([run]))
            : Promise.resolve(page([suite])),
  );
  const pending = deferred<EvaluationRun>();
  vi.spyOn(api, 'request').mockReturnValue(pending.promise);
  const user = userEvent.setup();
  view(suite.id);
  await user.click(await screen.findByRole('button', { name: '运行与对照' }));
  await user.click(await screen.findByRole('button', { name: '取消这次运行' }));
  expect(screen.getByRole('button', { name: '取消这次运行' })).toBeDisabled();
  expect(
    screen.queryByText('已取消，保留此前实际产生的逐例结果，不计算完整评测指标。'),
  ).not.toBeInTheDocument();
  run = { ...run, status: 'CANCELLED', finishedAt: suite.updatedAt };
  await act(async () => pending.resolve(run));
  await screen.findByText('已取消，保留此前实际产生的逐例结果，不计算完整评测指标。');
  expect(screen.queryByRole('link', { name: '下载真实 JSON 报告' })).not.toBeInTheDocument();
  expect(screen.queryByText('100.0%')).not.toBeInTheDocument();
});
