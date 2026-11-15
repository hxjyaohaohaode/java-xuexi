import { useEffect, useLayoutEffect, useRef, useState } from 'react';
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
  PanelLeftClose,
  PanelLeftOpen,
  WifiOff,
  LibraryBig,
  Workflow,
} from 'lucide-react';
import { api } from './api';
import { useMutation, useResource, useWorkspaceEvents } from './hooks';
import { WorkspaceContext, type Route } from './context';
import { Dialog, ErrorNotice, Field, Loading, Empty, lockBodyScroll } from './components/ui';
import { Auth } from './pages/Auth';
import { Learning } from './pages/Learning';
import { Practice } from './pages/Practice';
import { ProgressPage } from './pages/Progress';
import { applyPreference, MemoryPage, setPreferenceOwner, SettingsPage } from './pages/Personal';
import { Studio } from './pages/Studio';
import { Tutor } from './pages/Tutor';
import { Lab } from './pages/Lab';
import { Content } from './pages/Content';
import { Agents } from './pages/Agents';
import type { Capabilities, Preference, Session, Workspace } from './types';
import { readSaved, writeSaved } from './state';

const navigation: [Route, string, typeof BookOpen][] = [
  ['learn', '知识与阅读', BookOpen],
  ['practice', '练习与反馈', CheckSquare],
  ['progress', '进度与复习', ListChecks],
  ['lab', '类型实验', FlaskConical],
  ['studio', '作品与错题集', FolderPen],
  ['tutor', '导师与证据任务', MessageSquare],
  ['agents', '学习创作任务', Workflow],
  ['memory', '笔记与记忆', NotebookPen],
  ['content', '内容工作台', LibraryBig],
];
function routeFromHash(): { route: Route; selection: string } {
  const [route, selection = ''] = location.hash.slice(1).split('/');
  return {
    route: ([...navigation.map((n) => n[0]), 'settings'] as string[]).includes(route)
      ? (route as Route)
      : 'learn',
    selection: (() => {
      try {
        return decodeURIComponent(selection);
      } catch {
        return '';
      }
    })(),
  };
}
export default function App() {
  const [session, setSession] = useState<Session | null | undefined>(undefined);
  const [bootError, setBootError] = useState<unknown>(null);
  const [sessionNotice, setSessionNotice] = useState('');
  const sessionGeneration = useRef(0);
  const bootstrap = () => {
    const generation = ++sessionGeneration.current;
    setBootError(null);
    api
      .request<Session>('/auth/session', 'GET', undefined, { authExpected: false })
      .then((s) => {
        if (generation !== sessionGeneration.current) return;
        api.scope = s.userId;
        setPreferenceOwner(s.userId);
        setSession(s);
      })
      .catch((e) => {
        if (generation !== sessionGeneration.current) return;
        if (e.status === 401) {
          api.clearPrivateState();
          setPreferenceOwner(null);
          setSession(null);
        } else setBootError(e);
      });
  };
  useEffect(() => {
    bootstrap();
    api.onUnauthenticated = () => {
      setSessionNotice('会话已结束，请重新登录。你的已提交内容保存在服务端。');
      sessionGeneration.current += 1;
      api.clearPrivateState();
      setPreferenceOwner(null);
      setSession(null);
    };
    return () => {
      sessionGeneration.current += 1;
      api.onUnauthenticated = null;
      setPreferenceOwner(null);
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
        notice={sessionNotice}
        onSession={(s) => {
          setSessionNotice('');
          sessionGeneration.current += 1;
          api.scope = s.userId;
          setPreferenceOwner(s.userId);
          setSession(s);
        }}
      />
    );
  return (
    <SignedIn
      key={session.userId}
      session={session}
      onLogout={(notice) => {
        setSessionNotice(notice || '你已退出登录。');
        sessionGeneration.current += 1;
        api.clearPrivateState();
        setPreferenceOwner(null);
        setSession(null);
      }}
    />
  );
}
function SignedIn({
  session,
  onLogout,
}: {
  session: Session;
  onLogout: (notice?: string) => void;
}) {
  const workspaces = useResource<Workspace[]>('/workspaces?limit=100');
  const capabilities = useResource<Capabilities>('/capabilities');
  const activeRelease = useResource<{ releaseId: string }>('/releases/active');
  const preference = useResource<Preference>(
    capabilities.data?.enabled.includes('USER_PREFERENCES') ? '/me/preferences' : null,
  );
  useEffect(() => {
    if (preference.data) applyPreference(preference.data, session.userId);
  }, [preference.data, session.userId]);
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
  const logout = () =>
    mutation.run(
      () => api.request('/auth/session', 'DELETE'),
      () => onLogout(),
    );
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
        endSession={onLogout}
      />
      {creating && (
        <Dialog
          title="新建工作空间"
          onClose={() => {
            if (!mutation.busy) setCreating(false);
          }}
        >
          <div className="row spread">
            <h2 id="new-workspace">新建工作空间</h2>
            <button
              autoFocus
              className="icon-button"
              aria-label="关闭"
              disabled={mutation.busy}
              onClick={() => setCreating(false)}
            >
              <X size={20} />
            </button>
          </div>
          {createForm}
        </Dialog>
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
  endSession,
}: {
  workspace: Workspace;
  workspaces: Workspace[];
  capabilities: Capabilities;
  session: Session;
  choose: (id: string) => void;
  create: () => void;
  logout: () => void;
  refreshWorkspaces: () => void;
  endSession: (notice?: string) => void;
}) {
  const [locationState, setLocationState] = useState(routeFromHash);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const sidebar = useRef<HTMLElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null);
  const menuNavigation = useRef(false);
  const scrollPositions = useRef(new Map<string, number>());
  const routeKey = `${locationState.route}/${locationState.selection}`;
  const previousRoute = useRef(routeKey);
  const [tick, setTick] = useState(0);
  const refresh = () => {
    setTick((n) => n + 1);
    refreshWorkspaces();
  };
  const stream = useWorkspaceEvents(workspace.id, refresh);
  useEffect(() => {
    if (!mobileOpen) return;
    const releaseScroll = lockBodyScroll();
    sidebar.current?.querySelector<HTMLElement>('[aria-label="关闭菜单"]')?.focus();
    const closeOnDesktop = () => {
      if (window.innerWidth > 760) setMobileOpen(false);
    };
    window.addEventListener('resize', closeOnDesktop);
    return () => {
      releaseScroll();
      window.removeEventListener('resize', closeOnDesktop);
      if (menuNavigation.current)
        document.getElementById('main-content')?.focus({ preventScroll: true });
      else menuButton.current?.focus();
      menuNavigation.current = false;
    };
  }, [mobileOpen]);
  useLayoutEffect(() => {
    const key = routeKey;
    const position = scrollPositions.current.get(key) || 0;
    const frame = requestAnimationFrame(() => {
      window.scrollTo({ top: position, behavior: 'instant' });
      if (previousRoute.current.split('/')[0] !== locationState.route)
        document.getElementById('main-content')?.focus({ preventScroll: true });
      previousRoute.current = key;
    });
    return () => {
      cancelAnimationFrame(frame);
      scrollPositions.current.set(key, window.scrollY);
    };
  }, [routeKey, locationState.route]);
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
  const visibleNavigation = navigation.filter(
    ([id]) =>
      (id !== 'content' || session.roles.some((role) => ['EDITOR', 'ADMIN'].includes(role))) &&
      (id !== 'agents' || capabilities.enabled.includes('STUDY_WORKFLOW')),
  );
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
        endSession,
      }}
    >
      <div className={`app-layout ${collapsed ? 'nav-collapsed' : ''}`}>
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
        <aside
          ref={sidebar}
          id="workspace-navigation"
          className={`sidebar ${mobileOpen ? 'open' : ''}`}
          aria-label="主导航"
          role={mobileOpen ? 'dialog' : undefined}
          aria-modal={mobileOpen || undefined}
          onKeyDown={(e) => {
            if (!mobileOpen) return;
            if (e.key === 'Escape') {
              e.preventDefault();
              setMobileOpen(false);
            }
            if (e.key === 'Tab') {
              const controls = Array.from(
                e.currentTarget.querySelectorAll<HTMLElement>(
                  'button:not(:disabled),a[href],select',
                ),
              );
              const first = controls[0],
                last = controls.at(-1);
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
          <div className="brand">
            <span className="brand-mark">{'<T>'}</span>
            <span className="brand-name">TypeForge</span>
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
                onChange={(e) => {
                  choose(e.target.value);
                  setMobileOpen(false);
                  requestAnimationFrame(() =>
                    document.getElementById('main-content')?.focus({ preventScroll: true }),
                  );
                }}
              >
                {workspaces.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.title}
                  </option>
                ))}
              </select>
              <ChevronDown size={14} />
            </div>
            <button
              className="text-button small"
              onClick={() => {
                setMobileOpen(false);
                create();
              }}
            >
              <Plus size={15} />
              新建工作空间
            </button>
          </div>
          <nav>
            {visibleNavigation.map(([id, label, Icon]) => (
              <a
                href={`#${id}`}
                key={id}
                className={locationState.route === id ? 'active' : ''}
                aria-current={locationState.route === id ? 'page' : undefined}
                onClick={() => {
                  menuNavigation.current = mobileOpen;
                  setMobileOpen(false);
                }}
              >
                <Icon size={19} />
                <span className="nav-label">{label}</span>
              </a>
            ))}
          </nav>
          <div className="sidebar-bottom">
            <a
              href="#settings"
              onClick={() => {
                menuNavigation.current = mobileOpen;
                setMobileOpen(false);
              }}
              aria-current={locationState.route === 'settings' ? 'page' : undefined}
              className={locationState.route === 'settings' ? 'active' : ''}
            >
              <Settings2 size={18} />
              <span className="nav-label">偏好与能力</span>
            </a>
            <div className="account">
              <span className="avatar">{session.username.slice(0, 1).toUpperCase()}</span>
              <div>
                <strong>{session.username}</strong>
                <small>
                  {session.roles.includes('EDITOR') || session.roles.includes('ADMIN')
                    ? '课程编辑'
                    : '学习者'}
                </small>
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
        <div className="main-shell" inert={mobileOpen}>
          <header className="topbar">
            <div className="row">
              <button
                className="mobile-only icon-button"
                ref={menuButton}
                aria-label="打开菜单"
                aria-expanded={mobileOpen}
                aria-controls="workspace-navigation"
                onClick={() => setMobileOpen(true)}
              >
                <Menu size={21} />
              </button>
              <button
                className="desktop-only icon-button"
                aria-label={collapsed ? '展开导航' : '收起导航'}
                aria-expanded={!collapsed}
                aria-controls="workspace-navigation"
                onClick={() => setCollapsed(!collapsed)}
              >
                {collapsed ? <PanelLeftOpen size={19} /> : <PanelLeftClose size={19} />}
              </button>
              <span>学习工作台</span>
              <span className="separator">/</span>
              <strong>{selected}</strong>
            </div>
            <div
              className="sync-state"
              role="status"
              title={
                stream.lastSyncedAt
                  ? `最近同步：${stream.lastSyncedAt.toLocaleTimeString('zh-CN')} · 已确认事件 ${stream.cursor}`
                  : '正在连接工作空间'
              }
            >
              <span className={`status-dot ${!stream.online || stream.error ? 'offline' : ''}`} />
              {!stream.online
                ? '离线'
                : stream.error
                  ? '正在重连'
                  : stream.lastSyncedAt
                    ? '已同步'
                    : '正在连接'}
            </div>
          </header>
          <main id="main-content" className="page" tabIndex={-1}>
            {!stream.online && (
              <div className="notice offline-banner" role="status">
                <WifiOff size={18} />
                <div>
                  <strong>暂时离线，可以继续查看和编辑</strong>
                  <p>恢复网络后，你可以重新提交需要服务端确认的操作。</p>
                </div>
              </div>
            )}
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
            ) : locationState.route === 'content' ? (
              <Content />
            ) : locationState.route === 'agents' ? (
              <Agents />
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
