import { useState } from 'react';
import { useResource } from '../hooks';
import type { CompileJob } from '../types';
import { ErrorNotice, Field, Loading, Status } from './ui';

type Review = {
  jobId: string;
  sourceHash: string;
  phase: string;
  observation: string;
  investigate: string;
  nextExperiment: string;
  comparison: {
    jobId: string;
    status: string;
    sameSource: boolean;
    visibleOutputChanged: boolean;
    previousDiagnostics: number;
    currentDiagnostics: number;
  } | null;
};

export function RunDebrief({
  job,
  history,
  onDiscuss,
}: {
  job: CompileJob;
  history: CompileJob[];
  onDiscuss?: (text: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [previous, setPrevious] = useState('');
  const review = useResource<Review>(
    open ? `/compile-jobs/${job.id}/debrief${previous ? '?previousJobId=' + previous : ''}` : null,
  );
  const candidates = history.filter(
    (row) =>
      row.id !== job.id &&
      !!row.finishedAt &&
      row.workspaceId === job.workspaceId &&
      row.releaseId === job.releaseId &&
      row.language === job.language &&
      row.mode === job.mode,
  );
  const detail = review.data;
  const comparison = detail?.comparison;
  return (
    <details onToggle={(e) => setOpen(e.currentTarget.open)} className="run-debrief">
      <summary>复盘这次代码运行</summary>
      {open && (
        <>
          {candidates.length > 0 && (
            <Field label="对照另一次运行">
              <select value={previous} onChange={(e) => setPrevious(e.target.value)}>
                <option value="">只看本次</option>
                {candidates.map((row) => (
                  <option key={row.id} value={row.id}>
                    {new Date(row.createdAt).toLocaleString('zh-CN')} · {row.sourceHash.slice(0, 8)}
                  </option>
                ))}
              </select>
            </Field>
          )}
          {review.loading && !detail && <Loading label="正在核对运行回执…" />}
          <ErrorNotice error={review.error} retry={review.reload} />
          {detail && (
            <div aria-live="polite">
              <p>{detail.observation}</p>
              <p>{detail.investigate}</p>
              <p>
                <strong>下一次实验</strong> {detail.nextExperiment}
              </p>
              {comparison && (
                <div className="run-comparison">
                  <span>
                    对照任务 <Status value={comparison.status} />
                  </span>
                  <span>
                    本次 <Status value={job.status} />
                  </span>
                  <p>
                    源码{comparison.sameSource ? '相同' : '已改变'}，可见输出
                    {comparison.visibleOutputChanged ? '不同' : '相同'}。公开诊断由
                    {comparison.previousDiagnostics} 条变为 {comparison.currentDiagnostics} 条。
                  </p>
                </div>
              )}
              {onDiscuss && (
                <button
                  type="button"
                  onClick={() =>
                    onDiscuss(
                      [
                        `我想复盘这次 ${job.language === 'PYTHON' ? 'Python' : 'Java'} 实验（源码摘要 ${detail.sourceHash.slice(0, 12)}）。`,
                        detail.observation,
                        detail.investigate,
                        detail.nextExperiment,
                        '请结合本节知识解释原因，并帮我设计只改变一个条件的验证步骤。',
                      ].join('\n'),
                    )
                  }
                >
                  带着这次结果问导师
                </button>
              )}
            </div>
          )}
        </>
      )}
    </details>
  );
}
