import { useEffect, useState } from 'react';
import { KeyRound, Monitor, ShieldCheck, UserRound } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource, useSessionDraft, useUnsavedChanges } from '../hooks';
import { Badge, Confirmed, Dialog, ErrorNotice, Field, Loading } from '../components/ui';

interface Profile {
  userId: string;
  username: string;
  displayName: string;
  roles: string[];
  revision: number;
  createdAt: string;
}
interface LoginSession {
  id: string;
  deviceLabel: string;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
  current: boolean;
}
const passwordValid = (password: string) =>
  password.length >= 12 && new TextEncoder().encode(password).length <= 72;
const date = (value: string) => new Date(value).toLocaleString('zh-CN');

export function AccountPanel() {
  const { session, endSession } = useWorkspace();
  const profile = useResource<Profile>('/me/profile');
  const sessions = useResource<{ items: LoginSession[] }>('/me/sessions');
  const [draft, setDraft] = useSessionDraft<{ displayName: string; revision: number } | null>(
    `tf.profile-draft.${session.userId}`,
    null,
  );
  const [dirty, setDirty] = useSessionDraft(`tf.profile-dirty.${session.userId}`, false);
  const [notice, setNotice] = useState('');
  const [password, setPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [closing, setClosing] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const [closePassword, setClosePassword] = useState('');
  const profileMutation = useMutation();
  const passwordMutation = useMutation();
  const sessionMutation = useMutation();
  const closeMutation = useMutation();
  useUnsavedChanges(dirty || !!password || !!newPassword);
  useEffect(() => {
    if (profile.data && !dirty && (!draft || profile.data.revision > draft.revision))
      setDraft({ displayName: profile.data.displayName, revision: profile.data.revision });
  }, [profile.data, draft, dirty]);
  const leave = (message: string) => {
    if (endSession) endSession(message);
    else api.onUnauthenticated?.();
  };
  return (
    <section className="account-settings" aria-label="账号与安全">
      <div className="row spread">
        <h2>
          <UserRound size={20} />
          账号与安全
        </h2>
        <Badge>仅影响当前账号</Badge>
      </div>
      <div className="two-column">
        <section className="panel">
          <h3>个人资料</h3>
          <ErrorNotice error={profile.error} retry={profile.reload} />
          {!draft ? (
            !profile.error && <Loading />
          ) : (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                void profileMutation.run(
                  () =>
                    api.request<Profile>(
                      '/me/profile',
                      'PUT',
                      {
                        displayName: draft.displayName.trim(),
                        revision: draft.revision,
                      },
                      { headers: { 'If-Match': `"${draft.revision}"` } },
                    ),
                  (value) => {
                    setDraft({ displayName: value.displayName, revision: value.revision });
                    setDirty(false);
                    setNotice('个人资料已保存。');
                    profile.reload();
                  },
                );
              }}
            >
              <Field label="用户名" hint="用于登录与账号关闭确认。">
                <input value={session.username} readOnly />
              </Field>
              <Field label="显示名称" hint="可使用中文，最多 80 个字符。">
                <input
                  value={draft.displayName}
                  maxLength={80}
                  required
                  disabled={profileMutation.busy}
                  onChange={(e) => {
                    setDraft({ ...draft, displayName: e.target.value });
                    setDirty(true);
                    setNotice('');
                  }}
                />
              </Field>
              <button disabled={profileMutation.busy || !dirty || !draft.displayName.trim()}>
                {profileMutation.busy ? '正在保存…' : '保存个人资料'}
              </button>
              <ErrorNotice error={profileMutation.error} retry={profile.reload} />
              {notice && <Confirmed>{notice}</Confirmed>}
              {profile.data && profile.data.revision !== draft.revision && (
                <div className="notice warning">
                  <div>
                    <strong>资料已有新版本</strong>
                    <p>当前输入已保留。最新显示名称：{profile.data.displayName}</p>
                    <button
                      type="button"
                      onClick={() => {
                        setDraft({
                          displayName: profile.data!.displayName,
                          revision: profile.data!.revision,
                        });
                        setDirty(false);
                        profileMutation.clearError();
                      }}
                    >
                      采用最新资料
                    </button>
                    <button
                      type="button"
                      className="text-button"
                      onClick={() => {
                        setDraft({ ...draft, revision: profile.data!.revision });
                        profileMutation.clearError();
                      }}
                    >
                      保留我的名称，以最新版本为基线
                    </button>
                  </div>
                </div>
              )}
            </form>
          )}
        </section>
        <section className="panel">
          <h3>
            <KeyRound size={18} />
            修改密码
          </h3>
          <p className="muted small">修改成功后，所有设备都会退出登录。请用新密码重新登录。</p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (newPassword !== confirmPassword || !passwordValid(newPassword)) return;
              void passwordMutation.run(
                () =>
                  api.request('/me/password', 'PUT', { currentPassword: password, newPassword }),
                () => {
                  setPassword('');
                  setNewPassword('');
                  setConfirmPassword('');
                  leave('密码已修改，所有旧会话已退出。请使用新密码登录。');
                },
              );
            }}
          >
            <fieldset disabled={passwordMutation.busy}>
              <Field label="当前密码">
                <input
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  required
                  maxLength={72}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </Field>
              <Field label="新密码" hint="至少 12 个字符，UTF-8 编码最多 72 字节。">
                <input
                  type="password"
                  autoComplete="new-password"
                  value={newPassword}
                  required
                  minLength={12}
                  maxLength={72}
                  onChange={(e) => setNewPassword(e.target.value)}
                />
              </Field>
              <Field label="再次输入新密码">
                <input
                  type="password"
                  autoComplete="new-password"
                  value={confirmPassword}
                  required
                  maxLength={72}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  aria-invalid={!!confirmPassword && confirmPassword !== newPassword}
                />
              </Field>
              {confirmPassword && confirmPassword !== newPassword && (
                <p className="field-error">两次新密码还不一致。</p>
              )}
              {new TextEncoder().encode(newPassword).length > 72 && (
                <p className="field-error">新密码的 UTF-8 长度超过 72 字节，请缩短后提交。</p>
              )}
              <button
                disabled={
                  !password || !passwordValid(newPassword) || newPassword !== confirmPassword
                }
              >
                {passwordMutation.busy ? '正在修改…' : '修改密码并退出旧会话'}
              </button>
            </fieldset>
            <ErrorNotice error={passwordMutation.error} />
          </form>
        </section>
      </div>
      <section className="panel">
        <div className="row spread wrap">
          <h3>
            <Monitor size={19} />
            登录设备与会话
          </h3>
          <button
            className="text-button"
            disabled={sessionMutation.busy || !sessions.data?.items.some((item) => !item.current)}
            onClick={() =>
              void sessionMutation.run(() => api.request('/me/sessions', 'DELETE'), sessions.reload)
            }
          >
            退出其他所有设备
          </button>
        </div>
        <ErrorNotice error={sessions.error} retry={sessions.reload} />
        <ErrorNotice error={sessionMutation.error} />
        {sessions.loading && !sessions.data ? (
          <Loading />
        ) : (
          sessions.data?.items.map((item) => (
            <article key={item.id} className="session-row">
              <div>
                <div className="row wrap">
                  <strong>{item.deviceLabel || '登录设备'}</strong>
                  {item.current && <Badge tone="good">当前设备</Badge>}
                </div>
                <p className="muted small">
                  登录于 {date(item.createdAt)} · 最近活动 {date(item.lastSeenAt)}
                </p>
                <small className="muted">到期时间 {date(item.expiresAt)}</small>
              </div>
              <button
                disabled={sessionMutation.busy}
                onClick={() =>
                  void sessionMutation.run(
                    () => api.request(`/me/sessions/${item.id}`, 'DELETE'),
                    () => {
                      if (item.current) leave('当前设备已退出登录。');
                      else sessions.reload();
                    },
                  )
                }
              >
                {item.current ? '退出当前设备' : '撤销会话'}
              </button>
            </article>
          ))
        )}
      </section>
      <section className="panel account-closure">
        <div>
          <h3>
            <ShieldCheck size={19} />
            关闭账号
          </h3>
          <p className="muted">
            账号关闭后无法再访问原来的私人工作空间，所有会话和未完成任务会被停用。普通用户名可重新注册为独立新账号；曾具有编辑或管理权限的用户名会保留，不可重新使用。
          </p>
          <p className="muted small">
            历史学习证据与审计会以匿名账户标识保留；关闭账号不代表物理擦除所有历史记录。
          </p>
        </div>
        <button className="danger-text" onClick={() => setClosing(true)}>
          了解并关闭账号
        </button>
      </section>
      {closing && (
        <Dialog
          title="确认关闭账号"
          onClose={() => {
            if (!closeMutation.busy) {
              setClosing(false);
              setClosePassword('');
            }
          }}
        >
          <h2>确认关闭账号</h2>
          <p>此操作会结束当前会话。需要保留的作品请先导出。</p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void closeMutation.run(
                () =>
                  api.request<{ dataRetention: string }>('/me/close-account', 'POST', {
                    currentPassword: closePassword,
                    confirmationUsername: confirmation,
                  }),
                (value) => {
                  setClosePassword('');
                  leave(value.dataRetention);
                },
              );
            }}
          >
            <fieldset disabled={closeMutation.busy}>
              <Field label={`输入用户名 ${session.username} 以确认`}>
                <input
                  autoComplete="off"
                  required
                  value={confirmation}
                  onChange={(e) => setConfirmation(e.target.value)}
                />
              </Field>
              <Field label="验证当前密码">
                <input
                  type="password"
                  autoComplete="current-password"
                  required
                  value={closePassword}
                  maxLength={72}
                  onChange={(e) => setClosePassword(e.target.value)}
                />
              </Field>
              <ErrorNotice error={closeMutation.error} />
              <div className="row wrap">
                <button
                  type="button"
                  onClick={() => {
                    setClosing(false);
                    setClosePassword('');
                  }}
                >
                  保留账号
                </button>
                <button
                  className="danger-button"
                  disabled={confirmation !== session.username || !closePassword}
                >
                  {closeMutation.busy ? '正在关闭…' : '确认关闭账号'}
                </button>
              </div>
            </fieldset>
          </form>
        </Dialog>
      )}
    </section>
  );
}
