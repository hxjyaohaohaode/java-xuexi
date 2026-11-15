import { useEffect, useState } from 'react';
import { NotebookPen, SlidersHorizontal } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource, useSessionDraft, useUnsavedChanges } from '../hooks';
import {
  Badge,
  Confirmed,
  Empty,
  ErrorNotice,
  Field,
  Loading,
  SectionTitle,
} from '../components/ui';
import { AccountPanel } from './Account';
import type { Goal, Memory, Preference, Workspace } from '../types';

export function MemoryPage() {
  const { workspace, session, tick, refresh } = useWorkspace();
  const memories = useResource<Memory[]>(`/workspaces/${workspace.id}/memories`, tick);
  const [text, setText] = useSessionDraft(`tf.memory-input.${session.userId}.${workspace.id}`, '');
  useUnsavedChanges(!!text.trim());
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
                disabled={mutation.busy}
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
  const { workspace, session, tick, refresh } = useWorkspace();
  const goals = useResource<Goal[]>(`/workspaces/${workspace.id}/goals`, tick);
  const [text, setText] = useSessionDraft(`tf.goal-input.${session.userId}.${workspace.id}`, '');
  const [acceptance, setAcceptance] = useSessionDraft(
    `tf.goal-criteria.${session.userId}.${workspace.id}`,
    '能用代码与规则解释自己的判断',
  );
  const criteria = acceptance
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  const validCriteria =
    criteria.length > 0 && criteria.length <= 20 && criteria.every((line) => line.length <= 500);
  useUnsavedChanges(!!text.trim());
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
          if (!validCriteria) return;
          void mutation.run(
            () =>
              api.request<Goal>(
                `/workspaces/${workspace.id}/goals`,
                'POST',
                {
                  text,
                  acceptance: criteria,
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
              maxLength={4000}
              value={text}
              disabled={mutation.busy}
              onChange={(e) => setText(e.target.value)}
            />
          </Field>
          <Field label="验收标准（每行一条）" hint="1 至 20 项，每项最多 500 字符。">
            <textarea
              rows={3}
              required
              maxLength={4000}
              value={acceptance}
              disabled={mutation.busy}
              onChange={(e) => setAcceptance(e.target.value)}
            />
          </Field>
        </div>
        {!validCriteria && (
          <p className="field-error">请填写 1 至 20 项验收标准，每项最多 500 字符。</p>
        )}
        <button disabled={mutation.busy || !text.trim() || !validCriteria}>保存学习目标</button>
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
  const { session, workspace } = useWorkspace();
  const prefix = `tf.goal-edit.${session.userId}.${workspace.id}.${goal.id}`;
  const [editing, setEditing] = useSessionDraft(`${prefix}.editing`, false);
  const [text, setText] = useSessionDraft(`${prefix}.text`, goal.text);
  const [acceptance, setAcceptance] = useSessionDraft(
    `${prefix}.acceptance`,
    goal.acceptance.join('\n'),
  );
  const [base, setBase] = useSessionDraft(`${prefix}.base`, goal.revision);
  const criteria = acceptance
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  const validCriteria =
    criteria.length > 0 && criteria.length <= 20 && criteria.every((line) => line.length <= 500);
  useUnsavedChanges(editing && (text !== goal.text || acceptance !== goal.acceptance.join('\n')));
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
              disabled={mutation.busy}
              rows={3}
              maxLength={4000}
              onChange={(e) => setText(e.target.value)}
            />
          </Field>
          <Field label="修改验收标准">
            <textarea
              value={acceptance}
              disabled={mutation.busy}
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
              disabled={mutation.busy || goal.revision !== base || !validCriteria || !text.trim()}
              onClick={() => void save('ACTIVE')}
            >
              保存目标新版本
            </button>
            <button
              disabled={mutation.busy || goal.revision !== base || !validCriteria || !text.trim()}
              onClick={() => void save('ARCHIVED')}
            >
              归档这个目标
            </button>
            <button
              disabled={mutation.busy}
              className="text-button"
              onClick={() => setEditing(false)}
            >
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
let preferenceOwner: string | null = null;
let appliedPreferenceRevision = -1;
export function setPreferenceOwner(owner: string | null) {
  if (owner === preferenceOwner) return;
  clearThemeListener?.();
  clearThemeListener = undefined;
  preferenceOwner = owner;
  appliedPreferenceRevision = -1;
  delete document.documentElement.dataset.theme;
  delete document.documentElement.dataset.reducedMotion;
  document.documentElement.style.removeProperty('--code-font-size');
}
export function applyPreference(preference: Preference, owner: string) {
  if (owner !== preferenceOwner || preference.revision <= appliedPreferenceRevision) return;
  appliedPreferenceRevision = preference.revision;
  clearThemeListener?.();
  const media = matchMedia('(prefers-color-scheme: dark)');
  const apply = () => {
    if (owner !== preferenceOwner || preference.revision !== appliedPreferenceRevision) return;
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
  const { workspace, session, capabilities, can, tick, refresh } = useWorkspace();
  const preference = useResource<Preference>(can('USER_PREFERENCES') ? '/me/preferences' : null);
  const remoteWorkspace = useResource<Workspace>(`/workspaces/${workspace.id}`, tick);
  const [draft, setDraft] = useSessionDraft<Preference | null>(
    `tf.preference-draft.${session.userId}`,
    null,
  );
  const [dirty, setDirty] = useSessionDraft(`tf.preference-dirty.${session.userId}`, false);
  const [spaceDraft, setSpaceDraft] = useSessionDraft(
    `tf.workspace-draft.${session.userId}.${workspace.id}`,
    {
      title: workspace.title,
      guidanceMode: workspace.guidanceMode,
      revision: workspace.revision,
    },
  );
  const [spaceDirty, setSpaceDirty] = useSessionDraft(
    `tf.workspace-dirty.${session.userId}.${workspace.id}`,
    false,
  );
  const [spaceSaved, setSpaceSaved] = useState(false);
  const workspaceMutation = useMutation();
  useEffect(() => {
    if (remoteWorkspace.data && !spaceDirty && remoteWorkspace.data.revision > spaceDraft.revision)
      setSpaceDraft({
        title: remoteWorkspace.data.title,
        guidanceMode: remoteWorkspace.data.guidanceMode,
        revision: remoteWorkspace.data.revision,
      });
  }, [remoteWorkspace.data, spaceDirty, spaceDraft.revision]);
  const workspaceConflict =
    !!remoteWorkspace.data && spaceDirty && remoteWorkspace.data.revision !== spaceDraft.revision;
  const [saved, setSaved] = useState(false);
  const mutation = useMutation();
  useEffect(() => {
    if (preference.data && !dirty && (!draft || preference.data.revision > draft.revision)) {
      setDraft(preference.data);
      applyPreference(preference.data, session.userId);
    }
  }, [preference.data, dirty, draft, session.userId]);
  useUnsavedChanges(dirty || spaceDirty);
  const edit = (patch: Partial<Preference>) => {
    if (draft && !mutation.busy) {
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
      {can('ACCOUNT_LIFECYCLE') && <AccountPanel />}
      <div className="two-column">
        <section className="panel">
          <h2>
            <SlidersHorizontal size={20} />
            显示偏好
          </h2>
          <ErrorNotice error={preference.error} retry={preference.reload} />
          {preference.loading ? (
            <Loading />
          ) : draft && can('USER_PREFERENCES') ? (
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
                    setDraft((current) => (current && current.revision > p.revision ? current : p));
                    setDirty(false);
                    setSaved(true);
                    applyPreference(p, session.userId);
                  },
                );
              }}
            >
              <Field label="主题">
                <select
                  value={draft.theme}
                  disabled={mutation.busy}
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
                  disabled={mutation.busy}
                  onChange={(e) => edit({ codeFontSize: Number(e.target.value) })}
                />
              </Field>
              <label className="checkbox-field">
                <input
                  type="checkbox"
                  checked={draft.reducedMotion}
                  disabled={mutation.busy}
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
          <ErrorNotice error={mutation.error} retry={preference.reload} />
          {draft && preference.data && dirty && draft.revision !== preference.data.revision && (
            <div className="notice warning">
              <div>
                <strong>显示偏好已有新版本，当前选择已保留。</strong>
                <div className="row wrap">
                  <button
                    onClick={() => {
                      setDraft(preference.data!);
                      setDirty(false);
                      mutation.clearError();
                      applyPreference(preference.data!, session.userId);
                    }}
                  >
                    采用最新偏好
                  </button>
                  <button
                    className="text-button"
                    onClick={() => {
                      setDraft({ ...draft, revision: preference.data!.revision });
                      mutation.clearError();
                    }}
                  >
                    保留我的选择，以最新版本为基线
                  </button>
                </div>
              </div>
            </div>
          )}
          <hr />
          <h2>工作空间设置</h2>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (remoteWorkspace.data)
                void workspaceMutation.run(
                  () =>
                    api.request<Workspace>(`/workspaces/${workspace.id}`, 'PATCH', {
                      expectedRevision: spaceDraft.revision,
                      title: spaceDraft.title,
                      guidanceMode: spaceDraft.guidanceMode,
                    }),
                  (value) => {
                    setSpaceDraft({
                      title: value.title,
                      guidanceMode: value.guidanceMode,
                      revision: value.revision,
                    });
                    setSpaceDirty(false);
                    setSpaceSaved(true);
                    refresh();
                  },
                );
            }}
          >
            <Field label="空间名称">
              <input
                maxLength={255}
                required
                value={spaceDraft.title}
                disabled={workspaceMutation.busy}
                onChange={(e) => {
                  setSpaceDraft({ ...spaceDraft, title: e.target.value });
                  setSpaceDirty(true);
                  setSpaceSaved(false);
                }}
              />
            </Field>
            <Field label="指导方式">
              <select
                value={spaceDraft.guidanceMode}
                disabled={workspaceMutation.busy}
                onChange={(e) => {
                  setSpaceDraft({
                    ...spaceDraft,
                    guidanceMode: e.target.value as Workspace['guidanceMode'],
                  });
                  setSpaceDirty(true);
                  setSpaceSaved(false);
                }}
              >
                <option value="ON_DEMAND">按需协助</option>
                <option value="COLLABORATIVE">协作指导</option>
                <option value="QUIET">安静学习</option>
              </select>
            </Field>
            <p className="muted small">
              此偏好会保存到上下文。主动生成仍取决于实际启用的模型与任务能力。
            </p>
            {workspaceConflict && (
              <div className="notice warning">
                <div>
                  <strong>空间设置已被更新，当前输入已保留。</strong>
                  <p>最新名称：{remoteWorkspace.data!.title}。请比较后再选择。</p>
                  <button
                    type="button"
                    onClick={() => {
                      setSpaceDraft({
                        title: remoteWorkspace.data!.title,
                        guidanceMode: remoteWorkspace.data!.guidanceMode,
                        revision: remoteWorkspace.data!.revision,
                      });
                      setSpaceDirty(false);
                      workspaceMutation.clearError();
                    }}
                  >
                    采用最新设置
                  </button>
                  <button
                    type="button"
                    className="text-button"
                    onClick={() => {
                      setSpaceDraft({ ...spaceDraft, revision: remoteWorkspace.data!.revision });
                      workspaceMutation.clearError();
                    }}
                  >
                    保留我的输入，以最新版本为基线
                  </button>
                </div>
              </div>
            )}
            <button
              disabled={
                workspaceMutation.busy ||
                workspaceConflict ||
                !spaceDirty ||
                !spaceDraft.title.trim() ||
                !remoteWorkspace.data
              }
            >
              保存空间设置
            </button>
            <ErrorNotice error={workspaceMutation.error} retry={remoteWorkspace.reload} />
            {spaceSaved && <Confirmed>工作空间设置已保存。</Confirmed>}
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
            这里反映当前运行环境的真实开关。外部服务未配置时，相应操作会保持关闭。
          </p>
        </section>
      </div>
    </>
  );
}
