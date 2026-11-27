import {
  AlertCircle,
  ArrowUpRight,
  Check,
  ChevronRight,
  LoaderCircle,
  SearchX,
} from 'lucide-react';
import { useEffect, useRef, type ReactNode } from 'react';
import { ApiError, message, safeSourceUrl } from '../api';
import type { Evidence } from '../types';
import { lessonText } from '../lessonText';

export function ErrorNotice({ error, retry }: { error: unknown; retry?: () => void }) {
  if (!error) return null;
  const conflict =
    error instanceof ApiError &&
    (error.status === 412 ||
      /VERSION_CONFLICT|REVISION_CONFLICT|PRECONDITION_FAILED/.test(error.code));
  const title = conflict
    ? '发现版本冲突，当前输入已保留'
    : error instanceof ApiError && error.code === 'ASSET_QUOTA_EXCEEDED'
      ? '素材空间已满'
      : error instanceof ApiError && error.status === 429
        ? '操作较频繁，请稍后重试'
        : error instanceof ApiError && error.code === 'OFFLINE'
          ? '等待网络恢复'
          : error instanceof ApiError && error.code === 'NETWORK_UNCERTAIN'
            ? '服务端结果尚未确认，内容已保留'
            : '请求尚未完成';
  return (
    <div className={`notice ${conflict ? 'warning' : 'error'}`} role="alert">
      <AlertCircle size={18} />
      <div>
        <strong>{title}</strong>
        <p>{message(error)}</p>
        {error instanceof ApiError && error.retryAfterSeconds && (
          <small>约 {error.retryAfterSeconds} 秒后可重试。</small>
        )}
        {error instanceof ApiError && (
          <details className="error-detail">
            <summary>查看排查信息</summary>
            <code>{error.code}</code>
            {error.traceId && <small> · 排查编号：{error.traceId}</small>}
          </details>
        )}
        {retry && (
          <button className="text-button" onClick={retry}>
            重新读取
          </button>
        )}
      </div>
    </div>
  );
}
export function Loading({ label = '正在读取服务端数据…' }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <LoaderCircle size={18} />
      {label}
    </div>
  );
}
export function Empty({
  title,
  children,
  action,
}: {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <SearchX size={28} />
      <h3>{title}</h3>
      {children && <div className="empty-description">{children}</div>}
      {action}
    </div>
  );
}
export function Badge({
  children,
  tone = 'neutral',
}: {
  children: ReactNode;
  tone?: 'neutral' | 'good' | 'warn' | 'bad';
}) {
  return <span className={`badge ${tone}`}>{children}</span>;
}
export function SectionTitle({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <header className="section-title">
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {action}
    </header>
  );
}
export function Confirmed({ children }: { children: ReactNode }) {
  return (
    <div className="confirmed" role="status">
      <Check size={16} />
      {children}
    </div>
  );
}
export function EvidenceList({ items, compact = false }: { items: Evidence[]; compact?: boolean }) {
  return (
    <div className={`evidence-list ${compact ? 'compact' : ''}`}>
      {items.map((e) => {
        const { body, sourceStatus } = lessonText(e);
        return (
          <article className="evidence-card" key={`${e.chunkId}:${e.revision}`}>
            <div className="row spread">
              <h3>{e.title}</h3>
              <Badge>知识版本 {e.revision}</Badge>
            </div>
            <p className="preserve-text">{body}</p>
            <div className="source-line">
              {safeSourceUrl(e.source.url) ? (
                <a href={safeSourceUrl(e.source.url)} target="_blank" rel="noreferrer">
                  {e.source.title}
                  <ArrowUpRight size={14} />
                </a>
              ) : (
                <span>{e.source.title}</span>
              )}
              <span>{e.sourceSection}</span>
            </div>
            <details>
              <summary>
                查看来源与校验信息 <ChevronRight size={14} />
              </summary>
              <dl className="metadata">
                {sourceStatus && (
                  <>
                    <dt>课程来源</dt>
                    <dd>{sourceStatus}</dd>
                  </>
                )}
                <dt>资料版本</dt>
                <dd>{e.source.edition}</dd>
                <dt>内容摘要</dt>
                <dd>
                  <code>{e.contentHash}</code>
                </dd>
                <dt>使用说明</dt>
                <dd>{e.source.usageNote}</dd>
              </dl>
            </details>
          </article>
        );
      })}
    </div>
  );
}
export const statusLabel: Record<string, string> = {
  QUEUED: '等待执行',
  RUNNING: '执行中',
  PLANNING: '正在规划',
  WAITING_USER: '等待你的输入',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  PARTIAL: '部分完成',
  FAILED: '执行失败',
  CANCELLED: '已取消',
  COMPILE_ERROR: '编译未通过',
  TIMED_OUT: '执行超时',
  RESOURCE_LIMIT: '达到资源限制',
  INFRA_ERROR: '编译服务异常',
  RUNTIME_ERROR: '运行失败',
  PENDING: '待处理',
  READY: '准备就绪',
  WAITING_TOOL: '等待工具',
  REVIEWING: '正在校验',
  SUCCEEDED: '已成功',
  SKIPPED: '已跳过',
  RETRIEVING: '正在检索',
  GENERATING: '正在生成',
  VALIDATING: '正在核验',
  INSUFFICIENT_EVIDENCE: '证据不足',
  GRADED: '已判分',
  DRAFT: '草稿',
  VERIFIED: '已验证',
  REJECTED: '已拒绝',
  STALE: '来源已失效',
  CURRENT: '来源有效',
  SUPERSEDED: '已有新版本',
};
export function Status({ value }: { value: string }) {
  return (
    <Badge
      tone={
        ['COMPLETED', 'SUCCEEDED', 'GRADED', 'VERIFIED'].includes(value)
          ? 'good'
          : [
                'FAILED',
                'REJECTED',
                'STALE',
                'COMPILE_ERROR',
                'TIMED_OUT',
                'RESOURCE_LIMIT',
                'INFRA_ERROR',
                'RUNTIME_ERROR',
              ].includes(value)
            ? 'bad'
            : ['PAUSED', 'PARTIAL', 'INSUFFICIENT_EVIDENCE', 'SUPERSEDED'].includes(value)
              ? 'warn'
              : 'neutral'
      }
    >
      {statusLabel[value] || value}
    </Badge>
  );
}
export function Field({
  label,
  children,
  hint,
}: {
  label: string;
  children: ReactNode;
  hint?: string;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}

/** A focused, keyboard-complete dialog that restores the initiating control. */
let scrollLocks = 0;
let previousOverflow = '';
export function lockBodyScroll() {
  if (scrollLocks === 0) previousOverflow = document.body.style.overflow;
  scrollLocks += 1;
  document.body.style.overflow = 'hidden';
  let released = false;
  return () => {
    if (released) return;
    released = true;
    scrollLocks -= 1;
    if (scrollLocks === 0) document.body.style.overflow = previousOverflow;
  };
}
export function Dialog({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const dialog = ref.current;
    const releaseScroll = lockBodyScroll();
    dialog?.showModal?.();
    if (dialog && !dialog.open) dialog.setAttribute('open', '');
    dialog?.querySelector<HTMLElement>('[autofocus], input, button')?.focus();
    return () => {
      dialog?.close?.();
      releaseScroll();
      previous?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className="native-dialog"
      aria-label={title}
      onCancel={(e) => {
        e.preventDefault();
        close.current();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          close.current();
        }
        if (e.key !== 'Tab') return;
        const focusable = Array.from(
          e.currentTarget.querySelectorAll<HTMLElement>(
            'button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href],[tabindex="0"]',
          ),
        );
        const first = focusable[0],
          last = focusable.at(-1);
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last?.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first?.focus();
        }
      }}
    >
      {children}
    </dialog>
  );
}
