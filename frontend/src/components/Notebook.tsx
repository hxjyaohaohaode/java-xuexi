import { useEffect, useRef, useState } from 'react';
import { ArrowLeft, ArrowRight, Layers3, Plus, RefreshCw } from 'lucide-react';
import { ApiError, api } from '../api';
import { exerciseAnswerKind, validTextAnswer } from '../answers';
import { useWorkspace } from '../context';
import { useMutation, usePolling, useResource, useSessionDraft, useUnsavedChanges } from '../hooks';
import type {
  ActivityKind,
  NotebookBlockState,
  NotebookExtensions,
  NotebookOptions,
  NotebookResult,
  NotebookState,
  TypeFlowConfig,
  TypeInput,
} from '../notebook-types';
import type {
  Artifact,
  ArtifactVersion,
  Attempt,
  CompileJob,
  Concept,
  ContentRef,
  Exercise,
  InteractionBlock,
  Review,
} from '../types';
import { CompileDiagnostics, jobPending, starterSource } from './Compiler';
import { TextAnswer } from './TextAnswer';
import {
  Badge,
  Confirmed,
  Dialog,
  Empty,
  ErrorNotice,
  EvidenceList,
  Field,
  Loading,
  Status,
} from './ui';

const activities: Record<ActivityKind, { title: string; description: string }> = {
  TYPE_FLOW: {
    title: '类型关系推演',
    description: '在固定候选中改变类型或操作，读取有限规则求值结果。',
  },
  REPAIR_DIFF: {
    title: '代码修订对照',
    description: '保留原始代码，分别编译原稿与修订稿。需要代码来源。',
  },
  COUNTERFACTUAL: {
    title: '同题族对照',
    description: '选择当前课程内实际审核过的变式，再进行辅助复练。',
  },
  EXPERT_HELP: {
    title: '逐层引导',
    description: '阅读来源题目的三级提示；使用已编写或确定性提示。',
  },
  REVIEW_LINKS: {
    title: '复习与作品关联',
    description: '固定关联本人复习记录或作品版本，核验后再打开。',
  },
};
const blockTitle = (kind: string) =>
  kind === 'ERROR_CONTEXT'
    ? '重新检查你的判断'
    : activities[kind as ActivityKind]?.title || '学习交互';
const emptyState = (blockId: string): NotebookBlockState => ({
  blockId,
  stateRevision: 0,
  typeInput: null,
  selectedExerciseRef: null,
  hintLevel: 0,
  lastResult: null,
});
const refKey = (ref: ContentRef) => `${ref.id}:${ref.revision}`;
const refs = (value: unknown): ContentRef[] =>
  Array.isArray(value)
    ? value.filter(
        (item): item is ContentRef =>
          !!item && typeof item.id === 'string' && Number.isInteger(item.revision),
      )
    : [];
type Dispatch = (
  block: InteractionBlock,
  action: string,
  extensions?: NotebookExtensions,
) => Promise<NotebookResult | undefined>;

/** Source revisions and interaction revisions are distinct; neither is inferred from a local draft. */
export function NotebookWorkspace({
  artifact,
  version,
  disabled,
  onVersion,
  focusBlockId,
}: {
  artifact: Artifact;
  version: ArtifactVersion;
  disabled: boolean;
  onVersion: (value: ArtifactVersion) => void;
  focusBlockId?: string;
}) {
  const { workspace, can, refresh, navigate } = useWorkspace();
  const enabled = can('NOTEBOOK_INTERACTIONS');
  const blocks =
    version.content.kind === 'NOTEBOOK' || version.content.kind === 'INTERACTION'
      ? version.content.blocks
      : [];
  const current = useResource<NotebookState>(
    enabled ? `/notebooks/${artifact.id}/state?artifactRevision=${version.revision}` : null,
  );
  const concepts = useResource<{ items: Concept[] }>(`/concepts?releaseId=${workspace.releaseId}`);
  const [acknowledged, setAcknowledged] = useState<Record<string, NotebookBlockState>>({});
  const [adding, setAdding] = useState(false);
  const [appendBusy, setAppendBusy] = useState(false);
  const mutation = useMutation();
  const container = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!focusBlockId) return;
    const timer = setTimeout(() => {
      const target = Array.from(
        container.current?.querySelectorAll<HTMLElement>('[data-notebook-block]') || [],
      ).find((item) => item.dataset.notebookBlock === focusBlockId);
      target?.focus({ preventScroll: true });
      target?.scrollIntoView?.({ block: 'nearest', behavior: 'instant' });
    }, 0);
    return () => clearTimeout(timer);
  }, [focusBlockId]);
  const states = Object.fromEntries(
    blocks.map((block) => {
      const remote =
        current.data?.blocks.find((item) => item.blockId === block.id) || emptyState(block.id);
      const local = acknowledged[block.id];
      return [block.id, local && local.stateRevision > remote.stateRevision ? local : remote];
    }),
  );
  const busy = mutation.busy || appendBusy;
  const sourceConflict = current.error instanceof ApiError && current.error.status === 409;
  const unavailable = disabled || (enabled && (!current.data || !!current.error));
  const dispatch: Dispatch = async (block, action, extensions = {}) => {
    if (unavailable || busy || !block.allowedActions.includes(action)) return undefined;
    const previous = states[block.id];
    return mutation.run(
      () =>
        api.request<NotebookResult>(
          `/notebooks/${artifact.id}/actions`,
          'POST',
          {
            artifactRevision: version.revision,
            blockId: block.id,
            action,
            expectedBlockRevision: block.revision,
            ...(enabled ? { expectedStateRevision: previous.stateRevision } : {}),
            answer: null,
            selection: '',
            ...extensions,
          },
          { idempotent: true },
        ),
      (result) => {
        const typeInput =
          extensions.typeInput ||
          (extensions.parameterInput
            ? ({
                ...(previous.typeInput || {
                  sourceType: String(block.config.sourceType || ''),
                  targetType: String(block.config.targetType || ''),
                }),
                [extensions.parameterInput.name]: extensions.parameterInput.value,
                operation: extensions.parameterInput.operation,
                prediction: extensions.parameterInput.prediction,
              } as TypeInput)
            : previous.typeInput);
        setAcknowledged((before) => ({
          ...before,
          [block.id]: {
            ...previous,
            stateRevision: result.stateRevision ?? previous.stateRevision + 1,
            typeInput,
            selectedExerciseRef: extensions.exerciseRef || previous.selectedExerciseRef,
            hintLevel:
              action === 'requestHint'
                ? Math.max(previous.hintLevel, extensions.hintLevel || 1)
                : previous.hintLevel,
            lastResult: result,
          },
        }));
        if (enabled) current.reload();
        refresh();
        if (result.navigation) navigate('studio', result.navigation.artifactId);
      },
    );
  };
  return (
    <div className="notebook-workspace" ref={container}>
      <div className="notebook-overview">
        <div>
          <h3>
            <Layers3 size={19} /> 从原始证据继续探索
          </h3>
          <p className="muted small">
            每个活动有独立状态。预测、提示与实验保留为探索记录，复练由原题目规则判分，不增加独立掌握样本。
          </p>
        </div>
        {enabled && (
          <button
            disabled={
              unavailable ||
              busy ||
              blocks.length >= 100 ||
              !blocks.some((block) => block.exerciseRef && block.kind !== 'EXPERT_HELP')
            }
            onClick={() => setAdding(true)}
          >
            <Plus size={16} /> 添加学习活动
          </button>
        )}
      </div>
      {current.loading && !current.data && <Loading label="正在恢复已确认的活动选择与提示进度…" />}
      <ErrorNotice error={current.error} retry={current.reload} />
      <ErrorNotice error={mutation.error} />
      {(sourceConflict ||
        (mutation.error instanceof ApiError && mutation.error.status === 409)) && (
        <div className="notice warning">
          <div>
            <strong>交互或作品版本已更新，当前输入仍保留。</strong>
            <p>读取服务端状态后核对实际选择，再重新提交；不会自动套用旧题答案。</p>
            <button
              disabled={busy || current.loading}
              onClick={() => {
                current.reload();
                refresh();
                mutation.clearError();
              }}
            >
              <RefreshCw size={15} /> 读取最新状态与源版本
            </button>
          </div>
        </div>
      )}
      {blocks.map((block) => (
        <NotebookActivity
          key={block.id}
          artifact={artifact}
          version={version}
          block={block}
          state={states[block.id]}
          disabled={unavailable || busy || (!enabled && block.kind !== 'ERROR_CONTEXT')}
          hintSyncing={
            block.kind === 'COUNTERFACTUAL' &&
            !!states[block.id].lastResult?.selectedExercise &&
            (current.data?.blocks.find((item) => item.blockId === block.id)?.stateRevision ?? -1) <
              states[block.id].stateRevision
          }
          dispatch={dispatch}
          concepts={concepts.data?.items || []}
          evidenceError={concepts.error}
          reloadEvidence={concepts.reload}
        />
      ))}
      {adding && (
        <Dialog
          title="添加来源可核验的学习活动"
          onClose={() => {
            if (!appendBusy) setAdding(false);
          }}
        >
          <ActivityBuilder
            artifact={artifact}
            version={version}
            blocks={blocks}
            onBusy={setAppendBusy}
            onClose={() => setAdding(false)}
            onSaved={onVersion}
          />
        </Dialog>
      )}
    </div>
  );
}

function ActivityBuilder({
  artifact,
  version,
  blocks,
  onBusy,
  onClose,
  onSaved,
}: {
  artifact: Artifact;
  version: ArtifactVersion;
  blocks: InteractionBlock[];
  onBusy: (value: boolean) => void;
  onClose: () => void;
  onSaved: (value: ArtifactVersion) => void;
}) {
  const { session, workspace } = useWorkspace();
  const origins = blocks.filter((block) => block.exerciseRef && block.kind !== 'EXPERT_HELP');
  const prefix = `tf.notebook-builder.${session.userId}.${workspace.id}.${artifact.id}.${version.revision}`;
  const [originId, setOriginId] = useSessionDraft(`${prefix}.origin`, origins[0]?.id || '');
  const origin = origins.find((item) => item.id === originId) || origins[0];
  const options = useResource<NotebookOptions>(
    origin
      ? `/notebooks/${artifact.id}/block-options?artifactRevision=${version.revision}&originBlockId=${encodeURIComponent(origin.id)}`
      : null,
  );
  const [kind, setKind] = useSessionDraft<ActivityKind>(`${prefix}.kind`, 'TYPE_FLOW');
  const [dimension, setDimension] = useSessionDraft(`${prefix}.dimension`, 'BOUND');
  const [candidates, setCandidates] = useSessionDraft<string[]>(`${prefix}.candidates`, []);
  const [linked, setLinked] = useSessionDraft<string[]>(`${prefix}.linked`, []);
  const [includeReviews, setIncludeReviews] = useSessionDraft(`${prefix}.reviews`, true);
  const mutation = useMutation();
  useEffect(() => {
    onBusy(mutation.busy);
    return () => onBusy(false);
  }, [mutation.busy]);
  const actualCandidates =
    options.data?.counterfactualCandidates.filter((item) => candidates.includes(refKey(item))) ||
    [];
  const actualLinks =
    options.data?.artifactCandidates.filter((item) =>
      linked.includes(`${item.artifactId}:${item.revision}`),
    ) || [];
  const actualReviews = includeReviews ? options.data?.reviewIds || [] : [];
  const enabled = !!options.data?.availableKinds.includes(kind);
  const valid =
    enabled &&
    (kind !== 'COUNTERFACTUAL' || actualCandidates.length > 0) &&
    (kind !== 'REVIEW_LINKS' || actualLinks.length + actualReviews.length > 0);
  return (
    <div className="notebook-activity-builder">
      <h2>把一次作答展开为学习活动</h2>
      <p className="muted">
        活动引用真实题目与固定来源，保存后生成新的作品版本。添加活动不会修改原始成绩。
      </p>
      <fieldset disabled={mutation.busy}>
        <Field label="来源练习区块">
          <select value={origin?.id || ''} onChange={(event) => setOriginId(event.target.value)}>
            {origins.map((block, index) => (
              <option value={block.id} key={block.id}>
                {index + 1}. {blockTitle(block.kind)} · {block.prompt.slice(0, 80)}
              </option>
            ))}
          </select>
        </Field>
        <ErrorNotice error={options.error} retry={options.reload} />
        {options.loading && <Loading label="正在读取这道题可用的活动与真实关联…" />}
        <div className="notebook-kind-options" role="group" aria-label="学习活动类型">
          {(Object.keys(activities) as ActivityKind[]).map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={kind === value}
              disabled={!options.data?.availableKinds.includes(value)}
              onClick={() => setKind(value)}
            >
              <strong>{activities[value].title}</strong>
              <span>{activities[value].description}</span>
              {!options.data?.availableKinds.includes(value) && (
                <small>此来源暂无所需的已审核题目、代码或关联记录</small>
              )}
            </button>
          ))}
        </div>
        {kind === 'TYPE_FLOW' && options.data && (
          <p className="notice">
            初始关系：<code>{options.data.typeFlowDefaults.sourceType}</code> →{' '}
            <code>{options.data.typeFlowDefaults.targetType}</code>
            。保存后可在该区块的有限候选中逐项调整。
          </p>
        )}
        {kind === 'COUNTERFACTUAL' && (
          <>
            <Field label="你准备比较的变化维度">
              <select value={dimension} onChange={(event) => setDimension(event.target.value)}>
                <option value="BOUND">类型边界</option>
                <option value="ARGUMENT">类型参数</option>
                <option value="RETURN">返回类型</option>
                <option value="WILDCARD">通配符</option>
              </select>
            </Field>
            <p className="muted small">
              这是你的比较标记；系统仅保证同题族、同课程版本和审核状态，不证明只发生了一个维度的变化。
            </p>
            <fieldset>
              <legend>选择实际对照题</legend>
              {options.data?.counterfactualCandidates.map((item) => (
                <label className="check-row notebook-candidate" key={refKey(item)}>
                  <input
                    type="checkbox"
                    checked={candidates.includes(refKey(item))}
                    onChange={(event) =>
                      setCandidates((before) =>
                        event.target.checked
                          ? [...before, refKey(item)]
                          : before.filter((value) => value !== refKey(item)),
                      )
                    }
                  />
                  <span>
                    {item.prompt}
                    <small>
                      版本 {item.revision} · 难度 {item.difficulty}
                    </small>
                  </span>
                </label>
              ))}
            </fieldset>
          </>
        )}
        {kind === 'REVIEW_LINKS' && (
          <div className="stack">
            <label className="check-row">
              <input
                type="checkbox"
                checked={includeReviews}
                disabled={!options.data?.reviewIds.length}
                onChange={(event) => setIncludeReviews(event.target.checked)}
              />{' '}
              包含来源作答的 {options.data?.reviewIds.length || 0} 条复习记录
            </label>
            <fieldset>
              <legend>明确选择固定版本的本人作品</legend>
              {options.data?.artifactCandidates.map((item) => {
                const key = `${item.artifactId}:${item.revision}`;
                return (
                  <label className="check-row" key={key}>
                    <input
                      type="checkbox"
                      checked={linked.includes(key)}
                      onChange={(event) =>
                        setLinked((before) =>
                          event.target.checked
                            ? [...before, key]
                            : before.filter((value) => value !== key),
                        )
                      }
                    />
                    <span>
                      {item.title} · 版本 {item.revision}
                    </span>
                  </label>
                );
              })}
            </fieldset>
          </div>
        )}
        <ErrorNotice error={mutation.error} />
        <div className="row wrap notebook-builder-actions">
          <button type="button" onClick={onClose}>
            保留选择，暂不添加
          </button>
          <button
            className="primary"
            disabled={!valid || mutation.busy || options.loading}
            onClick={() =>
              void mutation.run(
                () => {
                  const config =
                    kind === 'COUNTERFACTUAL'
                      ? {
                          baseExerciseRef: origin!.exerciseRef,
                          changeDimension: dimension,
                          candidateExerciseRefs: actualCandidates.map(({ id, revision }) => ({
                            id,
                            revision,
                          })),
                        }
                      : kind === 'REVIEW_LINKS'
                        ? {
                            reviewIds: actualReviews,
                            artifactRefs: actualLinks.map(({ artifactId: id, revision }) => ({
                              id,
                              revision,
                            })),
                            conceptIds: origin!.conceptIds,
                          }
                        : undefined;
                  return api.request<ArtifactVersion>(
                    `/notebooks/${artifact.id}/blocks`,
                    'POST',
                    {
                      artifactRevision: version.revision,
                      originBlockId: origin!.id,
                      kind,
                      ...(config ? { config } : {}),
                    },
                    { idempotent: true },
                  );
                },
                (value) => {
                  onSaved(value);
                  onClose();
                },
              )
            }
          >
            {mutation.busy ? '正在保存新的作品版本…' : '保存并添加活动'}
          </button>
        </div>
      </fieldset>
    </div>
  );
}

function NotebookActivity({
  artifact,
  version,
  block,
  state,
  disabled,
  dispatch,
  concepts,
  evidenceError,
  reloadEvidence,
  hintSyncing,
}: {
  artifact: Artifact;
  version: ArtifactVersion;
  block: InteractionBlock;
  state: NotebookBlockState;
  disabled: boolean;
  dispatch: Dispatch;
  concepts: Concept[];
  evidenceError: unknown;
  reloadEvidence: () => void;
  hintSyncing: boolean;
}) {
  const { workspace, session, can, navigate } = useWorkspace();
  const [prediction, setPrediction] = useSessionDraft(
    `tf.notebook-prediction.${session.userId}.${workspace.id}.${artifact.id}.${block.id}`,
    '',
  );
  const [showEvidence, setShowEvidence] = useState(false);
  const seen = useRef<NotebookResult | null>(null);
  useEffect(() => {
    if (state.lastResult && state.lastResult !== seen.current) {
      seen.current = state.lastResult;
      if (state.lastResult.evidenceRefs?.length) setShowEvidence(true);
    }
  }, [state.lastResult]);
  const effectiveExercise =
    block.kind === 'COUNTERFACTUAL' ? state.selectedExerciseRef : block.exerciseRef;
  const evidence = concepts
    .flatMap((concept) => concept.evidence)
    .filter((item) =>
      block.evidenceRefs.some((ref) => ref.id === item.chunkId && ref.revision === item.revision),
    );
  const guidance = block.kind === 'EXPERT_HELP';
  const hasAnswer =
    block.allowedActions.includes('submitAnswer') ||
    block.allowedActions.includes('requestCompile');
  const hintLevel = Math.min(state.hintLevel + 1, 3);
  return (
    <section
      className={`notebook-block notebook-${block.kind.toLowerCase()}`}
      aria-label={blockTitle(block.kind)}
      data-notebook-block={block.id}
      tabIndex={-1}
    >
      <div className="row spread wrap">
        <h3>{blockTitle(block.kind)}</h3>
        <Badge>{block.kind === 'ERROR_CONTEXT' ? '关联真实练习' : '来源固定的学习活动'}</Badge>
      </div>
      <p className="question-prompt">{block.prompt}</p>
      {typeof block.config.studentReason === 'string' && (
        <details>
          <summary>上次作答上下文</summary>
          <p className="preserve-text">{block.config.studentReason}</p>
        </details>
      )}
      {block.allowedActions.includes('predict') && (
        <div className="notebook-prediction">
          <Field label="先写下这次的预测或理由">
            <textarea
              rows={3}
              maxLength={1000}
              value={prediction}
              onChange={(event) => setPrediction(event.target.value)}
              disabled={disabled}
            />
          </Field>
          <button
            disabled={disabled || !prediction.trim()}
            onClick={() => void dispatch(block, 'predict', { selection: prediction })}
          >
            记录预测
          </button>
        </div>
      )}
      {block.kind === 'TYPE_FLOW' && (
        <TypeFlowActivity
          key={`${artifact.id}:${block.id}`}
          block={block}
          state={state}
          disabled={disabled}
          dispatch={dispatch}
        />
      )}
      {block.kind === 'COUNTERFACTUAL' && (
        <VariantSelector
          artifact={artifact}
          block={block}
          version={version}
          state={state}
          disabled={disabled}
          dispatch={dispatch}
        />
      )}
      {block.kind === 'REVIEW_LINKS' && (
        <ReviewLinks block={block} disabled={disabled} dispatch={dispatch} />
      )}
      {guidance && (
        <p className="notice">
          这里提供题目分级引导，不会自动调用外部模型。已查看的最高提示层级会在刷新后恢复。
        </p>
      )}
      {hasAnswer && effectiveExercise && (
        <NotebookAnswer
          key={refKey(effectiveExercise)}
          artifact={artifact}
          version={version}
          block={block}
          exerciseRef={effectiveExercise}
          state={state}
          disabled={disabled}
          dispatch={dispatch}
        />
      )}
      {block.kind === 'COUNTERFACTUAL' && !effectiveExercise && (
        <p className="muted">确认一个对照题后，作答区和提示入口才会开放。</p>
      )}
      <div className="row wrap notebook-action-row">
        {block.allowedActions.includes('requestHint') && can('EXERCISE_HINTS') && (
          <button
            disabled={disabled || hintSyncing || state.hintLevel >= 3 || !effectiveExercise}
            onClick={() => void dispatch(block, 'requestHint', { hintLevel })}
          >
            {hintSyncing
              ? '正在恢复这道题的提示进度…'
              : state.hintLevel >= 3
                ? '已查看全部提示'
                : `查看第 ${hintLevel} 级提示`}
          </button>
        )}
        {block.attemptId && block.kind === 'ERROR_CONTEXT' && can('STUDY_WORKFLOW') && (
          <button disabled={disabled} onClick={() => navigate('agents', block.attemptId!)}>
            为这道错题创建学习任务
          </button>
        )}
        {block.allowedActions.includes('openEvidence') && (
          <button
            disabled={disabled}
            onClick={() =>
              void dispatch(block, 'openEvidence').then((value) => {
                if (value) setShowEvidence(true);
              })
            }
          >
            查看关联证据
          </button>
        )}
      </div>
      {state.lastResult?.message && <Confirmed>{state.lastResult.message}</Confirmed>}
      {showEvidence && (
        <div className="notebook-evidence">
          <ErrorNotice error={evidenceError} retry={reloadEvidence} />
          {evidence.length ? (
            <EvidenceList items={evidence} />
          ) : (
            !evidenceError && (
              <Empty title="未读取到匹配的知识引用">
                请重新读取课程证据并检查源稿是否失效。
                <button onClick={reloadEvidence}>重新读取课程证据</button>
              </Empty>
            )
          )}
        </div>
      )}
      <p className="muted small notebook-state-caption">
        源稿版本 {version.revision} · 区块版本 {block.revision} · 已确认交互版本{' '}
        {state.stateRevision}
      </p>
    </section>
  );
}

function TypeFlowActivity({
  block,
  state,
  disabled,
  dispatch,
}: {
  block: InteractionBlock;
  state: NotebookBlockState;
  disabled: boolean;
  dispatch: Dispatch;
}) {
  const { session, workspace, can } = useWorkspace();
  const config = block.config as unknown as TypeFlowConfig;
  const defaults: TypeInput = {
    sourceType: config.sourceType,
    targetType: config.targetType,
    operation: config.operations[0] || 'ASSIGN',
    prediction: 'UNKNOWN',
  };
  const [draft, setDraft] = useSessionDraft(
    `tf.notebook-type.${session.userId}.${workspace.id}.${block.id}`,
    { value: state.typeInput || defaults, dirty: false },
  );
  const [parameter, setParameter] = useState<'sourceType' | 'targetType'>('targetType');
  const [parameterValue, setParameterValue] = useState('');
  useEffect(() => {
    if (!draft.dirty && state.typeInput) setDraft({ value: state.typeInput, dirty: false });
  }, [state.typeInput]);
  const candidates = (name: 'sourceType' | 'targetType') => [
    ...new Set([
      config[name],
      ...config.parameters.filter((item) => item.name === name).flatMap((item) => item.candidates),
    ]),
  ];
  const parameters = config.parameters
    .filter((item) => item.name === parameter)
    .flatMap((item) => item.candidates);
  const selectedParameter = parameters.includes(parameterValue)
    ? parameterValue
    : parameters[0] || '';
  const result = state.lastResult?.labSession;
  const confirmed = state.typeInput;
  const valid =
    config.operations.includes(draft.value.operation) &&
    candidates('sourceType').includes(draft.value.sourceType) &&
    candidates('targetType').includes(draft.value.targetType);
  return (
    <div className="notebook-type-flow">
      <fieldset disabled={disabled || !can('FINITE_TYPE_LAB')}>
        <div className="type-flow-input">
          <Field label="推演源类型">
            <select
              value={draft.value.sourceType}
              onChange={(event) =>
                setDraft({ value: { ...draft.value, sourceType: event.target.value }, dirty: true })
              }
            >
              {candidates('sourceType').map((value) => (
                <option key={value}>{value}</option>
              ))}
            </select>
          </Field>
          {draft.value.operation === 'WRITE' ? <ArrowLeft size={18} /> : <ArrowRight size={18} />}
          <Field label="推演目标类型">
            <select
              value={draft.value.targetType}
              onChange={(event) =>
                setDraft({ value: { ...draft.value, targetType: event.target.value }, dirty: true })
              }
            >
              {candidates('targetType').map((value) => (
                <option key={value}>{value}</option>
              ))}
            </select>
          </Field>
        </div>
        <div className="form-grid">
          <Field label="推演操作">
            <select
              value={draft.value.operation}
              onChange={(event) =>
                setDraft({
                  value: {
                    ...draft.value,
                    operation: event.target.value as TypeInput['operation'],
                  },
                  dirty: true,
                })
              }
            >
              {config.operations.map((value) => (
                <option key={value} value={value}>
                  {value === 'ASSIGN'
                    ? '赋值：源类型 → 目标类型'
                    : value === 'READ'
                      ? '读取：源容器 → 接收值类型'
                      : '写入：向源容器写入目标值类型'}
                </option>
              ))}
            </select>
          </Field>
          <Field label="类型关系预测">
            <select
              value={draft.value.prediction}
              onChange={(event) =>
                setDraft({
                  value: {
                    ...draft.value,
                    prediction: event.target.value as TypeInput['prediction'],
                  },
                  dirty: true,
                })
              }
            >
              <option value="UNKNOWN">暂不确定</option>
              <option value="ALLOWED">允许</option>
              <option value="DENIED">拒绝</option>
            </select>
          </Field>
        </div>
        <button
          className="primary"
          disabled={!valid || !block.allowedActions.includes('selectType')}
          onClick={() =>
            void dispatch(block, 'selectType', { typeInput: draft.value }).then((value) => {
              if (value) setDraft({ value: draft.value, dirty: false });
            })
          }
        >
          检验选定的类型关系
        </button>
        <details className="notebook-parameter">
          <summary>基于上次确认的关系，只改变一个参数</summary>
          <p className="muted small">
            其余类型沿用已确认关系；未进行过推演时沿用区块初始值。操作和预测使用上方当前选择。
          </p>
          <div className="form-grid">
            <Field label="要改变的类型参数">
              <select
                value={parameter}
                onChange={(event) => setParameter(event.target.value as typeof parameter)}
              >
                <option value="sourceType">源类型</option>
                <option value="targetType">目标类型</option>
              </select>
            </Field>
            <Field label="参数的新取值">
              <select
                value={selectedParameter}
                onChange={(event) => setParameterValue(event.target.value)}
              >
                {parameters.map((value) => (
                  <option key={value}>{value}</option>
                ))}
              </select>
            </Field>
          </div>
          <button
            disabled={!selectedParameter || !block.allowedActions.includes('setParameter')}
            onClick={() =>
              void dispatch(block, 'setParameter', {
                parameterInput: {
                  name: parameter,
                  value: selectedParameter,
                  operation: draft.value.operation,
                  prediction: draft.value.prediction,
                },
              }).then((value) => {
                if (value)
                  setDraft({
                    value: {
                      ...(state.typeInput || defaults),
                      [parameter]: selectedParameter,
                      operation: draft.value.operation,
                      prediction: draft.value.prediction,
                    },
                    dirty: false,
                  });
              })
            }
          >
            只改变此参数并检验
          </button>
        </details>
      </fieldset>
      {!can('FINITE_TYPE_LAB') && (
        <p className="notice warning">当前服务未启用有限类型求值，输入保留。</p>
      )}
      {(!block.allowedActions.includes('selectType') ||
        !block.allowedActions.includes('setParameter')) && (
        <p className="notice">
          这个历史区块未声明完整类型交互。请从来源练习添加新的“类型关系推演”活动；旧区块的已有操作与证据仍保留。
        </p>
      )}
      {result && confirmed && (
        <section className="notebook-type-result" aria-live="polite">
          <div className="row spread wrap">
            <h4>已确认的求值结果</h4>
            <Badge
              tone={
                result.verdict === 'ALLOWED' ? 'good' : result.verdict === 'DENIED' ? 'bad' : 'warn'
              }
            >
              {result.verdict === 'ALLOWED'
                ? '允许'
                : result.verdict === 'DENIED'
                  ? '拒绝'
                  : '超出规则范围'}
            </Badge>
          </div>
          <p>
            <code>{confirmed.sourceType}</code> {confirmed.operation === 'WRITE' ? '←' : '→'}{' '}
            <code>{confirmed.targetType}</code> · {confirmed.operation}
          </p>
          <p className="preserve-text">{result.explanation}</p>
          <p className="muted small">
            规则 {result.ruleCode} · 求值器 {result.rulesVersion}。这是有限规则推演，不是 Java
            编译器。
          </p>
          {draft.dirty && (
            <p className="muted small">上方还有未核验的修改；这里仍显示上次已确认的输入与结果。</p>
          )}
        </section>
      )}
    </div>
  );
}

function VariantSelector({
  artifact,
  version,
  block,
  state,
  disabled,
  dispatch,
}: {
  artifact: Artifact;
  version: ArtifactVersion;
  block: InteractionBlock;
  state: NotebookBlockState;
  disabled: boolean;
  dispatch: Dispatch;
}) {
  const { workspace } = useWorkspace();
  const candidates = refs(block.config.candidateExerciseRefs);
  const exercises = useResource<NotebookOptions>(
    `/notebooks/${artifact.id}/block-options?artifactRevision=${version.revision}&originBlockId=${encodeURIComponent(block.id)}`,
  );
  const [selected, setSelected] = useState(
    state.selectedExerciseRef ? refKey(state.selectedExerciseRef) : '',
  );
  const chosen = candidates.find((item) => refKey(item) === selected);
  const actual =
    exercises.data?.counterfactualCandidates.filter((item) =>
      candidates.some((ref) => ref.id === item.id && ref.revision === item.revision),
    ) || [];
  return (
    <div className="notebook-variant">
      <Field label="选择这个区块允许的对照题">
        <select
          value={selected}
          disabled={disabled || exercises.loading || !!exercises.error}
          onChange={(event) => setSelected(event.target.value)}
        >
          <option value="">选择已审核的同题族题目</option>
          {actual.map((item) => (
            <option key={refKey(item)} value={refKey(item)}>
              {item.prompt.slice(0, 120)} · 版本 {item.revision}
            </option>
          ))}
        </select>
      </Field>
      <button
        disabled={
          disabled ||
          !chosen ||
          !actual.some((item) => refKey(item) === selected) ||
          (state.selectedExerciseRef !== null && selected === refKey(state.selectedExerciseRef))
        }
        onClick={() => void dispatch(block, 'setParameter', { exerciseRef: chosen! })}
      >
        确认载入这道对照题
      </button>
      <ErrorNotice error={exercises.error} retry={exercises.reload} />
      {state.selectedExerciseRef && selected !== refKey(state.selectedExerciseRef) && (
        <p className="notice">
          下方仍是上次确认的题目。点击确认后才切换作答上下文；各题草稿分别保留。
        </p>
      )}
      <p className="muted small">
        对照维度：
        {(
          {
            BOUND: '类型边界',
            ARGUMENT: '类型参数',
            RETURN: '返回类型',
            WILDCARD: '通配符',
          } as Record<string, string>
        )[String(block.config.changeDimension)] || '已声明维度'}
        。题目版本来自源稿 {version.revision}，不自动生成或改写题目。
      </p>
    </div>
  );
}

function ReviewLinks({
  block,
  disabled,
  dispatch,
}: {
  block: InteractionBlock;
  disabled: boolean;
  dispatch: Dispatch;
}) {
  const { workspace, navigate } = useWorkspace();
  const artifacts = useResource<Artifact[]>(`/workspaces/${workspace.id}/artifacts`);
  const reviews = useResource<Review[]>('/me/reviews');
  const linked = refs(block.config.artifactRefs);
  const reviewIds = Array.isArray(block.config.reviewIds) ? block.config.reviewIds : [];
  return (
    <div className="notebook-review-links">
      <ErrorNotice error={artifacts.error} retry={artifacts.reload} />
      <ErrorNotice error={reviews.error} retry={reviews.reload} />
      {linked.map((ref) => {
        const artifact = artifacts.data?.find((item) => item.id === ref.id);
        const current = artifact?.currentRevision === ref.revision;
        return (
          <div className="notebook-linked-record" key={refKey(ref)}>
            <div>
              <strong>{artifact?.title || '关联作品尚未读取'}</strong>
              <p className="muted small">
                固定版本 {ref.revision}
                {artifact && !current
                  ? ` · 当前已为版本 ${artifact.currentRevision}，需重建关联`
                  : ''}
              </p>
            </div>
            <button
              disabled={disabled || !current}
              onClick={() => void dispatch(block, 'navigateArtifact', { artifactRef: ref })}
            >
              核验并打开作品
            </button>
          </div>
        );
      })}
      {reviewIds.map((id) => {
        const review = reviews.data?.find((item) => item.id === id);
        return (
          <div className="notebook-linked-record" key={String(id)}>
            <div>
              <strong>{review?.reason || '来源作答的复习记录'}</strong>
              {review && (
                <p className="muted small">
                  {new Date(review.dueAt).toLocaleString()} · <Status value={review.status} />
                </p>
              )}
            </div>
            <button disabled={disabled || !review} onClick={() => navigate('progress')}>
              查看复习安排
            </button>
          </div>
        );
      })}
    </div>
  );
}

function NotebookAnswer({
  artifact,
  version,
  block,
  exerciseRef,
  state,
  disabled,
  dispatch,
}: {
  artifact: Artifact;
  version: ArtifactVersion;
  block: InteractionBlock;
  exerciseRef: ContentRef;
  state: NotebookBlockState;
  disabled: boolean;
  dispatch: Dispatch;
}) {
  const { workspace, session, can, refresh } = useWorkspace();
  const prefix = `tf.notebook-input.${session.userId}.${workspace.id}.${artifact.id}.${block.id}.${refKey(exerciseRef)}`;
  const resultPrefix = `${prefix}.version-${version.revision}`;
  const exercise = useResource<Exercise>(
    `/exercises/${exerciseRef.id}?releaseId=${workspace.releaseId}&revision=${exerciseRef.revision}`,
  );
  const [options, setOptions] = useSessionDraft<string[]>(`${prefix}.choices`, []);
  const initialSource =
    typeof block.config.originalSource === 'string' ? block.config.originalSource : starterSource;
  const [source, setSource] = useSessionDraft(`${prefix}.code`, initialSource);
  const [text, setText] = useSessionDraft(
    `${prefix}.text`,
    typeof block.config.originalTextAnswer === 'string' ? block.config.originalTextAnswer : '',
  );
  const [attemptId, setAttemptId] = useSessionDraft(
    `${resultPrefix}.attempt`,
    state.lastResult?.attemptId || '',
  );
  const [compileId, setCompileId] = useSessionDraft(
    `${resultPrefix}.compile`,
    state.lastResult?.compileJobId || '',
  );
  const [baselineId, setBaselineId] = useSessionDraft(
    `${resultPrefix}.baseline`,
    state.lastResult?.baselineCompileJobId || '',
  );
  const [submitted, setSubmitted] = useSessionDraft(`${resultPrefix}.submitted`, '');
  const [compiledSource, setCompiledSource] = useSessionDraft<string | null>(
    `${resultPrefix}.compiled-source`,
    null,
  );
  const [poll, setPoll] = useState(0);
  const attempt = useResource<Attempt>(attemptId ? `/attempts/${attemptId}` : null, poll);
  const compilation = useResource<CompileJob>(
    compileId ? `/compile-jobs/${compileId}` : null,
    poll,
  );
  const baseline = useResource<CompileJob>(baselineId ? `/compile-jobs/${baselineId}` : null, poll);
  const cancel = useMutation();
  const last = useRef<NotebookResult | null>(null);
  useEffect(() => {
    const result = state.lastResult;
    if (!result || last.current === result) return;
    last.current = result;
    if (result.attemptId) setAttemptId(result.attemptId);
    if (result.compileJobId) setCompileId(result.compileJobId);
    if (result.baselineCompileJobId) setBaselineId(result.baselineCompileJobId);
  }, [state.lastResult]);
  const pending =
    !!attemptId &&
    (!attempt.data || ['PENDING', 'QUEUED', 'RUNNING'].includes(attempt.data.status));
  const compiling = !!compileId && (!compilation.data || jobPending(compilation.data.status));
  const compilingBaseline = !!baselineId && (!baseline.data || jobPending(baseline.data.status));
  usePolling(
    pending || compiling || compilingBaseline,
    () => setPoll((value) => value + 1),
    attempt.loading || compilation.loading || baseline.loading,
    !!attempt.error || !!compilation.error || !!baseline.error,
  );
  useEffect(() => {
    if (attempt.data?.status === 'GRADED') refresh();
  }, [attempt.data?.id, attempt.data?.status]);
  const kind = exercise.data ? exerciseAnswerKind(exercise.data) : 'UNSUPPORTED';
  const bytes = new TextEncoder().encode(source).byteLength;
  const validSource = !!source.trim() && !source.includes('\0') && bytes <= 32768;
  const answer =
    kind === 'CODE'
      ? { kind: 'CODE' as const, source }
      : kind === 'TEXT'
        ? { kind: 'TEXT' as const, value: text }
        : { kind: 'CHOICE' as const, optionIds: options };
  const answerJson = JSON.stringify(answer);
  const answerValid =
    kind === 'CODE'
      ? can('CODE_GRADING') && validSource
      : kind === 'TEXT'
        ? can('TEXT_GRADING') &&
          !!exercise.data?.textPolicy &&
          validTextAnswer(text, exercise.data.textPolicy)
        : kind === 'CHOICE' && options.length > 0;
  useUnsavedChanges(
    kind === 'CODE'
      ? source !== initialSource && answerJson !== submitted
      : kind === 'TEXT'
        ? !!text.trim() && answerJson !== submitted
        : options.length > 0 && answerJson !== submitted,
  );
  const readOnly = disabled || pending || cancel.busy;
  const repair = block.kind === 'REPAIR_DIFF';
  return (
    <div className="notebook-answer">
      <ErrorNotice error={exercise.error} retry={exercise.reload} />
      {exercise.loading && !exercise.data && <Loading label="正在读取固定版本题目…" />}
      {block.kind === 'COUNTERFACTUAL' && exercise.data && (
        <div className="notebook-selected-question">
          <Badge>已确认的对照题 · 版本 {exercise.data.revision}</Badge>
          <p className="question-prompt">{exercise.data.prompt}</p>
        </div>
      )}
      {exercise.data && kind === 'CHOICE' && (
        <fieldset className="answer-options" disabled={readOnly}>
          <legend>重新选择答案</legend>
          {exercise.data.options.map((option) => (
            <label
              className={`answer-option ${options.includes(option.id) ? 'selected' : ''}`}
              key={option.id}
            >
              <input
                type={exercise.data!.kind === 'SINGLE' ? 'radio' : 'checkbox'}
                name={`notebook-${block.id}-${exerciseRef.id}`}
                checked={options.includes(option.id)}
                onChange={(event) =>
                  setOptions((before) =>
                    exercise.data!.kind === 'SINGLE'
                      ? [option.id]
                      : event.target.checked
                        ? [...before, option.id]
                        : before.filter((value) => value !== option.id),
                  )
                }
              />
              <span className="option-label">{option.id}</span>
              <span>{option.text}</span>
            </label>
          ))}
        </fieldset>
      )}
      {kind === 'TEXT' && exercise.data?.textPolicy && (
        <TextAnswer
          policy={exercise.data.textPolicy}
          value={text}
          onChange={setText}
          disabled={readOnly}
          label="重新写下文本答案"
        />
      )}
      {kind === 'TEXT' && (!can('TEXT_GRADING') || !exercise.data?.textPolicy) && (
        <p className="notice warning">当前文本判分能力或完整规则不可用，答案草稿仍会保留。</p>
      )}
      {(kind === 'CODE' || block.allowedActions.includes('requestCompile')) && (
        <div className={repair ? 'notebook-code-comparison' : ''}>
          {repair && (
            <details open>
              <summary>原始代码 · 固定来源</summary>
              <pre tabIndex={0} aria-label="修订对照的原始代码">
                <code>{initialSource}</code>
              </pre>
              <p className="muted small">这份代码保留来源作答，不随修订稿改变。</p>
            </details>
          )}
          <Field
            label={repair ? '修订代码 · Main.java' : '修改本题代码 · Main.java'}
            hint={`${bytes.toLocaleString()} / 32,768 字节`}
          >
            <textarea
              className="code-editor"
              rows={14}
              spellCheck={false}
              maxLength={32768}
              value={source}
              disabled={readOnly || compiling || compilingBaseline}
              onChange={(event) => setSource(event.target.value)}
            />
          </Field>
        </div>
      )}
      <div className="row wrap">
        <button
          className="primary"
          disabled={readOnly || !answerValid || !block.allowedActions.includes('submitAnswer')}
          onClick={() =>
            void dispatch(block, 'submitAnswer', { answer }).then((result) => {
              if (result) setSubmitted(answerJson);
            })
          }
        >
          提交复练
        </button>
        {block.allowedActions.includes('requestCompile') && can('COMPILE_ONLY') && (
          <button
            disabled={readOnly || compiling || compilingBaseline || !validSource}
            onClick={() =>
              void dispatch(block, 'requestCompile', { answer: { kind: 'CODE', source } }).then(
                (result) => {
                  if (result) setCompiledSource(source);
                },
              )
            }
          >
            {repair ? '分别检查原稿与修订稿编译' : '仅检查编译'}
          </button>
        )}
      </div>
      {repair && !can('COMPILE_ONLY') && (
        <p className="notice warning">
          独立编译服务尚未配置。原稿与修订草稿可阅读和编辑，编译结果保持为空。
        </p>
      )}
      <ErrorNotice error={attempt.error} retry={attempt.reload} />
      {attempt.data && (
        <section className="feedback" aria-live="polite">
          <h4>
            复练反馈 ·{' '}
            {attempt.data.status === 'GRADED' ? (
              `${attempt.data.score}/${attempt.data.maximum}`
            ) : (
              <Status value={attempt.data.status} />
            )}
          </h4>
          <p className="preserve-text">{attempt.data.feedback}</p>
          {pending && <Loading label="代码作答已入队，正在等待题目规则验证。" />}
          {submitted && submitted !== answerJson && (
            <p className="muted small">输入区有尚未提交的修改。这里展示上次提交的成绩。</p>
          )}
          <p className="muted small">错题复练标记为辅助记录，重复题目家族不会增加独立掌握样本。</p>
        </section>
      )}
      {(compileId || baselineId) && (
        <section className="notebook-compile-results">
          <h4>{repair ? '两份源码的实际编译结果' : '实际编译结果'}</h4>
          {compiledSource !== null && compiledSource !== source && (
            <p className="notice">当前代码有未编译的修改，以下结果对应上次提交的源码。</p>
          )}
          <div className={repair ? 'notebook-compile-pair' : ''}>
            {(repair
              ? [
                  { label: '原始代码', resource: baseline },
                  { label: '修订代码', resource: compilation },
                ]
              : [{ label: '当前提交代码', resource: compilation }]
            ).map(({ label, resource }) => (
              <section key={label}>
                <h5>{label}</h5>
                <ErrorNotice error={resource.error} retry={resource.reload} />
                {resource.loading && !resource.data && <Loading />}
                {resource.data && (
                  <>
                    <CompileDiagnostics job={resource.data} />
                    {jobPending(resource.data.status) && (
                      <button
                        disabled={disabled || cancel.busy}
                        onClick={() =>
                          void cancel.run(
                            () =>
                              api.request(
                                `/compile-jobs/${resource.data!.id}/cancel`,
                                'POST',
                                undefined,
                                { idempotent: true },
                              ),
                            () => {
                              setPoll((value) => value + 1);
                              refresh();
                            },
                          )
                        }
                      >
                        取消{label}编译
                      </button>
                    )}
                  </>
                )}
              </section>
            ))}
          </div>
          <ErrorNotice error={cancel.error} />
          <p className="muted small">
            编译通过只说明这份源码符合当前编译检查，不代表题目已答对。每个任务保留独立源码摘要。
          </p>
        </section>
      )}
    </div>
  );
}
