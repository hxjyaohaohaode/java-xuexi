import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { ApiError, api } from '../api';
import { WorkspaceContext, type WorkspaceState } from '../context';
import type { Artifact, ArtifactVersion, Concept, Reference } from '../types';
import { Studio } from './Studio';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}

const reference: Reference = {
  kind: 'KNOWLEDGE_CHUNK',
  id: 'original-source',
  revision: 1,
  contentHash: 'a'.repeat(64),
};
const artifact: Artifact = {
  id: 'document-1',
  workspaceId: 'workspace-1',
  kind: 'DOCUMENT',
  title: '讲义作品',
  currentRevision: 1,
};
const original: ArtifactVersion = {
  artifactId: artifact.id,
  revision: 1,
  schemaVersion: '1.0',
  content: {
    kind: 'DOCUMENT',
    schemaVersion: '1.0',
    title: '原稿',
    blocks: [{ id: 'paragraph-1', kind: 'PARAGRAPH', text: '原始段落', evidenceRefs: [] }],
  },
  contentHash: 'b'.repeat(64),
  inputRefs: [reference],
  reviewStatus: 'DRAFT',
};
const concept: Concept = {
  id: 'concept-1',
  releaseId: 'release-1',
  code: 'PECS',
  title: '生产者与消费者',
  prerequisiteIds: [],
  relatedExerciseIds: [],
  evidence: [
    {
      chunkId: 'added-source',
      revision: 2,
      releaseId: 'release-1',
      title: '通配符边界',
      excerpt: '从边界分析可读写的类型。',
      source: {
        id: 'source-1',
        title: '课程资料',
        url: 'https://example.com/generics',
        edition: '17',
        usageNote: '测试引用',
      },
      rankScore: 1,
      contentHash: 'c'.repeat(64),
      conceptId: 'concept-1',
      sourceSection: '通配符',
    },
  ],
};

function context(): WorkspaceState {
  return {
    workspace: {
      id: artifact.workspaceId,
      releaseId: 'release-1',
      title: '工作空间',
      guidanceMode: 'ON_DEMAND',
      revision: 1,
      eventSequence: 0,
      status: 'ACTIVE',
    },
    session: { userId: 'owner', username: 'student', roles: ['LEARNER'] },
    capabilities: { runtimeVersion: 'test', enabled: [], unavailable: [], businessReady: false },
    tick: 0,
    refresh: vi.fn(),
    navigate: vi.fn(),
    events: [],
    selection: artifact.id,
    can: () => false,
  };
}

function serve(readVersion: () => Promise<ArtifactVersion> = () => Promise.resolve(original)) {
  return vi.spyOn(api, 'get').mockImplementation((path) => {
    if (path === `/workspaces/${artifact.workspaceId}/artifacts`)
      return Promise.resolve([artifact]);
    if (path === `/artifacts/${artifact.id}/revisions?revision=1`) return readVersion();
    if (path === '/concepts?releaseId=release-1') return Promise.resolve({ items: [concept] });
    throw new Error(`Unexpected read: ${path}`);
  });
}

function view(state: WorkspaceState) {
  return (
    <WorkspaceContext.Provider value={state}>
      <Studio />
    </WorkspaceContext.Provider>
  );
}

it('keeps failed notebook hints retryable and stops requesting after the third confirmed hint', async () => {
  const notebook: Artifact = { ...artifact, kind: 'NOTEBOOK', title: '复练笔记' };
  const version: ArtifactVersion = {
    ...original,
    content: {
      kind: 'NOTEBOOK',
      schemaVersion: '1.0',
      blocks: [
        {
          id: 'hint-block',
          kind: 'ERROR_CONTEXT',
          revision: 1,
          prompt: '分析读写边界',
          conceptIds: [],
          exerciseRef: { id: 'exercise-1', revision: 1 },
          attemptId: 'attempt-1',
          allowedActions: ['requestHint'],
          evidenceRefs: [],
          config: {},
        },
      ],
    },
  };
  vi.spyOn(api, 'get').mockImplementation((path) => {
    if (path === '/workspaces/workspace-1/artifacts') return Promise.resolve([notebook]);
    if (path === '/artifacts/document-1/revisions?revision=1') return Promise.resolve(version);
    if (path === '/concepts?releaseId=release-1') return Promise.resolve({ items: [] });
    if (path === '/exercises/exercise-1?releaseId=release-1&revision=1')
      return Promise.resolve({
        id: 'exercise-1',
        revision: 1,
        releaseId: 'release-1',
        kind: 'SINGLE',
        prompt: '分析读写边界',
        difficulty: 1,
        conceptIds: [],
        options: [{ id: 'A', text: '先检查读取类型' }],
      });
    throw new Error(`Unexpected read: ${path}`);
  });
  const request = vi
    .spyOn(api, 'request')
    .mockRejectedValueOnce(new ApiError(503, 'UNAVAILABLE', '提示暂时不可用'))
    .mockImplementation((_path, _method, body) =>
      Promise.resolve({
        attemptId: null,
        compileJobId: null,
        evidenceRefs: [],
        message: `服务端提示 ${(body as { hintLevel: number }).hintLevel}`,
      }),
    );
  const user = userEvent.setup();
  render(
    view({ ...context(), can: (name) => ['NOTEBOOK_ACTIONS', 'EXERCISE_HINTS'].includes(name) }),
  );
  await user.click(await screen.findByRole('button', { name: '查看第 1 级提示' }));
  await screen.findByRole('alert');
  expect(screen.getByRole('button', { name: '查看第 1 级提示' })).toBeEnabled();
  expect(screen.queryByRole('button', { name: '查看第 2 级提示' })).not.toBeInTheDocument();
  for (const level of [1, 2, 3]) {
    await user.click(screen.getByRole('button', { name: `查看第 ${level} 级提示` }));
    await screen.findByText(`服务端提示 ${level}`);
  }
  const complete = screen.getByRole('button', { name: '已查看全部提示' });
  expect(complete).toBeDisabled();
  await user.click(complete);
  expect(request).toHaveBeenCalledTimes(4);
  expect(request.mock.calls.map((call) => (call[2] as { hintLevel: number }).hintLevel)).toEqual([
    1, 1, 2, 3,
  ]);
});

it('locks source edits and references until a save completes, and retains edits after a failed save', async () => {
  serve();
  const firstSave = deferred<ArtifactVersion>();
  const secondSave = deferred<ArtifactVersion>();
  const request = vi
    .spyOn(api, 'request')
    .mockReturnValueOnce(firstSave.promise)
    .mockReturnValueOnce(secondSave.promise);
  const user = userEvent.setup();
  render(view(context()));
  const title = await screen.findByRole('textbox', { name: '讲义标题' });
  await waitFor(() => expect(title).toHaveValue('原稿'));
  await user.clear(title);
  await user.type(title, '待保存讲义');
  await user.click(screen.getByRole('button', { name: '引用课程知识' }));
  await screen.findByRole('option', { name: 'PECS · 生产者与消费者' });
  const sources = screen.getByRole('combobox', { name: '选择要引用的知识卡' });
  await user.selectOptions(sources, concept.id);
  await user.click(screen.getByRole('button', { name: '保存版本' }));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(1));

  expect(title).toBeDisabled();
  expect(screen.getByRole('textbox', { name: '段落 1 内容' })).toBeDisabled();
  expect(screen.getByRole('combobox', { name: '段落 1 类型' })).toBeDisabled();
  expect(sources).toBeDisabled();
  const insert = screen.getByRole('button', { name: '插入内容并保留证据引用' });
  const addParagraph = screen.getByRole('button', { name: '添加段落' });
  expect(insert).toBeDisabled();
  expect(addParagraph).toBeDisabled();
  await user.type(title, '不能在提交途中追加');
  await user.click(insert);
  await user.click(addParagraph);
  expect(title).toHaveValue('待保存讲义');
  expect(screen.queryByRole('textbox', { name: '段落 2 内容' })).not.toBeInTheDocument();
  expect(screen.getByText('1 条已固定版本的输入引用')).toBeInTheDocument();

  const sent = request.mock.calls[0][2] as {
    baseRevision: number;
    content: ArtifactVersion['content'];
    inputRefs: Reference[];
  };
  expect(sent.baseRevision).toBe(1);
  expect(sent.inputRefs).toEqual([reference]);
  await act(async () => firstSave.resolve({ ...original, ...sent, revision: 2 }));
  expect(title).toBeEnabled();
  expect(sources).toBeEnabled();
  expect(title).toHaveValue('待保存讲义');
  expect(screen.getByText('基线版本 2')).toBeInTheDocument();

  await user.type(title, '，继续修改');
  await user.click(screen.getByRole('button', { name: '保存版本' }));
  expect(title).toBeDisabled();
  await act(async () => secondSave.reject(new ApiError(409, 'VERSION_CONFLICT', '版本已变化')));
  expect(title).toBeEnabled();
  expect(title).toHaveValue('待保存讲义，继续修改');
  expect(screen.getByText('基线版本 2')).toBeInTheDocument();
  expect(screen.getByText('服务器有新版本，草稿仍保留在本标签页。')).toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: /保留本地稿并以版本 1 为基线/ }),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole('button', { name: '放弃本地修改，采用最新内容' }),
  ).not.toBeInTheDocument();
  const cached = JSON.parse(sessionStorage.getItem('tf.draft.owner.workspace-1.document-1')!);
  expect(cached.content.title).toBe('待保存讲义，继续修改');
  expect(cached.dirty).toBe(true);
  expect(request.mock.calls[1][2]).toEqual(expect.objectContaining({ baseRevision: 2 }));
});

it('ignores a pre-save GET arriving after success, including its obsolete input references', async () => {
  const lateRead = deferred<ArtifactVersion>();
  let reads = 0;
  serve(() => (++reads === 1 ? Promise.resolve(original) : lateRead.promise));
  const saved = deferred<ArtifactVersion>();
  const nextSave = deferred<ArtifactVersion>();
  const request = vi
    .spyOn(api, 'request')
    .mockReturnValueOnce(saved.promise)
    .mockReturnValueOnce(nextSave.promise);
  const user = userEvent.setup();
  const state = context();
  const rendered = render(view(state));
  const title = await screen.findByRole('textbox', { name: '讲义标题' });
  await waitFor(() => expect(title).toHaveValue('原稿'));
  await user.clear(title);
  await user.type(title, '已保存的新稿');
  await user.click(screen.getByRole('button', { name: '引用课程知识' }));
  await screen.findByRole('option', { name: 'PECS · 生产者与消费者' });
  await user.selectOptions(
    screen.getByRole('combobox', { name: '选择要引用的知识卡' }),
    concept.id,
  );
  await user.click(screen.getByRole('button', { name: '插入内容并保留证据引用' }));
  expect(screen.getByText('2 条已固定版本的输入引用')).toBeInTheDocument();

  rendered.rerender(view({ ...state, tick: 1 }));
  await waitFor(() => expect(reads).toBe(2));
  await user.click(screen.getByRole('button', { name: '保存版本' }));
  const sent = request.mock.calls[0][2] as {
    baseRevision: number;
    content: ArtifactVersion['content'];
    inputRefs: Reference[];
  };
  await act(async () => saved.resolve({ ...original, ...sent, revision: 2 }));
  expect(screen.getByText('基线版本 2')).toBeInTheDocument();
  await act(async () => lateRead.resolve({ ...original }));

  expect(title).toHaveValue('已保存的新稿');
  expect(screen.getByText('基线版本 2')).toBeInTheDocument();
  expect(screen.getByText('2 条已固定版本的输入引用')).toBeInTheDocument();
  expect(screen.queryByText('服务器有新版本，草稿仍保留在本标签页。')).not.toBeInTheDocument();
  await user.type(title, '，下一次编辑');
  await user.click(screen.getByRole('button', { name: '保存版本' }));
  expect(request.mock.calls[1][2]).toEqual(
    expect.objectContaining({
      baseRevision: 2,
      inputRefs: sent.inputRefs,
      content: expect.objectContaining({ title: '已保存的新稿，下一次编辑' }),
    }),
  );
  const nextInput = request.mock.calls[1][2] as typeof sent;
  await act(async () => nextSave.resolve({ ...original, ...nextInput, revision: 3 }));
  expect(title).toHaveValue('已保存的新稿，下一次编辑');
  expect(screen.getByText('基线版本 3')).toBeInTheDocument();
});

it('locks conflict resolution during a save and requires explicit rebasing after a real CAS conflict', async () => {
  const remoteUpdate = deferred<ArtifactVersion>();
  let reads = 0;
  serve(() => (++reads === 1 ? Promise.resolve(original) : remoteUpdate.promise));
  const rejectedSave = deferred<ArtifactVersion>();
  const rebasedSave = deferred<ArtifactVersion>();
  const request = vi
    .spyOn(api, 'request')
    .mockReturnValueOnce(rejectedSave.promise)
    .mockReturnValueOnce(rebasedSave.promise);
  const user = userEvent.setup();
  const state = context();
  const rendered = render(view(state));
  const title = await screen.findByRole('textbox', { name: '讲义标题' });
  await waitFor(() => expect(title).toHaveValue('原稿'));
  await user.clear(title);
  await user.type(title, '保留本地修改');
  await user.click(screen.getByRole('button', { name: '保存版本' }));
  rendered.rerender(view({ ...state, tick: 1 }));
  await waitFor(() => expect(reads).toBe(2));
  await act(async () => remoteUpdate.resolve({ ...original, revision: 2 }));

  const keep = screen.getByRole('button', { name: '已比较，保留本地稿并以版本 2 为基线' });
  const discard = screen.getByRole('button', { name: '放弃本地修改，采用最新内容' });
  expect(keep).toBeDisabled();
  expect(discard).toBeDisabled();
  await user.click(keep);
  await user.click(discard);
  expect(title).toHaveValue('保留本地修改');
  expect(screen.getByText('基线版本 1')).toBeInTheDocument();

  await act(async () => rejectedSave.reject(new ApiError(409, 'VERSION_CONFLICT', '版本已变化')));
  expect(keep).toBeEnabled();
  expect(screen.getByRole('button', { name: '保存版本' })).toBeDisabled();
  expect(title).toHaveValue('保留本地修改');
  await user.click(keep);
  expect(screen.getByText('基线版本 2')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '保存版本' }));
  const sent = request.mock.calls[1][2] as {
    baseRevision: number;
    content: ArtifactVersion['content'];
    inputRefs: Reference[];
  };
  expect(sent).toEqual(
    expect.objectContaining({
      baseRevision: 2,
      content: expect.objectContaining({ title: '保留本地修改' }),
    }),
  );
  await act(async () => rebasedSave.resolve({ ...original, ...sent, revision: 3 }));
  expect(title).toHaveValue('保留本地修改');
  expect(screen.getByText('基线版本 3')).toBeInTheDocument();
});
