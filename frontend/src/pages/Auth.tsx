import { useState } from 'react';
import { ArrowRight, Braces, ShieldCheck } from 'lucide-react';
import { api } from '../api';
import { useMutation } from '../hooks';
import { ErrorNotice, Field } from '../components/ui';
import type { Session } from '../types';

export function Auth({ onSession }: { onSession: (session: Session) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const mutation = useMutation();
  async function submit(e: React.SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    await mutation.run(
      async () => {
        if (mode === 'register')
          await api.request(
            '/auth/register',
            'POST',
            { username, password },
            { authExpected: false },
          );
        const session = await api.request<Session>(
          '/auth/session',
          'POST',
          { username, password },
          { authExpected: false },
        );
        api.resetCsrf();
        await api.warmCsrf();
        return session;
      },
      (session) => {
        setPassword('');
        onSession(session);
      },
    );
  }
  return (
    <div className="auth-layout">
      <section className="auth-story">
        <div className="brand">
          <span className="brand-mark">{'<T>'}</span>TypeForge
        </div>
        <div>
          <p className="eyebrow">Java · 泛型与类型安全</p>
          <h1>
            让每一次理解，
            <br />
            都有证据。
          </h1>
          <p className="auth-lead">
            从一个类型问题开始，读清规则、验证判断，
            <br className="desktop-only" />
            再把自己的理解写成可以复习的作品。
          </p>
          <div className="auth-code" aria-label="Java 泛型示例">
            <div className="code-caption">
              <Braces size={16} />
              <span>从读取与写入的边界开始</span>
            </div>
            <pre>
              <code>
                <span className="code-keyword">List</span>
                {'<? extends Number> source;\n'}
                <span className="code-keyword">List</span>
                {'<? super Integer> target;\n\n'}
                <span className="code-comment">{'// 你能读出什么？又能写入什么？'}</span>
              </code>
            </pre>
            <span className="code-foot">先预测，再追溯规则。</span>
          </div>
        </div>
        <p className="quiet">阅读 · 练习 · 类型实验 · 证据 · 创作</p>
      </section>
      <main className="auth-form">
        <div>
          <h2>{mode === 'login' ? '回到你的工作台' : '创建学习账号'}</h2>
          <p className="muted">你的作答、笔记和作品属于同一个学习空间。</p>
          <div className="segments" aria-label="认证方式">
            <button
              type="button"
              className={mode === 'login' ? 'selected' : ''}
              onClick={() => {
                setMode('login');
                mutation.clearError();
              }}
            >
              登录
            </button>
            <button
              type="button"
              className={mode === 'register' ? 'selected' : ''}
              onClick={() => {
                setMode('register');
                mutation.clearError();
              }}
            >
              注册
            </button>
          </div>
          <form onSubmit={submit}>
            <Field label="用户名" hint="3–64 位小写字母、数字、点、短横线或下划线">
              <input
                required
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                pattern="[a-z0-9][a-z0-9_.-]{2,63}"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                disabled={mutation.busy}
              />
            </Field>
            <Field
              label="密码"
              hint={mode === 'register' ? '至少 12 个字符；UTF-8 编码最多 72 字节。' : undefined}
            >
              <input
                required
                type="password"
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                minLength={mode === 'register' ? 12 : 1}
                maxLength={72}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={mutation.busy}
              />
            </Field>
            <ErrorNotice error={mutation.error} />
            <button
              className="primary wide"
              disabled={mutation.busy || new TextEncoder().encode(password).length > 72}
            >
              {mutation.busy ? '正在建立会话…' : mode === 'login' ? '进入工作台' : '注册并进入'}
              <ArrowRight size={17} />
            </button>
          </form>
          <p className="auth-note">
            <ShieldCheck size={16} />
            使用服务端会话。密码不会保存到浏览器存储。
          </p>
        </div>
      </main>
    </div>
  );
}
