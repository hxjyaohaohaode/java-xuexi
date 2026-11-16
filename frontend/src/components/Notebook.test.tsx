import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { ApiError, api } from '../api';
import type {
  NotebookBlockState,
  NotebookResult,
  NotebookState,
  TypeInput,
} from '../notebook-types';
import type { Artifact, ArtifactVersion, CompileJob, Exercise, InteractionBlock } from '../types';
import { TestWorkspace, deferred, testConcept, testWorkspace } from '../test/workspace';
import { NotebookWorkspace } from './Notebook';

const artifact: Artifact = {
  id: 'notebook-1',
  workspaceId: 'workspace-1',
  kind: 'NOTEBOOK',
  title: '学习交互',
  currentRevision: 2,
};
const block: InteractionBlock = {
  id: 'activity-1',
  kind: 'TYPE_FLOW',
  prompt: '改变类型并核对实际规则',
  conceptIds: ['concept-1'],
  exerciseRef: { id: 'base', revision: 1 },
  attemptId: 'attempt-origin',
  allowedActions: ['selectType', 'setParameter', 'openEvidence'],
  evidenceRefs: [{ id: 'chunk-1', revision: 2 }],
  revision: 1,
  config: {
    sourceType: 'List<Integer>',
    targetType: 'List<Number>',
    parameters: [
      { name: 'sourceType', candidates: ['List<Integer>', 'List<Number>'] },
      { name: 'targetType', candidates: ['List<Number>', 'List<? extends Number>'] },
    ],
    operations: ['ASSIGN', 'READ'],
  },
};
const version = (item: InteractionBlock): ArtifactVersion => ({
  artifactId: artifact.id,
  revision: 2,
  schemaVersion: '1.0',
  content: { kind: 'NOTEBOOK', schemaVersion: '1.0', blocks: [item] },
  contentHash: 'a'.repeat(64),
  inputRefs: [],
  reviewStatus: 'DRAFT',
});
const blank = (): NotebookBlockState => ({
  blockId: block.id,
  stateRevision: 0,
  typeInput: null,
  selectedExerciseRef: null,
  hintLevel: 0,
  lastResult: null,
});
const snapshot = (state: NotebookBlockState): NotebookState => ({
  artifactId: artifact.id,
  artifactRevision: 2,
  blocks: [state],
});
const receipt = (stateRevision: number): NotebookResult => ({
  status: 'COMPLETED',
  attemptId: null,
  compileJobId: null,
  tutorRequestId: null,
  message: '服务端已记录本次活动',
  evidenceRefs: [],
  eventId: `event-${stateRevision}`,
  artifactRevision: 2,
  blockId: block.id,
  stateRevision,
  labSession: null,
  selectedExercise: null,
  navigation: null,
  baselineCompileJobId: null,
});
function view(
  item = block,
  capabilities = [
    'NOTEBOOK_INTERACTIONS',
    'NOTEBOOK_ACTIONS',
    'FINITE_TYPE_LAB',
    'EXERCISE_HINTS',
    'TEXT_GRADING',
  ],
) {
  return render(
    <TestWorkspace value={testWorkspace(capabilities)}>
      <NotebookWorkspace
        artifact={artifact}
        version={version(item)}
        disabled={false}
        onVersion={vi.fn()}
      />
    </TestWorkspace>,
  );
}

it('waits for actual state, preserves type drafts after a conflict and sends the newly confirmed CAS revision on deliberate retry', async () => {
  const initial = deferred<NotebookState>();
  let first = true;
  let state: NotebookBlockState = { ...blank(), stateRevision: 7 };
  vi.spyOn(api, 'get').mockImplementation((path) =>
    path.startsWith('/concepts')
      ? Promise.resolve({ items: [testConcept] })
      : first
        ? ((first = false), initial.promise)
        : Promise.resolve(snapshot(state)),
  );
  const pending = deferred<NotebookResult>();
  const request = vi
    .spyOn(api, 'request')
    .mockReturnValueOnce(pending.promise)
    .mockImplementation((_path, _method, body) => {
      const input = body as { typeInput: TypeInput };
      const result = {
        ...receipt(9),
        labSession: {
          id: 'lab-1',
          releaseId: 'release-1',
          verdict: 'ALLOWED' as const,
          ruleCode: 'UPPER_READ',
          explanation: '这组实际输入满足有限类型规则。',
          rulesVersion: 'finite-v1',
          jobId: null,
        },
      };
      state = { ...state, stateRevision: 9, typeInput: input.typeInput, lastResult: result };
      return Promise.resolve(result);
    });
  const user = userEvent.setup();
  view();
  expect(screen.getByRole('button', { name: '检验选定的类型关系' })).toBeDisabled();
  await act(async () => initial.resolve(snapshot(state)));
  const target = screen.getByLabelText('推演目标类型');
  await user.selectOptions(target, 'List<? extends Number>');
  await user.click(screen.getByRole('button', { name: '检验选定的类型关系' }));
  expect(target).toBeDisabled();
  await act(async () => pending.reject(new ApiError(409, 'CONFLICT', '另一页面刚刚改变活动选择')));
  expect(target).toHaveValue('List<? extends Number>');
  state = {
    ...state,
    stateRevision: 8,
    typeInput: {
      sourceType: 'List<Number>',
      targetType: 'List<Number>',
      operation: 'ASSIGN',
      prediction: 'UNKNOWN',
    },
  };
  await user.click(screen.getByRole('button', { name: '读取最新状态与源版本' }));
  await screen.findByText('源稿版本 2 · 区块版本 1 · 已确认交互版本 8');
  expect(target).toHaveValue('List<? extends Number>');
  await user.click(screen.getByRole('button', { name: '检验选定的类型关系' }));
  await waitFor(() =>
    expect(request).toHaveBeenLastCalledWith(
      '/notebooks/notebook-1/actions',
      'POST',
      expect.objectContaining({
        action: 'selectType',
        expectedStateRevision: 8,
        typeInput: {
          sourceType: 'List<Integer>',
          targetType: 'List<? extends Number>',
          operation: 'ASSIGN',
          prediction: 'UNKNOWN',
        },
      }),
      { idempotent: true },
    ),
  );
  expect(await screen.findByRole('heading', { name: '已确认的求值结果' })).toBeInTheDocument();
});

it('switches only after a confirmed variant and restores each question draft and actual hint level independently', async () => {
  const policy = {
    version: 'text-exact-nfkc-v1' as const,
    normalization: 'NFKC_WHITESPACE' as const,
    scope: '完整类型名',
    caseSensitive: true,
    maxCharacters: 4000,
    maxUtf8Bytes: 16000,
  };
  const a: Exercise = {
    id: 'variant-a',
    revision: 1,
    releaseId: 'release-1',
    kind: 'READING',
    answerKind: 'TEXT',
    textPolicy: policy,
    prompt: '对照题甲：回答上界',
    difficulty: 1,
    conceptIds: [],
    options: [],
  };
  const b = { ...a, id: 'variant-b', prompt: '对照题乙：回答下界' };
  const item = {
    ...block,
    kind: 'COUNTERFACTUAL',
    allowedActions: ['setParameter', 'submitAnswer', 'requestHint', 'openEvidence'],
    config: {
      baseExerciseRef: block.exerciseRef,
      changeDimension: 'BOUND',
      candidateExerciseRefs: [
        { id: a.id, revision: 1 },
        { id: b.id, revision: 1 },
      ],
    },
  };
  let state: NotebookBlockState = {
    ...blank(),
    stateRevision: 5,
    selectedExerciseRef: { id: a.id, revision: 1 },
    hintLevel: 3,
  };
  vi.spyOn(api, 'get').mockImplementation((path) => {
    if (path.startsWith('/concepts')) return Promise.resolve({ items: [testConcept] });
    if (path.includes('/block-options?'))
      return Promise.resolve({ counterfactualCandidates: [a, b] });
    if (path.startsWith('/exercises/')) return Promise.resolve(path.includes(a.id) ? a : b);
    return Promise.resolve(snapshot(state));
  });
  const request = vi.spyOn(api, 'request').mockImplementation((_path, _method, body) => {
    const selected = (body as { exerciseRef: { id: string; revision: number } }).exerciseRef;
    const result = {
      ...receipt(state.stateRevision + 1),
      selectedExercise: selected.id === a.id ? a : b,
    };
    state = {
      ...state,
      stateRevision: result.stateRevision,
      selectedExerciseRef: selected,
      hintLevel: selected.id === a.id ? 3 : 0,
      lastResult: result,
    };
    return Promise.resolve(result);
  });
  const user = userEvent.setup();
  view(item);
  const input = await screen.findByLabelText('重新写下文本答案');
  await user.type(input, '甲的独立草稿');
  expect(screen.getByRole('button', { name: '已查看全部提示' })).toBeDisabled();
  await user.selectOptions(screen.getByLabelText('选择这个区块允许的对照题'), 'variant-b:1');
  expect(input).toHaveValue('甲的独立草稿');
  await user.click(screen.getByRole('button', { name: '确认载入这道对照题' }));
  await screen.findByText(b.prompt);
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '查看第 1 级提示' })).toBeEnabled(),
  );
  expect(screen.getByLabelText('重新写下文本答案')).toHaveValue('');
  await user.type(screen.getByLabelText('重新写下文本答案'), '乙的独立草稿');
  await user.selectOptions(screen.getByLabelText('选择这个区块允许的对照题'), 'variant-a:1');
  await user.click(screen.getByRole('button', { name: '确认载入这道对照题' }));
  await screen.findByText(a.prompt);
  expect(screen.getByLabelText('重新写下文本答案')).toHaveValue('甲的独立草稿');
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '已查看全部提示' })).toBeDisabled(),
  );
  expect(request).toHaveBeenCalledTimes(2);
});

it('reads real evidence errors explicitly and never renders a success placeholder as a citation', async () => {
  let state = blank();
  vi.spyOn(api, 'get').mockImplementation((path) =>
    path.startsWith('/concepts')
      ? Promise.reject(new ApiError(503, 'EVIDENCE_UNAVAILABLE', '证据读取暂不可用'))
      : Promise.resolve(snapshot(state)),
  );
  vi.spyOn(api, 'request').mockImplementation(() => {
    const result = { ...receipt(1), evidenceRefs: [{ id: 'chunk-1', revision: 2 }] };
    state = { ...state, stateRevision: 1, lastResult: result };
    return Promise.resolve(result);
  });
  const user = userEvent.setup();
  view({ ...block, kind: 'EXPERT_HELP', allowedActions: ['openEvidence'], config: {} });
  const open = screen.getByRole('button', { name: '查看关联证据' });
  await waitFor(() => expect(open).toBeEnabled());
  await user.click(open);
  await screen.findByText('证据读取暂不可用');
  expect(document.querySelector('.notebook-evidence .evidence-card')).toBeNull();
  expect(screen.getByRole('button', { name: '重新读取' })).toBeEnabled();
});

it('does not present executable typed actions for legacy blocks that never admitted those actions', async () => {
  vi.spyOn(api, 'get').mockImplementation((path) =>
    Promise.resolve(path.startsWith('/concepts') ? { items: [] } : snapshot(blank())),
  );
  const request = vi.spyOn(api, 'request');
  const user = userEvent.setup();
  view({
    ...block,
    allowedActions: ['predict', 'requestHint'],
    config: { ...block.config, parameters: [{ name: 'T', candidates: ['Integer', 'Number'] }] },
  });
  await screen.findByText(
    '这个历史区块未声明完整类型交互。请从来源练习添加新的“类型关系推演”活动；旧区块的已有操作与证据仍保留。',
  );
  const action = screen.getByRole('button', { name: '检验选定的类型关系' });
  expect(action).toBeDisabled();
  await user.click(action);
  expect(request).not.toHaveBeenCalled();
});

it('keeps the fixed repair source separate, waits for both job results and never treats compilation as a graded answer', async () => {
  const original = 'public class Main { int value = "wrong"; }';
  const corrected = 'public class Main { int value = 1; }';
  const acknowledgement = deferred<NotebookResult>();
  const revisedJob = deferred<CompileJob>();
  let state = blank();
  const job: CompileJob = {
    id: 'original-job',
    workspaceId: 'workspace-1',
    releaseId: 'release-1',
    attemptId: null,
    status: 'COMPILE_ERROR',
    sourceHash: 'b'.repeat(64),
    jdkVersion: '17',
    diagnostics: [
      {
        code: 'compiler.err',
        severity: 'ERROR',
        line: 1,
        column: 26,
        message: '固定原稿的类型错误',
      },
    ],
    output: '',
    outputTruncated: false,
    errorCode: null,
    createdAt: '2026-11-15T00:00:00Z',
    finishedAt: '2026-11-15T00:00:01Z',
  };
  vi.spyOn(api, 'get').mockImplementation((path) => {
    if (path.startsWith('/exercises/'))
      return Promise.resolve({
        id: 'base',
        familyId: 'family-1',
        kind: 'REFACTOR',
        answerKind: 'CODE',
        prompt: '修正类型错误',
        options: [],
        revision: 1,
      });
    if (path.startsWith('/concepts')) return Promise.resolve({ items: [testConcept] });
    if (path === '/compile-jobs/original-job') return Promise.resolve(job);
    if (path === '/compile-jobs/revised-job') return revisedJob.promise;
    return Promise.resolve(snapshot(state));
  });
  vi.spyOn(api, 'request').mockImplementation(() =>
    acknowledgement.promise.then((result) => {
      state = { ...state, stateRevision: result.stateRevision, lastResult: result };
      return result;
    }),
  );
  const user = userEvent.setup();
  view(
    {
      ...block,
      kind: 'REPAIR_DIFF',
      allowedActions: ['requestCompile'],
      config: { originalSource: original },
    },
    ['NOTEBOOK_ACTIONS', 'NOTEBOOK_INTERACTIONS', 'COMPILE_ONLY'],
  );
  const input = await screen.findByLabelText(/修订代码 · Main.java/);
  await waitFor(() => expect(input).toBeEnabled());
  await user.clear(input);
  await user.click(input);
  await user.paste(corrected);
  await user.click(screen.getByRole('button', { name: '分别检查原稿与修订稿编译' }));
  expect(input).toBeDisabled();
  await act(async () =>
    acknowledgement.resolve({
      ...receipt(1),
      status: 'ACCEPTED',
      baselineCompileJobId: 'original-job',
      compileJobId: 'revised-job',
    }),
  );
  await screen.findByText('固定原稿的类型错误');
  expect(input).toBeDisabled();
  expect(screen.getByLabelText('修订对照的原始代码')).toHaveTextContent(original);
  await act(async () =>
    revisedJob.resolve({
      ...job,
      id: 'revised-job',
      status: 'SUCCEEDED',
      sourceHash: 'c'.repeat(64),
      diagnostics: [],
    }),
  );
  await screen.findByText('编译已完成，没有返回诊断。');
  await waitFor(() => expect(input).toBeEnabled());
  expect(document.querySelector('.feedback')).toBeNull();
  expect(screen.getByRole('button', { name: '提交复练' })).toBeDisabled();
  await user.clear(input);
  await user.click(input);
  await user.paste('public class Main { int value = 2; }');
  await screen.findByText('当前代码有未编译的修改，以下结果对应上次提交的源码。');
  expect(screen.getByLabelText('修订对照的原始代码')).toHaveTextContent(original);
});
