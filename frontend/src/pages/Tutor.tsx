import { useEffect, useState } from 'react';
import { ArrowUp, CirclePause, Play, Plus, Square, Workflow } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource } from '../hooks';
import { readSaved, submitOnEnter, writeSaved } from '../state';
import {
  Badge,
  Empty,
  ErrorNotice,
  EvidenceList,
  Field,
  Loading,
  SectionTitle,
  Status,
} from '../components/ui';
import type { Context, Conversation, Page, Run, TutorRequest } from '../types';

const terminalTutor = ['COMPLETED', 'INSUFFICIENT_EVIDENCE', 'FAILED', 'CANCELLED'];
const terminalRun = ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'];
export function Tutor() {
  const [tab, setTab] = useState<'conversation' | 'workflow'>('conversation');
  return (
    <>
      <SectionTitle
        eyebrow="让提问、来源与任务过程一起可见"
        title="导师与证据任务"
        description="本地证据整理与模型回答有明确区别。每个请求固定工作空间快照，取消、来源失效和证据不足都会显示真实状态。"
      />
      <div className="segments large" aria-label="导师模式">
        <button
          className={tab === 'conversation' ? 'selected' : ''}
          onClick={() => setTab('conversation')}
        >
          导师对话
        </button>
        <button className={tab === 'workflow' ? 'selected' : ''} onClick={() => setTab('workflow')}>
          证据工作流
        </button>
      </div>
      {tab === 'conversation' ? <ConversationPanel /> : <EvidenceWorkflow />}
    </>
  );
}
function ConversationPanel() {
  const { workspace, session, can, tick, refresh } = useWorkspace();
  const conversations = useResource<Page<Conversation>>(
    can('TUTOR_EVIDENCE_ONLY')
      ? `/tutor/conversations?workspaceId=${workspace.id}&limit=100`
      : null,
    tick,
  );
  const [conversationId, setConversationId] = useState(() =>
    readSaved<string>(`tf.conversation.${session.userId}.${workspace.id}`, ''),
  );
  const [question, setQuestion] = useState('');
  const [mode, setMode] = useState<'EVIDENCE_ONLY' | 'MODEL'>('EVIDENCE_ONLY');
  const [hintLevel, setHintLevel] = useState(1);
  const [poll, setPoll] = useState(0);
  const mutation = useMutation();
  const detail = useResource<
    Conversation & { messages: TutorRequest[]; nextCursor: string | null }
  >(conversationId ? `/tutor/conversations/${conversationId}?limit=100` : null, poll + tick);
  useEffect(() => {
    if (!conversationId && conversations.data?.items[0])
      setConversationId(conversations.data.items[0].id);
  }, [conversations.data, conversationId]);
  useEffect(() => {
    if (conversationId)
      writeSaved(`tf.conversation.${session.userId}.${workspace.id}`, conversationId);
  }, [conversationId, workspace.id, session.userId]);
  useEffect(() => {
    if (detail.data?.messages.some((m) => !terminalTutor.includes(m.status))) {
      const timer = setTimeout(() => setPoll((n) => n + 1), 1200);
      return () => clearTimeout(timer);
    }
  }, [detail.data, poll]);
  if (!can('TUTOR_EVIDENCE_ONLY'))
    return (
      <Empty title="导师服务尚未启用">课程阅读和练习仍可使用；未配置模型不会出现模拟回答。</Empty>
    );
  const create = async () => {
    const conversation = await api.request<Conversation>(
      '/tutor/conversations',
      'POST',
      {
        workspaceId: workspace.id,
        releaseId: workspace.releaseId,
        title: question.trim().slice(0, 120) || '泛型学习对话',
      },
      { idempotent: true },
    );
    return conversation.id;
  };
  const send = () => {
    if (!question.trim() || mutation.busy) return;
    void mutation.run(
      async () => {
        const key = `tf.tutor-preparing.${session.userId}.${workspace.id}`;
        type Preparation = {
          question: string;
          mode: string;
          hintLevel: number;
          conversationId?: string;
          snapshotId?: string;
        };
        let prepared = readSaved<Preparation>(key, {
          question,
          mode,
          hintLevel,
          conversationId: conversationId || undefined,
        });
        if (
          prepared.question !== question ||
          prepared.mode !== mode ||
          prepared.hintLevel !== hintLevel ||
          (conversationId && prepared.conversationId !== conversationId)
        )
          prepared = { question, mode, hintLevel, conversationId: conversationId || undefined };
        if (!prepared.conversationId) {
          prepared.conversationId = await create();
          writeSaved(key, prepared);
        }
        const id = prepared.conversationId;
        writeSaved(`tf.conversation.${session.userId}.${workspace.id}`, id);
        if (!prepared.snapshotId) {
          const context = await api.request<Context>(`/workspaces/${workspace.id}/context`, 'POST');
          prepared.snapshotId = context.id;
          writeSaved(key, prepared);
        }
        const request = await api.request<TutorRequest>(
          '/tutor/requests',
          'POST',
          { conversationId: id, snapshotId: prepared.snapshotId, mode, question, hintLevel },
          { idempotent: true },
        );
        sessionStorage.removeItem(key);
        return { id, request };
      },
      ({ id }) => {
        setConversationId(id);
        setQuestion('');
        refresh();
        setPoll((n) => n + 1);
      },
    );
  };
  return (
    <div className="tutor-layout">
      <aside className="conversation-list">
        <button
          className="wide"
          disabled={mutation.busy}
          onClick={() =>
            void mutation.run(create, (id) => {
              setConversationId(id);
              refresh();
            })
          }
        >
          <Plus size={16} />
          新建对话
        </button>
        <ErrorNotice error={conversations.error} retry={conversations.reload} />
        {conversations.data?.items.map((c) => (
          <button
            className={c.id === conversationId ? 'selected' : ''}
            onClick={() => setConversationId(c.id)}
            key={c.id}
          >
            <strong>{c.title}</strong>
            <small>{new Date(c.createdAt).toLocaleDateString('zh-CN')}</small>
          </button>
        ))}
      </aside>
      <section className="conversation-main">
        <div className="conversation-toolbar">
          <Field label="回答方式">
            <select
              value={mode}
              onChange={(e) => setMode(e.target.value as 'EVIDENCE_ONLY' | 'MODEL')}
            >
              <option value="EVIDENCE_ONLY">本地证据整理 · 无模型</option>
              <option value="MODEL" disabled={!can('TUTOR_MODEL')}>
                模型导师{!can('TUTOR_MODEL') ? ' · 管理员尚未配置' : ''}
              </option>
            </select>
          </Field>
          <Field label="提示深度">
            <select value={hintLevel} onChange={(e) => setHintLevel(Number(e.target.value))}>
              <option value={0}>0 · 方向提示</option>
              <option value={1}>1 · 相关规则</option>
              <option value={2}>2 · 关键步骤</option>
              <option value={3}>3 · 详细解释</option>
              <option value={4}>4 · 完整帮助</option>
            </select>
          </Field>
        </div>
        {mode === 'MODEL' && (
          <p className="provider-disclosure">
            发送到管理员配置的模型供应商：本次问题、最多 4
            个先前用户问题、快照中的目标与记忆摘要、可选的本人作答和公开证据。标准答案、隐藏测试和先前模型答案不在发送范围内。
          </p>
        )}
        <ErrorNotice error={detail.error} retry={detail.reload} />
        <div className="messages" aria-live="polite">
          {detail.loading && !detail.data ? (
            <Loading />
          ) : detail.data?.messages.length ? (
            detail.data.messages.map((request) => (
              <TutorMessage
                key={request.id}
                request={request}
                onChange={() => {
                  setPoll((n) => n + 1);
                  refresh();
                }}
              />
            ))
          ) : (
            <Empty title="从一个具体疑问开始">
              例如：“为什么 List&lt;Integer&gt; 不能赋给
              List&lt;Number&gt;？”本地模式只整理当前发布版本的知识证据。
            </Empty>
          )}
        </div>
        <form
          className="composer"
          onSubmit={(e) => {
            e.preventDefault();
            send();
          }}
        >
          <label className="sr-only" htmlFor="tutor-question">
            输入你的问题
          </label>
          <textarea
            id="tutor-question"
            rows={3}
            value={question}
            maxLength={4000}
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => {
              if (submitOnEnter(e)) {
                e.preventDefault();
                send();
              }
            }}
            placeholder="写下你的疑问。Enter 发送，Shift+Enter 换行。"
          />
          <div className="row spread">
            <span className="muted small">
              {mode === 'EVIDENCE_ONLY'
                ? '本地课程证据 · 不调用大语言模型'
                : '将调用已配置的模型供应商'}
            </span>
            <button
              className="primary"
              aria-label="发送问题"
              disabled={mutation.busy || !question.trim()}
            >
              {mutation.busy ? '提交中…' : <ArrowUp size={20} />}
            </button>
          </div>
        </form>
        <ErrorNotice error={mutation.error} />
      </section>
    </div>
  );
}
function TutorMessage({ request, onChange }: { request: TutorRequest; onChange: () => void }) {
  const { navigate, refresh } = useWorkspace();
  const mutation = useMutation();
  return (
    <article className="conversation-turn">
      <div className="user-message">
        <span>你</span>
        <p>{request.question}</p>
      </div>
      <div className="tutor-message">
        <div className="row spread">
          <strong>{request.mode === 'EVIDENCE_ONLY' ? '本地证据整理' : '模型导师'}</strong>
          <Status value={request.status} />
        </div>
        {request.sourceState === 'STALE' ? (
          <div className="notice warning">
            关联快照或来源已失效，旧回答已隐藏。请用当前上下文重新提问。
          </div>
        ) : request.answer ? (
          <p className="preserve-text">{request.answer}</p>
        ) : !terminalTutor.includes(request.status) ? (
          <Loading label="请求已持久化，正在等待服务端处理…" />
        ) : request.status === 'INSUFFICIENT_EVIDENCE' ? (
          <p>没有足够的匹配课程证据，请缩小问题或明确一个泛型术语。</p>
        ) : request.status === 'CANCELLED' ? (
          <p className="muted">此请求已取消，不会展示迟到的回答。</p>
        ) : (
          <p className="muted">本次未产生可展示回答。{request.errorCode}</p>
        )}
        {request.sourceState !== 'STALE' && request.evidenceRefs.length > 0 && (
          <details>
            <summary>{request.evidenceRefs.length} 条课程证据</summary>
            <EvidenceList items={request.evidenceRefs} compact />
          </details>
        )}
        {request.limitations && <p className="muted small">{request.limitations}</p>}
        {request.usage.billingState === 'UNKNOWN' && (
          <div className="notice warning">
            供应商是否已消耗额度尚不确定，保留预留额度。系统不会自动重试此模型请求。
          </div>
        )}
        {request.status === 'COMPLETED' && request.sourceState === 'CURRENT' && (
          <button
            disabled={mutation.busy}
            onClick={() =>
              void mutation.run(
                () =>
                  api.request<{ artifactId: string }>(
                    `/tutor/requests/${request.id}/document`,
                    'POST',
                    { title: request.question.slice(0, 120) || '导师学习文档' },
                    { idempotent: true },
                  ),
                (result) => {
                  refresh();
                  navigate('studio', result.artifactId);
                },
              )
            }
          >
            保存为学习文档
          </button>
        )}
        {!terminalTutor.includes(request.status) && (
          <button
            disabled={mutation.busy}
            onClick={() =>
              void mutation.run(
                () =>
                  api.request(`/tutor/requests/${request.id}/cancel`, 'POST', {
                    expectedEpoch: request.epoch,
                  }),
                onChange,
              )
            }
          >
            <Square size={14} />
            取消这个请求
          </button>
        )}
        <ErrorNotice error={mutation.error} />
      </div>
    </article>
  );
}
function EvidenceWorkflow() {
  const { workspace, session, events, refresh, can } = useWorkspace();
  const key = `tf.run.${session.userId}.${workspace.id}`;
  const [query, setQuery] = useState('PECS 的读取与写入边界');
  const [runId, setRunId] = useState(() => readSaved<string>(key, ''));
  const [poll, setPoll] = useState(0);
  const mutation = useMutation();
  const result = useResource<Run>(runId ? `/agent-runs/${runId}` : null, poll);
  const run = result.data;
  useEffect(() => {
    if (!runId) {
      const ids = events
        .map((e) => (typeof e.data.runId === 'string' ? e.data.runId : ''))
        .filter(Boolean);
      if (ids.length) setRunId(ids[ids.length - 1]);
    }
  }, [events, runId]);
  useEffect(() => {
    if (runId) writeSaved(key, runId);
  }, [runId, key]);
  useEffect(() => {
    if (run && !terminalRun.includes(run.status) && run.status !== 'PAUSED') {
      const timer = setTimeout(() => setPoll((n) => n + 1), 1500);
      return () => clearTimeout(timer);
    }
  }, [run, poll]);
  const launch = () =>
    mutation.run(
      async () => {
        const preparationKey = `${key}.preparing`;
        let prepared = readSaved<{ query: string; goalId?: string; snapshotId?: string }>(
          preparationKey,
          { query },
        );
        if (prepared.query !== query) prepared = { query };
        if (!prepared.goalId) {
          const goal = await api.request<{ id: string }>(
            `/workspaces/${workspace.id}/goals`,
            'POST',
            { text: query, acceptance: ['用当前发布课程的来源形成可追溯证据摘要'] },
            { idempotent: true },
          );
          prepared.goalId = goal.id;
          writeSaved(preparationKey, prepared);
        }
        if (!prepared.snapshotId) {
          const context = await api.request<Context>(`/workspaces/${workspace.id}/context`, 'POST');
          prepared.snapshotId = context.id;
          writeSaved(preparationKey, prepared);
        }
        const run = await api.request<Run>(
          `/workspaces/${workspace.id}/evidence-runs`,
          'POST',
          { goalId: prepared.goalId, snapshotId: prepared.snapshotId, query },
          { idempotent: true },
        );
        sessionStorage.removeItem(preparationKey);
        return run;
      },
      (r) => {
        setRunId(r.id);
        refresh();
        setPoll((n) => n + 1);
      },
    );
  const command = (kind: string) =>
    run &&
    mutation.run(
      () =>
        api.request<Run>(`/agent-runs/${run.id}/commands`, 'POST', {
          kind,
          expectedPlanRevision: run.planRevision,
          expectedRunEpoch: run.runEpoch,
        }),
      () => {
        setPoll((n) => n + 1);
        refresh();
      },
    );
  return (
    <div className="workflow-layout">
      <section className="panel">
        <h2>
          <Workflow size={20} />
          课程证据工作流
        </h2>
        <p className="muted">
          真实执行本地知识检索与证据源稿整理。此工作流的计划由规则生成，不会伪装成自主 LLM
          专家集群。
        </p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void launch();
          }}
        >
          <Field label="你要追溯的问题">
            <textarea
              rows={4}
              required
              maxLength={500}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </Field>
          <button
            className="primary"
            disabled={mutation.busy || !can('LOCAL_EVIDENCE_WORKFLOW') || !query.trim()}
          >
            {mutation.busy ? '正在创建快照与任务…' : '启动证据工作流'}
          </button>
        </form>
        <ErrorNotice error={mutation.error} />
      </section>
      <section className="panel">
        <div className="row spread">
          <h2>执行状态</h2>
          {run && <Status value={run.status} />}
        </div>
        <ErrorNotice error={result.error} retry={result.reload} />
        {result.loading && !run ? (
          <Loading />
        ) : run ? (
          <>
            <dl className="run-metadata">
              <div>
                <dt>计划版本</dt>
                <dd>{run.planRevision}</dd>
              </div>
              <div>
                <dt>运行世代</dt>
                <dd>{run.runEpoch}</dd>
              </div>
              <div>
                <dt>已成功步骤</dt>
                <dd>
                  {run.steps.filter((s) => s.status === 'SUCCEEDED').length} / {run.steps.length}
                </dd>
              </div>
            </dl>
            <ol className="run-steps">
              {run.steps.map((step, index) => (
                <li key={step.id}>
                  <span className={`step-number ${step.status === 'SUCCEEDED' ? 'done' : ''}`}>
                    {index + 1}
                  </span>
                  <div>
                    <strong>{step.role}</strong>
                    <small>
                      {step.key} · 尝试 {step.attempts} 次{step.required ? ' · 必需步骤' : ''}
                    </small>
                    {step.outputRefs.length > 0 && (
                      <small>输出引用 {step.outputRefs.length} 条</small>
                    )}
                  </div>
                  <Status value={step.status} />
                </li>
              ))}
            </ol>
            <div className="row wrap">
              {run.status === 'PAUSED' ? (
                <button disabled={mutation.busy} onClick={() => void command('RESUME')}>
                  <Play size={15} />
                  继续
                </button>
              ) : (
                !terminalRun.includes(run.status) && (
                  <button disabled={mutation.busy} onClick={() => void command('PAUSE')}>
                    <CirclePause size={15} />
                    暂停
                  </button>
                )
              )}
              {!terminalRun.includes(run.status) && (
                <button disabled={mutation.busy} onClick={() => void command('CANCEL')}>
                  <Square size={15} />
                  取消任务
                </button>
              )}
              <button className="text-button" onClick={result.reload}>
                刷新服务端状态
              </button>
            </div>
            {run.limitations.map((limit) => (
              <p className="muted small" key={limit}>
                {limit}
              </p>
            ))}
            {run.status === 'COMPLETED' && (
              <p className="confirmed">任务已完成。生成的源稿可在“作品与错题集”中查看。</p>
            )}
            <p className="muted small">任务 ID：{run.id}</p>
          </>
        ) : (
          <Empty title="尚未选择证据任务">启动后可以查看真实步骤、暂停与取消。</Empty>
        )}
      </section>
    </div>
  );
}
