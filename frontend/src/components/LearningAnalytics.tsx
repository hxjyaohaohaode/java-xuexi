import { useRef, useState } from 'react';
import { BarChart3 } from 'lucide-react';
import { useWorkspace } from '../context';
import { useResource } from '../hooks';
import { Badge, Empty, ErrorNotice, Loading } from './ui';

interface Period {
  gradedAttempts: number;
  correctAttempts: number;
  correctRatePercent: number | null;
  activeDays: number;
}
interface Analytics {
  generatedAt: string;
  timezone: string;
  days: number;
  evidenceNote: string;
  totals: {
    workspaces: number;
    gradedAttempts: number;
    correctAttempts: number;
    independentFamilies: number;
    pendingReviews: number;
    artifacts: number;
    completedReadings: number;
    labExperiments: number;
    pendingProjectionEvents: number;
  };
  daily: {
    date: string;
    gradedAttempts: number;
    correctAttempts: number;
    completedReadings: number;
    labExperiments: number;
    activityEvents: number;
  }[];
  comparison: { current: Period; previous: Period; includesPartialToday: boolean };
}
type Metric = 'gradedAttempts' | 'completedReadings' | 'labExperiments';
const labels: Record<Metric, string> = {
  gradedAttempts: '已判分作答',
  completedReadings: '完成阅读',
  labExperiments: '类型实验',
};
const rate = (value: number | null) => (value === null ? '暂无样本' : `${value.toFixed(1)}%`);

export function LearningAnalytics({ version = 0 }: { version?: number }) {
  const { tick } = useWorkspace();
  const [days, setDays] = useState(30);
  const [metric, setMetric] = useState<Metric>('gradedAttempts');
  const [selected, setSelected] = useState(-1);
  const bars = useRef<(HTMLButtonElement | null)[]>([]);
  const resource = useResource<Analytics>(`/me/analytics?days=${days}`, tick + version);
  const data = resource.data;
  const index = data
    ? Math.min(selected < 0 ? data.daily.length - 1 : selected, data.daily.length - 1)
    : -1;
  const day = data?.daily[index];
  const max = Math.max(1, ...(data?.daily.map((item) => item[metric]) || []));
  return (
    <section className="panel learning-analytics" aria-label="学习活动趋势">
      <div className="row spread wrap">
        <h2>
          <BarChart3 size={21} />
          学习活动趋势
        </h2>
        <div className="segments" aria-label="统计时间范围">
          {[7, 30, 90].map((value) => (
            <button
              key={value}
              aria-pressed={days === value}
              className={days === value ? 'selected' : ''}
              onClick={() => {
                setDays(value);
                setSelected(-1);
              }}
            >
              近 {value} 天
            </button>
          ))}
        </div>
      </div>
      <ErrorNotice error={resource.error} retry={resource.reload} />
      {resource.loading && !data ? (
        <Loading label="正在读取实际学习记录…" />
      ) : data ? (
        <>
          <div className="analytics-totals">
            <div>
              <span>累计已判分</span>
              <strong>{data.totals.gradedAttempts}</strong>
            </div>
            <div>
              <span>累计独立题族</span>
              <strong>{data.totals.independentFamilies}</strong>
            </div>
            <div>
              <span>累计阅读记录</span>
              <strong>{data.totals.completedReadings}</strong>
            </div>
            <div>
              <span>已创建作品</span>
              <strong>{data.totals.artifacts}</strong>
            </div>
          </div>
          <div className="row spread wrap chart-toolbar">
            <div className="segments" aria-label="趋势指标">
              {(Object.keys(labels) as Metric[]).map((key) => (
                <button
                  key={key}
                  className={metric === key ? 'selected' : ''}
                  aria-pressed={metric === key}
                  onClick={() => setMetric(key)}
                >
                  {labels[key]}
                </button>
              ))}
            </div>
            <Badge>按 {data.timezone} 自然日</Badge>
          </div>
          {data.daily.some((item) => item[metric] > 0) ? (
            <>
              <div
                className="activity-chart"
                role="group"
                aria-label={`${days} 天${labels[metric]}，方向键选择日期`}
              >
                <span className="chart-maximum" aria-hidden="true">
                  {max}
                </span>
                {data.daily.map((item, i) => (
                  <button
                    type="button"
                    key={item.date}
                    ref={(el) => {
                      bars.current[i] = el;
                    }}
                    className={`activity-bar ${i === index ? 'selected' : ''}`}
                    style={
                      { '--bar-height': `${(item[metric] / max) * 100}%` } as React.CSSProperties
                    }
                    aria-label={`${item.date}，${labels[metric]} ${item[metric]} 次`}
                    aria-pressed={i === index}
                    tabIndex={i === index ? 0 : -1}
                    onClick={() => setSelected(i)}
                    onFocus={() => setSelected(i)}
                    onKeyDown={(e) => {
                      const next =
                        e.key === 'ArrowLeft'
                          ? i - 1
                          : e.key === 'ArrowRight'
                            ? i + 1
                            : e.key === 'Home'
                              ? 0
                              : e.key === 'End'
                                ? data.daily.length - 1
                                : null;
                      if (next !== null) {
                        e.preventDefault();
                        bars.current[Math.max(0, Math.min(next, data.daily.length - 1))]?.focus();
                      }
                    }}
                  >
                    <span aria-hidden="true" className={item[metric] === 0 ? 'zero' : ''} />
                  </button>
                ))}
              </div>
              <div className="chart-axis" aria-hidden="true">
                <span>{data.daily[0]?.date}</span>
                <span>{data.daily.at(-1)?.date}</span>
              </div>
              {day && (
                <p className="chart-selection" role="status">
                  <strong>{day.date}</strong>
                  <span>
                    {labels[metric]} {day[metric]} 次
                  </span>
                  <small>
                    已判分 {day.gradedAttempts} · 正确 {day.correctAttempts} · 阅读{' '}
                    {day.completedReadings} · 实验 {day.labExperiments}
                  </small>
                </p>
              )}
            </>
          ) : (
            <Empty title={`近 ${days} 天还没有${labels[metric]}记录`}>
              完成对应操作后，曲线会根据服务端确认的数据显示。
            </Empty>
          )}
          <div className="period-comparison">
            <div>
              <span>近 {days} 天作答</span>
              <strong>{data.comparison.current.gradedAttempts}</strong>
              <small>
                前 {days} 天：{data.comparison.previous.gradedAttempts}
              </small>
            </div>
            <div>
              <span>本期作答正确率</span>
              <strong>{rate(data.comparison.current.correctRatePercent)}</strong>
              <small>上期：{rate(data.comparison.previous.correctRatePercent)}</small>
            </div>
            <div>
              <span>本期有记录天数</span>
              <strong>{data.comparison.current.activeDays}</strong>
              <small>上期：{data.comparison.previous.activeDays}</small>
            </div>
          </div>
          <p className="muted small">
            {data.evidenceNote}{' '}
            {data.comparison.includesPartialToday &&
              '本期包含尚未结束的今天，有记录天数包含后台任务事件。'}{' '}
            正确率包含复练与受助题，不表示独立掌握度。
          </p>
          {data.totals.pendingProjectionEvents > 0 && (
            <p className="notice" role="status">
              还有 {data.totals.pendingProjectionEvents} 条学习事件正在汇总，概念证据稍后更新。
            </p>
          )}
          <details>
            <summary>查看每日原始计数</summary>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>日期（{data.timezone}）</th>
                    <th>已判分</th>
                    <th>正确</th>
                    <th>阅读</th>
                    <th>实验</th>
                  </tr>
                </thead>
                <tbody>
                  {data.daily.map((item) => (
                    <tr key={item.date}>
                      <td>{item.date}</td>
                      <td>{item.gradedAttempts}</td>
                      <td>{item.correctAttempts}</td>
                      <td>{item.completedReadings}</td>
                      <td>{item.labExperiments}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        </>
      ) : null}
    </section>
  );
}
