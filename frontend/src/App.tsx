import { useEffect, useRef, useState } from 'react';
import {
  BookOpen,
  CheckSquare,
  FlaskConical,
  FolderPen,
  ListChecks,
  LogOut,
  Menu,
  MessageSquare,
  NotebookPen,
  Plus,
  Settings2,
  X,
  ChevronDown,
} from 'lucide-react';
import { api } from './api';
import { useMutation, useResource, useWorkspaceEvents } from './hooks';
import { WorkspaceContext, type Route } from './context';
import { ErrorNotice, Field, Loading, Empty } from './components/ui';
import { Auth } from './pages/Auth';
import { Learning } from './pages/Learning';
import { Practice } from './pages/Practice';
import { ProgressPage } from './pages/Progress';
import { applyPreference, MemoryPage, SettingsPage } from './pages/Personal';
import { Studio } from './pages/Studio';
import { Tutor } from './pages/Tutor';
import { Lab } from './pages/Lab';
import type { Capabilities, Preference, Session, Workspace } from './types';
import { readSaved, writeSaved } from './state';

const navigation: [Route, string, typeof BookOpen][] = [
  ['learn', '知识与阅读', BookOpen],
  ['practice', '练习与反馈', CheckSquare],
  ['progress', '进度与复习', ListChecks],
  ['lab', '类型实验', FlaskConical],
  ['studio', '作品与错题集', FolderPen],
  ['tutor', '导师与证据任务', MessageSquare],
  ['memory', '笔记与记忆', NotebookPen],
];
function routeFromHash(): { route: Route; selection: string } {
  const [route, selection = ''] = location.hash.slice(1).split('/');
  return {
    route: ([...navigation.map((n) => n[0]), 'settings'] as string[]).includes(route)
      ? (route as Route)
      : 'learn',
    selection: decodeURIComponent(selection),
  };
}
export default function App() {
  const [session, setSession] = useState<Session | null | undefined>(undefined);
  const [bootError, setBootError] = useState<unknown>(null);
  const sessionGeneration = useRef(0);
  const bootstrap = () => {
    const generation = ++sessionGeneration.current;
    setBootError(null);
    api
      .request<Session>('/auth/session', 'GET', undefined, { authExpected: false })
      .then((s) => {
        if (generation !== sessionGeneration.current) return;
        api.scope = s.userId;
        setSession(s);
      })
      .catch((e) => {
        if (generation !== sessionGeneration.current) return;
        if (e.status === 401) {
          api.clearPrivateState();
          setSession(null);
        } else setBootError(e);
      });
  };
  useEffect(() => {
    bootstrap();
    api.onUnauthenticated = () => {
      sessionGeneration.current += 1;
      api.clearPrivateState();
      setSession(null);
    };
    return () => {
      sessionGeneration.current += 1;
      api.onUnauthenticated = null;
    };
  }, []);
  if (bootError)
    return (
      <main className="boot">
        <ErrorNotice error={bootError} retry={bootstrap} />
      </main>
    );
  if (session === undefined)
    return (
      <main className="boot">
        <Loading label="正在检查学习会话…" />
      </main>
    );
  if (!session)
    return (
      <Auth
        onSession={(s) => {
          sessionGeneration.current += 1;
          api.scope = s.userId;
          setSession(s);
        }}
      />
    );
  return (
    <SignedIn
      key={session.userId}
      session={session}
      onLogout={() => {
        sessionGeneration.current += 1;
        api.clearPrivateState();
        setSession(null);
      }}
    />
  );
}
function SignedIn({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const workspaces = useResource<Workspace[]>('/workspaces?limit=100');
  const capabilities = useResource<Capabilities>('/capabilities');
  const activeRelease = useResource<{ releaseId: string }>('/releases/active');
  const preference = useResource<Preference>(
    capabilities.data?.enabled.includes('USER_PREFERENCES') ? '/me/preferences' : null,
  );
  useEffect(() => {
    if (preference.data) applyPreference(preference.data);
  }, [preference.data]);
  const [selected, setSelected] = useState(() =>
    readSaved<string>(`tf.workspace.${session.userId}`, ''),
  );
  const [creating, setCreating] = useState(false);
  const [createdWorkspace, setCreatedWorkspace] = useState<Workspace | null>(null);
  const [title, setTitle] = useState('我的泛型学习');
  const mutation = useMutation();
  const availableWorkspaces = [
    ...(createdWorkspace && !workspaces.data?.some((w) => w.id === createdWorkspace.id)
      ? [createdWorkspace]
      : []),
    ...(workspaces.data || []),
  ];
  useEffect(() => {
    if (availableWorkspaces.length && !availableWorkspaces.some((w) => w.id === selected))
      setSelected(availableWorkspaces[0].id);
    if (createdWorkspace && workspaces.data?.some((w) => w.id === createdWorkspace.id))
      setCreatedWorkspace(null);
  }, [workspaces.data, selected, createdWorkspace]);
  const workspace = availableWorkspaces.find((w) => w.id === selected);
  const create = async (e: React.SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!activeRelease.data) return;
    await mutation.run(
      () =>
        api.request<Workspace>(
          '/workspaces',
          'POST',
          { releaseId: activeRelease.data!.releaseId, title, guidanceMode: 'ON_DEMAND' },
          { idempotent: true },
        ),
      (w) => {
        setCreatedWorkspace(w);
        setSelected(w.id);
        writeSaved(`tf.workspace.${session.userId}`, w.id);
        setCreating(false);
        workspaces.reload();
      },
    );
  };
  const choose = (id: string) => {
    setSelected(id);
    writeSaved(`tf.workspace.${session.userId}`, id);
  };
  const logout = () => mutation.run(() => api.request('/auth/session', 'DELETE'), onLogout);
  if ((workspaces.loading && !workspaces.data) || (capabilities.loading && !capabilities.data))
    return (
      <main className="boot">
        <Loading />
      </main>
    );
  if (!capabilities.data || !workspaces.data)
    return (
      <main className="boot">
        <ErrorNotice
          error={workspaces.error || capabilities.error}
          retry={() => {
            workspaces.reload();
            capabilities.reload();
          }}
        />
        <button onClick={() => void logout()}>退出会话</button>
      </main>
    );
  const createForm = (
    <form className="workspace-form" onSubmit={create}>
      <Field label="工作空间名称">
        <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} required />
      </Field>
      <p className="muted">使用当前已发布的课程版本。不同工作空间的上下文与作品彼此隔离。</p>
      <ErrorNotice error={mutation.error || activeRelease.error} />
      <div className="row">
        <button className="primary" disabled={mutation.busy || !activeRelease.data}>
          {mutation.busy ? '正在创建…' : '创建工作空间'}
        </button>
        {workspace && (
          <button type="button" onClick={() => setCreating(false)}>
            取消
          </button>
        )}
      </div>
    </form>
  );
  if (!workspace)
    return (
      <main className="onboard">
        <div className="brand">
          <span className="brand-mark">{'<T>'}</span>TypeForge
        </div>
        <h1>给学习留一个空间。</h1>
        <p className="muted">把练习、规则、笔记和作品放在一起，让每一步可以接着往下走。</p>
        {activeRelease.loading ? (
          <Loading />
        ) : activeRelease.error ? (
          <Empty title="当前还没有可用课程">
            <ErrorNotice error={activeRelease.error} retry={activeRelease.reload} />
          </Empty>
        ) : (
          createForm
        )}
        <button className="text-button" onClick={() => void logout()}>
          退出登录
        </button>
      </main>
    );
  return (
    <>
      <WorkspaceShell
        key={workspace.id}
        workspace={workspace}
        workspaces={availableWorkspaces}
        capabilities={capabilities.data}
        session={session}
        choose={choose}
        create={() => setCreating(true)}
        logout={() => void logout()}
        refreshWorkspaces={workspaces.reload}
      />
      {creating && (
        <div className="modal-backdrop">
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="new-workspace"
            onKeyDown={(e) => {
              if (e.key === 'Escape') setCreating(false);
              if (e.key === 'Tab') {
                const elements = Array.from(
                  e.currentTarget.querySelectorAll<HTMLElement>(
                    'button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href]',
                  ),
                );
                const first = elements[0],
                  last = elements[elements.length - 1];
                if (e.shiftKey && document.activeElement === first) {
                  e.preventDefault();
                  last?.focus();
                } else if (!e.shiftKey && document.activeElement === last) {
                  e.preventDefault();
                  first?.focus();
                }
              }
            }}
          >
            <div className="row spread">
              <h2 id="new-workspace">新建工作空间</h2>
              <button
                autoFocus
                className="icon-button"
                aria-label="关闭"
                onClick={() => setCreating(false)}
              >
                <X size={20} />
              </button>
            </div>
            {createForm}
          </section>
        </div>
      )}
    </>
  );
}
function WorkspaceShell({
  workspace,
  workspaces,
  capabilities,
  session,
  choose,
  create,
  logout,
  refreshWorkspaces,
}: {
  workspace: Workspace;
  workspaces: Workspace[];
  capabilities: Capabilities;
  session: Session;
  choose: (id: string) => void;
  create: () => void;
  logout: () => void;
  refreshWorkspaces: () => void;
}) {
  const [locationState, setLocationState] = useState(routeFromHash);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [tick, setTick] = useState(0);
  const refresh = () => {
    setTick((n) => n + 1);
    refreshWorkspaces();
  };
  const stream = useWorkspaceEvents(workspace.id, refresh);
  const navigate = (route: Route, selection = '') => {
    location.hash = `${route}${selection ? '/' + encodeURIComponent(selection) : ''}`;
    setMobileOpen(false);
  };
  useEffect(() => {
    const change = () => {
      setLocationState(routeFromHash());
      setMobileOpen(false);
    };
    window.addEventListener('hashchange', change);
    return () => window.removeEventListener('hashchange', change);
  }, []);
  const selected = navigation.find((n) => n[0] === locationState.route)?.[1] || '偏好与能力';
  return (
    <WorkspaceContext.Provider
      value={{
        workspace,
        session,
        capabilities,
        tick,
        refresh,
        events: stream.events,
        navigate,
        selection: locationState.selection,
        can: (c) => capabilities.enabled.includes(c),
      }}
    >
      <div className="app-layout">
        <a
          className="skip-link"
          href="#main-content"
          onClick={(event) => {
            event.preventDefault();
            document.getElementById('main-content')?.focus();
          }}
        >
          跳到主要内容
        </a>
        <aside className={`sidebar ${mobileOpen ? 'open' : ''}`} aria-label="主导航">
          <div className="brand">
            <span className="brand-mark">{'<T>'}</span>TypeForge
            <button
              className="mobile-only icon-button"
              aria-label="关闭菜单"
              onClick={() => setMobileOpen(false)}
            >
              <X size={20} />
            </button>
          </div>
          <div className="workspace-picker">
            <label htmlFor="workspace-select">当前工作空间</label>
            <div className="select-wrap">
              <select
                id="workspace-select"
                value={workspace.id}
                onChange={(e) => choose(e.target.value)}
              >
                {workspaces.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.title}
                  </option>
                ))}
              </select>
              <ChevronDown size={14} />
            </div>
            <button className="text-button small" onClick={create}>
              <Plus size={15} />
              新建工作空间
            </button>
          </div>
          <nav>
            {navigation.map(([id, label, Icon]) => (
              <a
                href={`#${id}`}
                key={id}
                className={locationState.route === id ? 'active' : ''}
                aria-current={locationState.route === id ? 'page' : undefined}
              >
                <Icon size={19} />
                {label}
              </a>
            ))}
          </nav>
          <div className="sidebar-bottom">
            <a href="#settings" className={locationState.route === 'settings' ? 'active' : ''}>
              <Settings2 size={18} />
              偏好与能力
            </a>
            <div className="account">
              <span className="avatar">{session.username.slice(0, 1).toUpperCase()}</span>
              <div>
                <strong>{session.username}</strong>
                <small>学习者</small>
              </div>
              <button className="icon-button" aria-label="退出登录" onClick={logout}>
                <LogOut size={17} />
              </button>
            </div>
          </div>
        </aside>
        {mobileOpen && (
          <button
            className="sidebar-overlay"
            aria-label="关闭菜单遮罩"
            onClick={() => setMobileOpen(false)}
          />
        )}
        <div className="main-shell">
          <header className="topbar">
            <div className="row">
              <button
                className="mobile-only icon-button"
                aria-label="打开菜单"
                onClick={() => setMobileOpen(true)}
              >
                <Menu size={21} />
              </button>
              <span>学习工作台</span>
              <span className="separator">/</span>
              <strong>{selected}</strong>
            </div>
            <div className="sync-state">
              <span className={`status-dot ${!stream.online || stream.error ? 'offline' : ''}`} />
              {!stream.online ? '离线 · 写入需恢复连接' : stream.error ? '同步中断' : '已连接'}
              <span className="desktop-only">· 事件 {stream.cursor}</span>
            </div>
          </header>
          <main id="main-content" className="page" tabIndex={-1}>
            <ErrorNotice error={stream.error} />
            {locationState.route === 'learn' ? (
              <Learning />
            ) : locationState.route === 'practice' ? (
              <Practice />
            ) : locationState.route === 'progress' ? (
              <ProgressPage />
            ) : locationState.route === 'memory' ? (
              <MemoryPage />
            ) : locationState.route === 'settings' ? (
              <SettingsPage />
            ) : locationState.route === 'studio' ? (
              <Studio />
            ) : locationState.route === 'tutor' ? (
              <Tutor />
            ) : (
              <Lab />
            )}
          </main>
          <footer className="workspace-footer">
            课程：Java 泛型与类型安全 <span>工作空间隔离 · 服务端确认后保存</span>
          </footer>
        </div>
      </div>
    </WorkspaceContext.Provider>
  );
}
