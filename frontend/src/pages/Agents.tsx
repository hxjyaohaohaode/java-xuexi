import { useEffect, useState } from 'react';
import {
  ArrowUpRight,
  Check,
  CirclePause,
  FileText,
  Layers,
  Play,
  RefreshCw,
  Square,
  Workflow,
} from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, usePolling, useResource, useSessionDraft, useUnsavedChanges } from '../hooks';
import { readSaved, removeSaved, writeSaved } from '../state';
import { Badge, Empty, ErrorNotice, Field, Loading, SectionTitle, Status } from '../components/ui';
import type { Artifact, Attempt, Context, Goal, Run, RunEvent } from '../types';

const ended = ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'];
const outputs = [
  { id: 'DOCUMENT', title: '学习文稿', note: '可编辑的讲解与审阅意见' },
  { id: 'SLIDES', title: '演示文稿', note: '可调整版式并导出 PPTX' },
  { id: 'NOTEBOOK', title: '复练笔记', note: '从本空间错误或受助作答生成' },
];
const roleNames: Record<string, string> = {
  conductor: '教学规划',
  'learning-designer': '示例设计',
  'generics-expert': '泛型讲解',
  'code-coach': '代码纠错',
  'fact-checker': '教学审阅',
  'document-author': '整理文稿',
  'slide-designer': '整理演示',
  'interactive-builder': '整理复练',
};
type Details = {
  runId: string;
  mode: 'MODEL' | 'EVIDENCE_ONLY';
  requestedOutputs: string[];
  teachingPlan: null | {
    strategy: string;
    sections: { role: string; title: string; focus: string }[];
  };
  tutorRequestIds: string[];
  artifactIds: string[];
  limitations: string[];
};
type Draft = {
  goalId: string;
  text: string;
  acceptance: string;
  outputs: string[];
  mode: 'MODEL' | 'EVIDENCE_ONLY';
  tokens: number;
  minutes: number;
  attemptIds?: string[];
};

export function Agents() {
  const { workspace, session, can, tick, refresh, navigate, selection } = useWorkspace();
  const [draft, setDraft] = useSessionDraft<Draft>(
    `tf.study-draft.${session.userId}.${workspace.id}`,
    {
      goalId: '',
      text: '',
      acceptance: '能够解释适用规则，并用正反代码例子检验自己的判断',
      outputs: ['DOCUMENT', 'SLIDES'],
      mode: 'EVIDENCE_ONLY',
      tokens: 100000,
      minutes: 10,
    },
  );
  const attemptIds = draft.attemptIds || [];
  const attempts = useResource<Attempt[]>(
    draft.outputs.includes('NOTEBOOK') ? `/workspaces/${workspace.id}/attempts` : null,
    tick,
  );
  const routedAttempt = useResource<Attempt>(
    selection ? `/attempts/${encodeURIComponent(selection)}` : null,
  );
  useEffect(() => {
    if (
      selection &&
      routedAttempt.data?.status === 'GRADED' &&
      routedAttempt.data.workspaceId === workspace.id &&
      (routedAttempt.data.score !== routedAttempt.data.maximum ||
        routedAttempt.data.hintLevel > 0) &&
      !attemptIds.includes(selection) &&
      attemptIds.length < 8
    )
      setDraft((previous) => ({
        ...previous,
        attemptIds: [...(previous.attemptIds || []), selection],
        outputs: [...new Set([...previous.outputs, 'NOTEBOOK'])],
      }));
  }, [selection, routedAttempt.data]);
  const gradedAttempts = [
    ...new Map(
      [...(attempts.data || []), ...(routedAttempt.data ? [routedAttempt.data] : [])]
        .filter(
          (item) =>
            item.status === 'GRADED' &&
            (item.score !== item.maximum || item.hintLevel > 0) &&
            item.workspaceId === workspace.id &&
            item.releaseId === workspace.releaseId,
        )
        .map((item) => [item.id, item]),
    ).values(),
  ];
  const [selected, select] = useState(() =>
    readSaved<string>(`tf.study-selection.${session.userId}.${workspace.id}`, ''),
  );
  const [poll, setPoll] = useState(0);
  const goals = useResource<Goal[]>(`/workspaces/${workspace.id}/goals`, tick);
  const runs = useResource<Run[]>(
    can('STUDY_WORKFLOW') ? `/workspaces/${workspace.id}/agent-runs` : null,
    tick + poll,
  );
  const artifacts = useResource<Artifact[]>(`/workspaces/${workspace.id}/artifacts`, tick + poll);
  const run = runs.data?.find((item) => item.id === selected);
  const detail = useResource<Details>(
    run &&
      run.plan &&
      (run.plan as { steps?: { id: string }[] }).steps?.some((s) => s.id === 'planner')
      ? `/agent-runs/${run.id}/study`
      : null,
    tick + poll,
  );
  const events = useResource<RunEvent[]>(
    run ? `/agent-runs/${run.id}/events?limit=100` : null,
    tick + poll,
  );
  const mutation = useMutation();
  useUnsavedChanges(!!draft.text.trim());
  useEffect(() => {
    if (selected) writeSaved(`tf.study-selection.${session.userId}.${workspace.id}`, selected);
  }, [selected, session.userId, workspace.id]);
  useEffect(() => {
    if (!selected && runs.data?.[0]) select(runs.data[0].id);
  }, [runs.data, selected]);
  usePolling(
    !!runs.data?.some((item) => !ended.includes(item.status)),
    () => setPoll((value) => value + 1),
    runs.loading || artifacts.loading || detail.loading || events.loading,
    !!runs.error || !!detail.error || !!events.error,
    2500,
  );
  if (!can('STUDY_WORKFLOW'))
    return (
      <Empty title="学习创作任务尚未启用">
        当前服务没有提供可执行的教学任务适配器。你可以继续阅读、提问与编辑作品。
      </Empty>
    );
  const change = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((previous) => ({ ...previous, [key]: value }));
  const create = () => {
    const submitted = { ...draft, outputs: [...draft.outputs] };
    void mutation.run(
      async () => {
        const acceptance = submitted.acceptance
          .split('\n')
          .map((line) => line.trim())
          .filter(Boolean);
        if (
          !submitted.goalId &&
          (!acceptance.length ||
            acceptance.length > 20 ||
            acceptance.some((line) => line.length > 500))
        )
          throw new Error('验收标准需要 1 至 20 项，每项最多 500 字符。');
        const storageKey = `tf.study-preparing.${session.userId}.${workspace.id}`;
        const fingerprint = JSON.stringify(submitted);
        let prepared = readSaved<{ fingerprint: string; goalId?: string; snapshotId?: string }>(
          storageKey,
          { fingerprint },
        );
        if (prepared.fingerprint !== fingerprint) prepared = { fingerprint };
        if (!prepared.goalId) {
          const goal = submitted.goalId
            ? { id: submitted.goalId }
            : await api.request<Goal>(
                `/workspaces/${workspace.id}/goals`,
                'POST',
                { text: submitted.text.trim(), acceptance },
                { idempotent: true },
              );
          prepared.goalId = goal.id;
          writeSaved(storageKey, prepared);
        }
        if (!prepared.snapshotId) {
          const snapshot = await api.request<Context>(
            `/workspaces/${workspace.id}/context`,
            'POST',
            submitted.attemptIds?.length ? { attemptIds: submitted.attemptIds } : undefined,
          );
          prepared.snapshotId = snapshot.id;
          writeSaved(storageKey, prepared);
        }
        const result = await api.request<Run>(
          `/workspaces/${workspace.id}/agent-runs`,
          'POST',
          {
            goalId: prepared.goalId,
            snapshotId: prepared.snapshotId,
            requestedOutputs: submitted.outputs,
            mode: submitted.mode,
            ...(submitted.attemptIds?.length ? { attemptIds: submitted.attemptIds } : {}),
            budget: {
              maxTokens: submitted.tokens,
              maxWallMillis: submitted.minutes * 60000,
              maxCostMicros: 1000000,
              maxParallel: 3,
            },
          },
          { idempotent: true },
        );
        removeSaved(storageKey);
        return result;
      },
      (result) => {
        select(result.id);
        setDraft((previous) => ({ ...previous, text: '', goalId: result.goalId }));
        refresh();
        setPoll((p) => p + 1);
      },
    );
  };
  const command = (kind: 'PAUSE' | 'RESUME' | 'CANCEL') => {
    if (!run) return;
    void mutation.run(
      () =>
        api.request<Run>(`/agent-runs/${run.id}/commands`, 'POST', {
          kind,
          expectedPlanRevision: run.planRevision,
          expectedRunEpoch: run.runEpoch,
        }),
      () => {
        refresh();
        setPoll((p) => p + 1);
      },
    );
  };
  return (
    <div className="study-page">
      <SectionTitle
        eyebrow="目标、分工与作品"
        title="一起把知识学透"
        description="先规划学习重点，再整理讲解、审阅意见和可编辑作品。每一步的来源、进度与用量都可以追踪。"
        action={
          <button
            className="icon-button"
            aria-label="刷新学习任务"
            onClick={() => setPoll((p) => p + 1)}
          >
            <RefreshCw size={18} />
          </button>
        }
      />
      <ErrorNotice
        error={
          mutation.error ||
          runs.error ||
          goals.error ||
          detail.error ||
          events.error ||
          artifacts.error ||
          attempts.error ||
          routedAttempt.error
        }
        retry={() => {
          goals.reload();
          setPoll((p) => p + 1);
        }}
      />
      <div className="study-layout">
        <aside className="stack study-sidebar">
          <form
            className="panel stack"
            onSubmit={(e) => {
              e.preventDefault();
              create();
            }}
          >
            <h2>
              <Workflow size={20} /> 新建学习任务
            </h2>
            <fieldset disabled={mutation.busy} className="study-form-fields">
              <Field label="学习目标">
                <select value={draft.goalId} onChange={(e) => change('goalId', e.target.value)}>
                  <option value="">编写一个新目标</option>
                  {goals.data
                    ?.filter((g) => g.status === 'ACTIVE')
                    .map((g) => (
                      <option key={g.id} value={g.id}>
                        {g.text.slice(0, 70)}
                      </option>
                    ))}
                </select>
              </Field>
              {!draft.goalId && (
                <>
                  <Field label="你想真正理解什么？">
                    <textarea
                      rows={3}
                      required
                      maxLength={4000}
                      value={draft.text}
                      placeholder="例如：用生产者与消费者的例子解释 PECS，并识别错误的通配符签名"
                      onChange={(e) => change('text', e.target.value)}
                    />
                  </Field>
                  <Field label="怎样算学会？每行一项">
                    <textarea
                      rows={2}
                      required
                      maxLength={4000}
                      value={draft.acceptance}
                      onChange={(e) => change('acceptance', e.target.value)}
                    />
                  </Field>
                </>
              )}
              <Field label="协作方式">
                <select
                  value={draft.mode}
                  onChange={(e) => change('mode', e.target.value as Draft['mode'])}
                >
                  <option value="EVIDENCE_ONLY">整理已有课程证据</option>
                  <option value="MODEL" disabled={!can('LLM_PLANNING')}>
                    模型规划与分工{can('LLM_PLANNING') ? '' : ' · 未配置'}
                  </option>
                </select>
              </Field>
              <p className="muted small">
                {draft.mode === 'MODEL'
                  ? '规划者选择 1–3 个教学分工，再由另一轮请求审阅。模型意见与事实验证分开记录。'
                  : '使用确定性步骤整理已有证据；此模式不会调用模型。'}
              </p>
              <div className="study-output-choices" role="group" aria-label="希望得到的作品">
                {outputs.map((output) => (
                  <label
                    key={output.id}
                    className={`study-output ${draft.outputs.includes(output.id) ? 'selected' : ''}`}
                  >
                    <input
                      type="checkbox"
                      checked={draft.outputs.includes(output.id)}
                      onChange={() =>
                        change(
                          'outputs',
                          draft.outputs.includes(output.id)
                            ? draft.outputs.filter((id) => id !== output.id)
                            : [...draft.outputs, output.id],
                        )
                      }
                    />
                    <span>
                      <strong>{output.title}</strong>
                      <small>{output.note}</small>
                    </span>
                  </label>
                ))}
              </div>
              {draft.outputs.includes('NOTEBOOK') && (
                <fieldset className="study-attempts">
                  <legend>固定复练作答</legend>
                  <p className="muted small">
                    已选 {attemptIds.length} / 8 条。不选择时，使用快照中最近最多 8
                    次已判分作答中的错误或受助记录。创建复练笔记不会增加独立掌握样本。
                  </p>
                  {gradedAttempts.map((attempt) => (
                    <label className="check-row" key={attempt.id}>
                      <input
                        type="checkbox"
                        checked={attemptIds.includes(attempt.id)}
                        disabled={!attemptIds.includes(attempt.id) && attemptIds.length >= 8}
                        onChange={() =>
                          change(
                            'attemptIds',
                            attemptIds.includes(attempt.id)
                              ? attemptIds.filter((id) => id !== attempt.id)
                              : [...attemptIds, attempt.id],
                          )
                        }
                      />
                      <span>
                        {attempt.feedback.slice(0, 90)}
                        <small>
                          {new Date(attempt.createdAt).toLocaleString()} · {attempt.score}/
                          {attempt.maximum}
                        </small>
                      </span>
                    </label>
                  ))}
                  {!attempts.loading && !gradedAttempts.length && (
                    <p className="notice warning">
                      还没有可复练的错误或受助作答。可以先完成练习，或暂时取消“复练笔记”。
                    </p>
                  )}
                </fieldset>
              )}
              <details className="editor-disclosure">
                <summary>时间与用量上限</summary>
                <div className="form-grid">
                  <Field label="最多运行（分钟）">
                    <input
                      type="number"
                      min={1}
                      max={15}
                      value={draft.minutes}
                      onChange={(e) => change('minutes', Number(e.target.value))}
                    />
                  </Field>
                  <Field label="Token 上限">
                    <input
                      type="number"
                      min={1}
                      max={100000}
                      value={draft.tokens}
                      onChange={(e) => change('tokens', Number(e.target.value))}
                    />
                  </Field>
                </div>
                <p className="muted small">
                  实际 Token 由模型服务回执记录。费用没有配置单价时不做金额推测。
                </p>
              </details>
              <button
                className="primary wide"
                disabled={
                  mutation.busy ||
                  !draft.outputs.length ||
                  (!draft.goalId && !draft.text.trim()) ||
                  (draft.mode === 'MODEL' && !can('LLM_PLANNING'))
                }
              >
                <Play size={16} />
                {mutation.busy ? '正在提交…' : '开始学习创作'}
              </button>
            </fieldset>
          </form>
          <section className="panel">
            <div className="row spread">
              <h2>最近任务</h2>
              <Badge>{runs.data?.length || 0}</Badge>
            </div>
            {runs.loading && !runs.data ? (
              <Loading />
            ) : (
              <div className="content-list">
                {runs.data?.map((item) => (
                  <button
                    key={item.id}
                    className={`content-row ${selected === item.id ? 'selected' : ''}`}
                    onClick={() => select(item.id)}
                  >
                    <strong>
                      {goals.data?.find((g) => g.id === item.goalId)?.text || '已保存的学习目标'}
                    </strong>
                    <span>
                      <Status value={item.status} /> ·{' '}
                      {item.steps.filter((s) => s.status === 'SUCCEEDED').length}/
                      {item.steps.length} 步
                    </span>
                  </button>
                ))}
              </div>
            )}
          </section>
        </aside>
        <section className="study-detail stack" aria-label="学习任务详情">
          {!run ? (
            <Empty title="从一个具体问题开始">
              任务会保留规划、执行记录与草稿作品。先写下目标和验收标准，再选择你想带走的成果。
            </Empty>
          ) : (
            <>
              <div className="panel">
                <div className="row spread wrap">
                  <div>
                    <Badge>
                      {detail.data?.mode === 'MODEL'
                        ? '模型协作'
                        : detail.data?.mode === 'EVIDENCE_ONLY'
                          ? '课程证据整理'
                          : '证据任务'}
                    </Badge>
                    <h2>{goals.data?.find((g) => g.id === run.goalId)?.text || '学习任务'}</h2>
                  </div>
                  <Status value={run.status} />
                </div>
                <div
                  className="study-run-progress"
                  role="progressbar"
                  aria-label="已完成步骤"
                  aria-valuemin={0}
                  aria-valuemax={run.steps.length}
                  aria-valuenow={run.steps.filter((s) => s.status === 'SUCCEEDED').length}
                >
                  <span
                    style={{
                      width: `${(run.steps.filter((s) => s.status === 'SUCCEEDED').length / Math.max(1, run.steps.length)) * 100}%`,
                    }}
                  />
                </div>
                <div className="row spread wrap">
                  <p className="muted small">
                    已用 {run.spentTokens.toLocaleString()} · 预留{' '}
                    {run.reservedTokens.toLocaleString()} / {run.tokenBudget.toLocaleString()} Token
                  </p>
                  <div className="row wrap">
                    {['QUEUED', 'RUNNING'].includes(run.status) && (
                      <button disabled={mutation.busy} onClick={() => command('PAUSE')}>
                        <CirclePause size={16} /> 暂停
                      </button>
                    )}
                    {['PAUSED', 'WAITING_USER'].includes(run.status) && (
                      <button
                        disabled={mutation.busy || run.reservedTokens > 0}
                        onClick={() => command('RESUME')}
                      >
                        <Play size={16} /> 继续
                      </button>
                    )}
                    {!ended.includes(run.status) && (
                      <button disabled={mutation.busy} onClick={() => command('CANCEL')}>
                        <Square size={15} /> 停止任务
                      </button>
                    )}
                  </div>
                </div>
                {run.status === 'WAITING_USER' && (
                  <p className="notice warning">
                    任务已停下，等待处理来源变化或未确认的调用。已有草稿保留；若目标或来源已变更，请停止本任务并使用新快照创建任务。
                  </p>
                )}
              </div>
              {detail.data?.teachingPlan && (
                <div className="panel">
                  <h2>这次如何展开</h2>
                  <div className="study-plan">
                    {detail.data.teachingPlan.sections.map((section, i) => (
                      <article key={`${i}:${section.title}`}>
                        <span className="study-step-number">{i + 1}</span>
                        <div>
                          <h3>{section.title}</h3>
                          <p>{section.focus}</p>
                        </div>
                      </article>
                    ))}
                  </div>
                </div>
              )}
              <div className="panel">
                <h2>每一步都可以追踪</h2>
                <ol className="study-steps">
                  {run.steps.map((step) => (
                    <li key={step.id}>
                      <span
                        className={`study-step-mark ${step.status === 'SUCCEEDED' ? 'complete' : ''}`}
                      >
                        {step.status === 'SUCCEEDED' ? <Check size={16} /> : <Layers size={16} />}
                      </span>
                      <div>
                        <strong>{roleNames[step.role] || step.role}</strong>
                        <small>
                          {step.key === 'await-plan'
                            ? '等待教学计划确定分工'
                            : step.key.startsWith('expert-')
                              ? '根据目标和知识来源编写草稿'
                              : step.key === 'review'
                                ? '核对遗漏与待验证点'
                                : step.key.startsWith('output-')
                                  ? '保留来源与修订记录'
                                  : '确定本次学习重点'}
                        </small>
                      </div>
                      <Status value={step.status} />
                    </li>
                  ))}
                </ol>
              </div>
              {!!detail.data?.artifactIds.length && (
                <div className="panel">
                  <h2>已经留下的作品</h2>
                  <p className="muted small">
                    作品均以草稿保存。你可以继续编辑、核对引用并导出；任务不会覆盖你的手工修改。
                  </p>
                  <div className="study-artifacts">
                    {detail.data.artifactIds.map((id) => {
                      const artifact = artifacts.data?.find((a) => a.id === id);
                      return (
                        <button
                          className="content-row"
                          key={id}
                          onClick={() => navigate('studio', id)}
                        >
                          <FileText size={20} />
                          <span>
                            <strong>{artifact?.title || '已保存的学习草稿'}</strong>
                            <small>
                              {artifact?.kind || 'DOCUMENT'} ·{' '}
                              {artifact ? `版本 ${artifact.currentRevision}` : '打开查看'}
                            </small>
                          </span>
                          <ArrowUpRight size={17} />
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}
              <details className="panel editor-disclosure">
                <summary>查看执行记录</summary>
                <p className="muted small">
                  记录显示服务器实际状态；规划、调用、结算与完成分别保留。
                </p>
                <ol className="study-events">
                  {events.data?.map((event) => (
                    <li key={event.sequence}>
                      <span>{new Date(event.createdAt).toLocaleTimeString()}</span>
                      <code>{event.type}</code>
                      <details>
                        <summary>详情</summary>
                        <pre>{JSON.stringify(event.payload, null, 2)}</pre>
                      </details>
                    </li>
                  ))}
                </ol>
                {events.data?.length === 100 && (
                  <p className="muted small">当前展示前 100 条记录。</p>
                )}
              </details>
            </>
          )}
        </section>
      </div>
    </div>
  );
}
