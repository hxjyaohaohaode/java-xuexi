import {
  AlertCircle,
  ArrowUpRight,
  Check,
  ChevronRight,
  LoaderCircle,
  SearchX,
} from 'lucide-react';
import type { ReactNode } from 'react';
import { ApiError, message, safeSourceUrl } from '../api';
import type { Evidence } from '../types';

export function ErrorNotice({ error, retry }: { error: unknown; retry?: () => void }) {
  if (!error) return null;
  const conflict = error instanceof ApiError && [409, 412].includes(error.status);
  return (
    <div className={`notice ${conflict ? 'warning' : 'error'}`} role="alert">
      <AlertCircle size={18} />
      <div>
        <strong>{conflict ? '发现版本冲突，当前输入已保留' : '请求尚未完成'}</strong>
        <p>{message(error)}</p>
        {error instanceof ApiError && error.traceId && <small>排查编号：{error.traceId}</small>}
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
      {children && <p>{children}</p>}
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
      {items.map((e) => (
        <article className="evidence-card" key={`${e.chunkId}:${e.revision}`}>
          <div className="row spread">
            <h3>{e.title}</h3>
            <Badge>知识版本 {e.revision}</Badge>
          </div>
          <p className="preserve-text">{e.excerpt}</p>
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
      ))}
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
  PENDING: '尚未开始',
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
          : ['FAILED', 'REJECTED', 'STALE'].includes(value)
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
