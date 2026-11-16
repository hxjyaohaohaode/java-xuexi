import { useState } from 'react';
import { Activity, Database, RefreshCw, ShieldCheck } from 'lucide-react';
import { ApiError } from '../api';
import { useWorkspace } from '../context';
import { usePolling, useResource } from '../hooks';
import { Badge, Empty, ErrorNotice, Loading, SectionTitle } from '../components/ui';
import './operations.css';

export interface OperationsStatus {
  generatedAt: string;
  requestMetrics: {
    since: string;
    completed: number;
    inFlight: number;
    serverErrors: number;
    rateLimited: number;
    p50UpperBoundMillis: number | null;
    p95UpperBoundMillis: number | null;
    maximumMillis: number;
  };
  queues: {
    pendingEvents: number;
    deadLetters: number;
    pendingTutor: number;
    pendingExports: number;
    pendingCompilation: number;
    pendingEvaluations: number;
    failedEvaluations: number;
    activeImportPreviews: number;
    expiredImportPreviews: number;
    storedEvaluationBytes: number;
    importPreviewBytes: number;
  };
  measurementScope: string;
}

const number = (value: number) => value.toLocaleString('zh-CN');
const latency = (value: number | null) => (value == null ? '暂无样本' : `${number(value)} ms`);
const date = (value: string) => new Date(value).toLocaleString('zh-CN');
export function payloadSize(value: number) {
  if (!Number.isFinite(value) || value < 0) return '—';
  if (value < 1024) return `${number(value)} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / (1024 * 1024)).toFixed(2)} MiB`;
}

export function Operations() {
  const { session, can } = useWorkspace();
  const permitted = session.roles.includes('ADMIN') && can('OPERATIONS_METRICS');
  const [automatic, setAutomatic] = useState(true);
  const status = useResource<OperationsStatus>(permitted ? '/admin/operations' : null);
  const accessLost = status.error instanceof ApiError && [401, 403].includes(status.error.status);
  usePolling(
    permitted && automatic && !accessLost,
    status.reload,
    status.loading,
    !!status.error,
    5000,
  );
  if (!permitted)
    return (
      <Empty title="运行状态仅向管理员开放">请使用已授权的管理账户查看实际队列和进程统计。</Empty>
    );

  // A revoked administrator must not retain the previously fetched privileged snapshot on screen.
  const snapshot = accessLost ? null : status.data;
  const metrics = snapshot?.requestMetrics;
  const queues = snapshot?.queues;
  return (
    <div className="operations-page">
      <SectionTitle
        eyebrow="看见实际等待与占用"
        title="运行状态"
        description="查看当前服务的请求、后台队列与留存数据。这里只展示聚合数量，统计不会包含个人问题、文件正文或答案。"
        action={
          <Badge>
            <ShieldCheck size={14} /> 管理员
          </Badge>
        }
      />
      <div className="operations-toolbar">
        <div className="operations-freshness">
          <span className="muted">
            {snapshot ? `读取时间 ${date(snapshot.generatedAt)}` : '等待第一份服务端快照'}
          </span>
          {status.loading && snapshot && <span className="muted small">正在更新…</span>}
          {!!status.error && snapshot && <Badge tone="warn">上次成功读取的数据</Badge>}
        </div>
        <div className="row wrap">
          <label className="check-row">
            <input
              type="checkbox"
              checked={automatic}
              disabled={accessLost}
              onChange={(e) => setAutomatic(e.target.checked)}
            />
            每 5 秒刷新
          </label>
          <button disabled={status.loading} onClick={status.reload}>
            <RefreshCw size={16} />
            {status.loading ? '正在读取…' : '刷新运行状态'}
          </button>
        </div>
      </div>
      <ErrorNotice error={status.error} retry={status.reload} />
      {accessLost && (
        <Empty title="管理权限已变化">
          服务端已拒绝继续读取，先前的聚合快照已隐藏。请核对当前账户权限。
        </Empty>
      )}
      {status.loading && !snapshot && <Loading label="正在读取实际队列与请求统计…" />}
      {snapshot && metrics && queues && (
        <>
          <div className="summary-strip operations-summary">
            <div>
              <span>已完成 HTTP 请求</span>
              <strong>{number(metrics.completed)}</strong>
            </div>
            <div>
              <span>正在处理的请求</span>
              <strong>{number(metrics.inFlight)}</strong>
            </div>
            <div>
              <span>P95 桶上界</span>
              <strong className="small-value">{latency(metrics.p95UpperBoundMillis)}</strong>
            </div>
            <div>
              <span>服务端错误（5xx）</span>
              <strong>{number(metrics.serverErrors)}</strong>
            </div>
          </div>
          <div className="operations-columns">
            <section className="panel">
              <h2>
                <Activity size={19} /> 后台队列
              </h2>
              <p className="muted small">
                数据库中的当前未终结记录，包含等待领取和正在处理的任务。
              </p>
              <dl className="operations-counts">
                <div>
                  <dt>
                    待发布事件 <small>含已阻塞的死信</small>
                  </dt>
                  <dd>{number(queues.pendingEvents)}</dd>
                </div>
                <div>
                  <dt>导师请求</dt>
                  <dd>{number(queues.pendingTutor)}</dd>
                </div>
                <div>
                  <dt>代码编译</dt>
                  <dd>{number(queues.pendingCompilation)}</dd>
                </div>
                <div>
                  <dt>作品导出</dt>
                  <dd>{number(queues.pendingExports)}</dd>
                </div>
                <div>
                  <dt>检索评测</dt>
                  <dd>{number(queues.pendingEvaluations)}</dd>
                </div>
              </dl>
              <div className="operations-attention">
                <span>阻塞事件</span>
                <Badge tone={queues.deadLetters ? 'warn' : 'neutral'}>
                  {number(queues.deadLetters)}
                </Badge>
                <span>留存的失败评测</span>
                <Badge tone={queues.failedEvaluations ? 'warn' : 'neutral'}>
                  {number(queues.failedEvaluations)}
                </Badge>
              </div>
              {queues.deadLetters > 0 && (
                <p className="muted small">
                  阻塞事件需要维护者核对失败原因；页面刷新不会重试或改变原始学习事实。
                </p>
              )}
            </section>
            <section className="panel">
              <h2>
                <Database size={19} /> 评测与导入留存
              </h2>
              <p className="muted small">按真实保存的正文和 JSON 字节统计，覆盖所有账户。</p>
              <dl className="operations-counts">
                <div>
                  <dt>
                    评测定义与运行证据 <small>包括中间结果及完整报告</small>
                  </dt>
                  <dd>{payloadSize(queues.storedEvaluationBytes)}</dd>
                </div>
                <div>
                  <dt>
                    导入预览正文 <small>原稿与清洗稿</small>
                  </dt>
                  <dd>{payloadSize(queues.importPreviewBytes)}</dd>
                </div>
                <div>
                  <dt>有效待确认预览</dt>
                  <dd>{number(queues.activeImportPreviews)}</dd>
                </div>
                <div>
                  <dt>已过期待清理预览</dt>
                  <dd>{number(queues.expiredImportPreviews)}</dd>
                </div>
              </dl>
              <p className="muted small">
                预览确认、取消或过期后清除暂存正文；已确认的采集收据独立保留。这里的字节数不包含数据库索引、日志和文件页。
              </p>
            </section>
          </div>
          <section className="panel">
            <div className="row spread wrap">
              <h2>请求观测口径</h2>
              <span className="muted small">当前进程开始于 {date(metrics.since)}</span>
            </div>
            <dl className="operations-request-detail">
              <div>
                <dt>P50 桶上界</dt>
                <dd>{latency(metrics.p50UpperBoundMillis)}</dd>
              </div>
              <div>
                <dt>最长已完成请求</dt>
                <dd>{metrics.completed ? latency(metrics.maximumMillis) : '暂无样本'}</dd>
              </div>
              <div>
                <dt>触发限流（429）</dt>
                <dd>{number(metrics.rateLimited)}</dd>
              </div>
            </dl>
            <p className="muted small">
              直方图从本次服务进程启动开始累积，重启后重新计数。P50/P95
              展示固定时间桶的上界；当前请求样本不代表生产压测结果或服务容量承诺。切换到后台或离线时暂停自动刷新。
            </p>
          </section>
        </>
      )}
    </div>
  );
}
