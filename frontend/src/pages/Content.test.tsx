import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { api } from '../api';
import { WorkspaceContext, type WorkspaceState } from '../context';
import { Content } from './Content';

const citations = [
  { sourceId: 'source-a', ingestionId: 'capture-a', section: '4.5.1' },
  { sourceId: 'source-b', ingestionId: 'capture-b', section: '5.1.10' },
];
const chunk = {
  id: 'chunk-a',
  revision: 3,
  conceptId: 'concept-a',
  title: '具有两条来源的知识卡',
  body: '固定知识内容',
  reviewHash: 'a'.repeat(64),
  status: 'DRAFT',
  authorId: 'another-editor',
  sources: citations,
};
function fixture() {
  const context: WorkspaceState = {
    session: { userId: 'editor', username: 'editor', roles: ['EDITOR'] },
    workspace: {
      id: 'space',
      title: '课程',
      releaseId: 'release',
      revision: 1,
      eventSequence: 0,
      guidanceMode: 'ON_DEMAND',
      status: 'ACTIVE',
    },
    tick: 0,
    events: [],
    refresh: vi.fn(),
    navigate: vi.fn(),
    selection: '',
    can: () => false,
    capabilities: { enabled: [], unavailable: [], businessReady: false, runtimeVersion: 'test' },
  };
  const get = vi.spyOn(api, 'get').mockImplementation((path) => {
    if (path.startsWith('/admin/sources?'))
      return Promise.resolve(
        citations.map((ref) => ({
          id: ref.sourceId,
          title: ref.sourceId,
          url: 'https://docs.oracle.com',
          edition: '17',
          usageNote: '用于教学引用',
          captureCount: 1,
        })),
      );
    if (path.startsWith('/admin/concepts?'))
      return Promise.resolve([
        { id: 'concept-a', code: 'PECS', title: '生产者与消费者', prerequisiteIds: [] },
      ]);
    if (path.startsWith('/admin/chunks?')) return Promise.resolve([chunk]);
    if (path.startsWith('/admin/chunks/')) return Promise.resolve(chunk);
    if (path.includes('/imports?'))
      return Promise.resolve(
        citations.map((ref) => ({
          id: ref.ingestionId,
          sourceId: ref.sourceId,
          originalMarkdown: '原稿',
          cleanedMarkdown: '清洗稿',
          processingNote: '保留原例',
          originalHash: 'b'.repeat(64),
          cleanedHash: 'c'.repeat(64),
          createdAt: '2026-01-01T00:00:00Z',
        })),
      );
    return Promise.resolve([]);
  });
  const view = () =>
    render(
      <WorkspaceContext.Provider value={context}>
        <Content />
      </WorkspaceContext.Provider>,
    );
  return { context, get, view };
}

it('preserves every citation on revision and saving knowledge does not clear another form draft', async () => {
  const { view } = fixture();
  const request = vi.spyOn(api, 'request').mockResolvedValue(chunk);
  const user = userEvent.setup();
  view();
  await screen.findByRole('button', { name: /source-a/ });
  await user.click(screen.getByText('登记新来源'));
  await user.type(screen.getByLabelText('来源名称'), '尚未保存的资料');
  await user.click(screen.getByRole('tab', { name: '知识版本' }));
  await user.click(screen.getByRole('button', { name: '修订为新版本' }));
  expect(await screen.findByLabelText('引用章节 2')).toHaveValue('5.1.10');
  await user.type(screen.getByLabelText('知识正文'), '，补充说明');
  await user.click(screen.getByRole('button', { name: '保存知识草稿' }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith(
      '/admin/chunks',
      'POST',
      expect.objectContaining({ revision: 4, baseRevision: 3, sources: citations }),
      { idempotent: true },
    ),
  );
  expect(await screen.findByText(/尚未提交：资料来源。/)).toBeInTheDocument();
  await user.click(screen.getByRole('tab', { name: '资料来源' }));
  expect(screen.getByLabelText('来源名称')).toHaveValue('尚未保存的资料');
});

it('requires a deliberate discard before loading another revision over an edited knowledge draft', async () => {
  const { view, get } = fixture();
  const user = userEvent.setup();
  view();
  await user.click(screen.getByRole('tab', { name: '知识版本' }));
  await user.type(screen.getByLabelText('知识卡标题'), '我的未保存草稿');
  await user.click(await screen.findByRole('button', { name: '修订为新版本' }));
  const dialog = screen.getByRole('dialog', { name: '替换尚未保存的草稿' });
  expect(
    get.mock.calls.filter(([path]) => path === '/admin/chunks/chunk-a/revisions/3'),
  ).toHaveLength(0);
  await user.click(within(dialog).getByRole('button', { name: '继续编辑当前草稿' }));
  expect(screen.getByLabelText('知识卡标题')).toHaveValue('我的未保存草稿');
  await user.click(screen.getByRole('button', { name: '修订为新版本' }));
  await user.click(screen.getByRole('button', { name: '放弃这份草稿并继续' }));
  await waitFor(() => expect(screen.getByLabelText('知识卡标题')).toHaveValue(chunk.title));
});

it('sends the independently reviewed hash and the exact APPROVED protocol decision', async () => {
  const { view } = fixture();
  const request = vi.spyOn(api, 'request').mockResolvedValue({});
  const user = userEvent.setup();
  view();
  await user.click(screen.getByRole('tab', { name: '独立审核' }));
  await user.click(await screen.findByRole('button', { name: /具有两条来源的知识卡/ }));
  await user.type(await screen.findByLabelText('审核意见'), '已核对两条采集记录和知识边界。');
  await user.click(screen.getByRole('button', { name: '通过审核' }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith(
      '/admin/reviews',
      'POST',
      expect.objectContaining({
        targetKind: 'CHUNK',
        targetRevision: 3,
        expectedContentHash: chunk.reviewHash,
        decision: 'APPROVED',
      }),
      { idempotent: true },
    ),
  );
});
