import { useEffect, useState } from 'react';
import { NotebookPen, SlidersHorizontal } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource } from '../hooks';
import {
  Badge,
  Confirmed,
  Empty,
  ErrorNotice,
  Field,
  Loading,
  SectionTitle,
} from '../components/ui';
import type { Goal, Memory, Preference, Workspace } from '../types';

export function MemoryPage() {
  const { workspace, tick, refresh } = useWorkspace();
  const memories = useResource<Memory[]>(`/workspaces/${workspace.id}/memories`, tick);
  const [text, setText] = useState('');
  const [saved, setSaved] = useState(false);
  const mutation = useMutation();
  const add = (e: React.SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    void mutation.run(
      () =>
        api.request<Memory>(
          `/workspaces/${workspace.id}/memories`,
          'POST',
          { summary: text },
          { idempotent: true },
        ),
      () => {
        setText('');
        setSaved(true);
        refresh();
      },
    );
  };
  return (
    <>
      <SectionTitle
        eyebrow="保留自己的判断，也保留撤回权"
        title="笔记与工作空间记忆"
        description="在这里写下学习目标、偏好或自己的理解。有效记忆会被纳入后续上下文；撤回后，相关来源与任务会重新检查有效性。"
      />
      <div className="two-column">
        <section className="panel">
          <h2>
            <NotebookPen size={20} />
            新增笔记
          </h2>
          <form onSubmit={add}>
            <Field label="你希望工作空间记住什么？">
              <textarea
                rows={8}
                maxLength={2000}
                required
                value={text}
                onChange={(e) => {
                  setText(e.target.value);
                  setSaved(false);
                }}
                placeholder="例如：我总把 List<Number> 与 List<Integer> 的继承关系混淆，后续解释希望先给反例。"
              />
            </Field>
            <p className="muted small">
              这会作为你明确提供的记忆保存，不会被标成系统观察到的事实。
            </p>
            <ErrorNotice error={mutation.error} />
            <button className="primary" disabled={mutation.busy || !text.trim()}>
              {mutation.busy ? '正在保存…' : '保存到工作空间'}
            </button>
            {saved && <Confirmed>笔记已保存，可在后续上下文中使用。</Confirmed>}
          </form>
        </section>
        <section className="panel">
          <div className="row spread">
            <h2>已保存记忆</h2>
            <Badge>
              {memories.data?.filter((m) => m.status === 'ACTIVE').length ?? '—'} 条有效
            </Badge>
          </div>
          <ErrorNotice error={memories.error} retry={memories.reload} />
          {memories.loading && !memories.data ? (
            <Loading />
          ) : memories.data?.length ? (
            memories.data.map((memory) => (
              <article
                className={`memory-item ${memory.status !== 'ACTIVE' ? 'revoked' : ''}`}
                key={memory.id}
              >
                <div className="row spread">
                  <Badge>
                    {memory.basis === 'USER_STATED'
                      ? '你明确提供'
                      : memory.basis === 'INFERRED'
                        ? '推断，需谨慎'
                        : '观察记录'}
                  </Badge>
                  <span className="muted small">版本 {memory.revision}</span>
                </div>
                <p className="preserve-text">{memory.summary}</p>
                {memory.status === 'ACTIVE' ? (
                  <button
                    className="text-button danger-text"
                    disabled={mutation.busy}
                    onClick={() =>
                      void mutation.run(
                        () =>
                          api.request(`/memories/${memory.id}/revoke`, 'POST', {
                            expectedRevision: memory.revision,
                          }),
                        () => refresh(),
                      )
                    }
                  >
                    撤回这条记忆
                  </button>
                ) : (
                  <Badge tone="warn">{memory.status === 'REVOKED' ? '已撤回' : '已过期'}</Badge>
                )}
              </article>
            ))
          ) : (
            !memories.error && (
              <Empty title="还没有工作空间记忆">你可以从一个学习偏好或容易混淆的问题开始。</Empty>
            )
          )}
        </section>
      </div>
      <GoalsPanel />
    </>
  );
}
function GoalsPanel() {
  const { workspace, tick, refresh } = useWorkspace();
  const goals = useResource<Goal[]>(`/workspaces/${workspace.id}/goals`, tick);
  const [text, setText] = useState('');
  const [acceptance, setAcceptance] = useState('能用代码与规则解释自己的判断');
  const mutation = useMutation();
  return (
    <section className="panel">
      <h2>学习目标与验收标准</h2>
      <p className="muted small">
        目标修改或归档后，依赖旧版本的上下文、任务和作品会失效。新任务会使用更新后的目标。
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void mutation.run(
            () =>
              api.request<Goal>(
                `/workspaces/${workspace.id}/goals`,
                'POST',
                {
                  text,
                  acceptance: acceptance
                    .split('\n')
                    .map((s) => s.trim())
                    .filter(Boolean),
                },
                { idempotent: true },
              ),
            () => {
              setText('');
              refresh();
            },
          );
        }}
      >
        <div className="two-column">
          <Field label="新目标">
            <textarea
              rows={3}
              required
              maxLength={2000}
              value={text}
              onChange={(e) => setText(e.target.value)}
            />
          </Field>
          <Field label="验收标准（每行一条）">
            <textarea
              rows={3}
              required
              maxLength={4000}
              value={acceptance}
              onChange={(e) => setAcceptance(e.target.value)}
            />
          </Field>
        </div>
        <button disabled={mutation.busy || !text.trim()}>保存学习目标</button>
      </form>
      <ErrorNotice
        error={mutation.error || goals.error}
        retry={goals.error ? goals.reload : undefined}
      />
      {goals.data?.map((goal) => (
        <GoalEditor key={goal.id} goal={goal} onChange={refresh} />
      ))}
    </section>
  );
}
function GoalEditor({ goal, onChange }: { goal: Goal; onChange: () => void }) {
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(goal.text);
  const [acceptance, setAcceptance] = useState(goal.acceptance.join('\n'));
  const [base, setBase] = useState(goal.revision);
  const mutation = useMutation();
  const save = (status: Goal['status']) =>
    mutation.run(
      () =>
        api.request<Goal>(`/goals/${goal.id}`, 'PATCH', {
          expectedRevision: base,
          text,
          acceptance: acceptance
            .split('\n')
            .map((s) => s.trim())
            .filter(Boolean),
          status,
        }),
      () => {
        setEditing(false);
        onChange();
      },
    );
  return (
    <article className="memory-item">
      <div className="row spread">
        <Badge>{goal.status === 'ACTIVE' ? '有效目标' : '已归档'}</Badge>
        <span className="muted small">版本 {goal.revision}</span>
      </div>
      {editing ? (
        <>
          <Field label="修改目标">
            <textarea
              value={text}
              rows={3}
              maxLength={2000}
              onChange={(e) => setText(e.target.value)}
            />
          </Field>
          <Field label="修改验收标准">
            <textarea
              value={acceptance}
              rows={3}
              maxLength={4000}
              onChange={(e) => setAcceptance(e.target.value)}
            />
          </Field>
          {goal.revision !== base && (
            <div className="notice warning">
              目标已有新版本。你的输入已保留；取消编辑后重新读取最新目标，再决定修改。
            </div>
          )}
          <div className="row wrap">
            <button
              disabled={mutation.busy || goal.revision !== base}
              onClick={() => void save('ACTIVE')}
            >
              保存目标新版本
            </button>
            <button
              disabled={mutation.busy || goal.revision !== base}
              onClick={() => void save('ARCHIVED')}
            >
              归档这个目标
            </button>
            <button className="text-button" onClick={() => setEditing(false)}>
              取消编辑
            </button>
          </div>
        </>
      ) : (
        <>
          <p className="preserve-text">{goal.text}</p>
          <ul className="goal-acceptance">
            {goal.acceptance.map((a, i) => (
              <li key={i}>{a}</li>
            ))}
          </ul>
          <button
            className="text-button"
            onClick={() => {
              setText(goal.text);
              setAcceptance(goal.acceptance.join('\n'));
              setBase(goal.revision);
              setEditing(true);
            }}
          >
            编辑或归档
          </button>
        </>
      )}
      <ErrorNotice error={mutation.error} />
    </article>
  );
}
let clearThemeListener: undefined | (() => void);
export function applyPreference(preference: Preference) {
  clearThemeListener?.();
  const media = matchMedia('(prefers-color-scheme: dark)');
  const apply = () => {
    const dark = preference.theme === 'dark' || (preference.theme === 'system' && media.matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.documentElement.dataset.reducedMotion = String(preference.reducedMotion);
    document.documentElement.style.setProperty('--code-font-size', `${preference.codeFontSize}px`);
  };
  apply();
  if (preference.theme === 'system') {
    media.addEventListener('change', apply);
    clearThemeListener = () => media.removeEventListener('change', apply);
  } else clearThemeListener = undefined;
}
export function SettingsPage() {
  const { workspace, capabilities, can, tick, refresh } = useWorkspace();
  const preference = useResource<Preference>(can('USER_PREFERENCES') ? '/me/preferences' : null);
  const remoteWorkspace = useResource<Workspace>(`/workspaces/${workspace.id}`, tick);
  const [draft, setDraft] = useState<Preference | null>(null);
  const [dirty, setDirty] = useState(false);
  const [title, setTitle] = useState(workspace.title);
  const [mode, setMode] = useState(workspace.guidanceMode);
  const [saved, setSaved] = useState(false);
  const mutation = useMutation();
  useEffect(() => {
    if (preference.data && !dirty) {
      setDraft(preference.data);
      applyPreference(preference.data);
    }
  }, [preference.data, dirty]);
  const edit = (patch: Partial<Preference>) => {
    if (draft) {
      setDraft({ ...draft, ...patch });
      setDirty(true);
      setSaved(false);
    }
  };
  return (
    <>
      <SectionTitle
        eyebrow="由你决定工作台如何协作"
        title="偏好与能力"
        description="可用能力来自服务端声明。尚未配置的能力保持明确关闭，不会用演示结果代替执行。"
      />
      <div className="two-column">
        <section className="panel">
          <h2>
            <SlidersHorizontal size={20} />
            显示偏好
          </h2>
          <ErrorNotice error={preference.error} retry={preference.reload} />
          {preference.loading ? (
            <Loading />
          ) : draft ? (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                void mutation.run(
                  () =>
                    api.request<Preference>(
                      '/me/preferences',
                      'PUT',
                      {
                        theme: draft.theme,
                        reducedMotion: draft.reducedMotion,
                        codeFontSize: draft.codeFontSize,
                        revision: draft.revision,
                      },
                      { headers: { 'If-Match': `"${draft.revision}"` } },
                    ),
                  (p) => {
                    setDraft(p);
                    setDirty(false);
                    setSaved(true);
                    applyPreference(p);
                  },
                );
              }}
            >
              <Field label="主题">
                <select
                  value={draft.theme}
                  onChange={(e) => edit({ theme: e.target.value as Preference['theme'] })}
                >
                  <option value="system">跟随系统</option>
                  <option value="light">浅色纸面</option>
                  <option value="dark">深色阅读</option>
                </select>
              </Field>
              <Field label={`代码字号 · ${draft.codeFontSize}px`}>
                <input
                  type="range"
                  min={14}
                  max={24}
                  value={draft.codeFontSize}
                  onChange={(e) => edit({ codeFontSize: Number(e.target.value) })}
                />
              </Field>
              <label className="checkbox-field">
                <input
                  type="checkbox"
                  checked={draft.reducedMotion}
                  onChange={(e) => edit({ reducedMotion: e.target.checked })}
                />
                减少界面动效
              </label>
              <button className="primary" disabled={mutation.busy || !dirty}>
                保存显示偏好
              </button>
              {saved && <Confirmed>服务端已保存显示偏好。</Confirmed>}
            </form>
          ) : (
            <Empty title="显示偏好服务尚未启用" />
          )}
          <ErrorNotice error={mutation.error} />
          <hr />
          <h2>工作空间设置</h2>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (remoteWorkspace.data)
                void mutation.run(
                  () =>
                    api.request<Workspace>(`/workspaces/${workspace.id}`, 'PATCH', {
                      expectedRevision: remoteWorkspace.data!.revision,
                      title,
                      guidanceMode: mode,
                    }),
                  () => {
                    refresh();
                  },
                );
            }}
          >
            <Field label="空间名称">
              <input
                maxLength={255}
                required
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
            </Field>
            <Field label="指导方式">
              <select
                value={mode}
                onChange={(e) => setMode(e.target.value as Workspace['guidanceMode'])}
              >
                <option value="ON_DEMAND">按需协助</option>
                <option value="COLLABORATIVE">协作指导</option>
                <option value="QUIET">安静学习</option>
              </select>
            </Field>
            <p className="muted small">
              此偏好会保存到上下文。主动生成仍取决于实际启用的模型与任务能力。
            </p>
            <button disabled={mutation.busy || !remoteWorkspace.data}>保存空间设置</button>
          </form>
          <ErrorNotice error={remoteWorkspace.error} retry={remoteWorkspace.reload} />
        </section>
        <section className="panel">
          <h2>当前可用能力</h2>
          <p className="muted">运行版本 {capabilities.runtimeVersion}</p>
          <ul className="capability-list">
            {capabilities.enabled.map((c) => (
              <li key={c}>
                <span className="status-dot" />
                <code>{c}</code>
              </li>
            ))}
          </ul>
          <h3>尚不可用</h3>
          <ul className="capability-list unavailable">
            {capabilities.unavailable.map((c) => (
              <li key={c}>
                <span className="status-dot offline" />
                <code>{c}</code>
              </li>
            ))}
          </ul>
          <p className="muted small">
            图片、音频、视频、Office 二进制导出和任意 Java
            编译器需对应适配器，不能从静态源稿推定为已接通。
          </p>
        </section>
      </div>
    </>
  );
}
