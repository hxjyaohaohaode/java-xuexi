import { useEffect, useState } from 'react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, usePolling, useResource, useSessionDraft } from '../hooks';
import type { Artifact, ArtifactVersion, PersonalCourseware, Run } from '../types';
import { ErrorNotice, Loading, Status } from './ui';
import { SourcePreview } from './SourcePreview';

type StudyMode = 'MODEL' | 'EVIDENCE_ONLY';
type Details = { artifactIds: string[]; mode: StudyMode; limitations: string[] };

export function CoursewareStudy({ plan, scope }: { plan: PersonalCourseware; scope: string }) {
  const { can, navigate, tick } = useWorkspace();
  const [mode, setMode] = useSessionDraft<StudyMode>(
    'tf.personal-study-mode.' + scope + '.' + plan.id,
    can('LLM_PLANNING') ? 'MODEL' : 'EVIDENCE_ONLY',
  );
  const [started, setStarted] = useState<{ id: string; mode: StudyMode } | null>(null);
  const endpoint = '/workspaces/' + plan.workspaceId + '/courseware/' + plan.conceptId + '/study';
  const restored = useResource<Run | undefined>(
    endpoint + '?planId=' + plan.id + '&mode=' + mode,
    tick,
  );
  const restoredId = restored.data?.workspaceId === plan.workspaceId ? restored.data.id : null;
  const runId = started?.mode === mode ? started.id : restoredId;
  useEffect(() => {
    if (started?.id === restoredId) setStarted(null);
  }, [started, restoredId]);
  const [poll, setPoll] = useState(0);
  const [index, setIndex] = useState(0);
  const write = useMutation();
  const run = useResource<Run>(runId ? '/agent-runs/' + runId : null, tick + poll);
  const current = run.data?.workspaceId === plan.workspaceId ? run.data : null;
  const details = useResource<Details>(
    current ? '/agent-runs/' + current.id + '/study' : null,
    tick + poll,
  );
  const finished =
    !!current && ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(current.status);
  usePolling(
    !!runId && !finished,
    () => setPoll((n) => n + 1),
    run.loading || details.loading,
    !!run.error || !!details.error,
    2000,
  );
  const artifacts = useResource<Artifact[]>(
    details.data?.artifactIds.length ? '/workspaces/' + plan.workspaceId + '/artifacts' : null,
    tick + poll,
  );
  const slides = artifacts.data?.find(
    (item) =>
      item.workspaceId === plan.workspaceId &&
      item.kind === 'SLIDES' &&
      details.data?.artifactIds.includes(item.id),
  );
  const artifact = useResource<ArtifactVersion>(
    slides ? '/artifacts/' + slides.id + '/revisions?revision=' + slides.currentRevision : null,
  );
  const content = artifact.data?.content;
  const preview = content?.kind === 'SLIDES' ? content : undefined;
  const page = preview && Math.min(index, preview.slides.length - 1);
  function create() {
    void write.run(
      () => api.request<Run>(endpoint, 'POST', { planId: plan.id, mode }, { idempotent: true }),
      (result) => {
        if (result.workspaceId === plan.workspaceId) {
          setStarted({ id: result.id, mode });
          restored.reload();
          setIndex(0);
        }
      },
    );
  }
  return (
    <section className="courseware-study" aria-label="个人讲解课件">
      <h4>讲解课件</h4>
      <p className="small muted">依据这次个人课件的分镜与来源编写讲解，保存在本空间的作品中。</p>
      <div className="personal-plan-controls">
        <label>
          生成方式
          <select
            value={mode}
            onChange={(e) => {
              setIndex(0);
              setMode(e.target.value as StudyMode);
            }}
          >
            <option disabled={!can('LLM_PLANNING')} value="MODEL">
              模型生成{can('LLM_PLANNING') ? '' : ' · 未配置'}
            </option>
            <option value="EVIDENCE_ONLY">课程证据整理</option>
          </select>
        </label>
        {(!runId || (current && ['FAILED', 'CANCELLED', 'PARTIAL'].includes(current.status))) && (
          <button
            className="primary"
            onClick={create}
            disabled={
              write.busy ||
              restored.loading ||
              !!restored.error ||
              (mode === 'MODEL' && !can('LLM_PLANNING'))
            }
          >
            {runId ? '重新生成讲解课件' : '生成讲解课件'}
          </button>
        )}
      </div>
      {runId && (
        <div className="row wrap">
          <span>
            {(details.data?.mode || mode) === 'MODEL' ? '模型讲解任务' : '课程证据整理任务'}
          </span>
          {current && <Status value={current.status} />}
          {!current && !run.error && <Loading label="正在读取讲解任务…" />}
        </div>
      )}
      <ErrorNotice error={write.error} />
      <ErrorNotice error={restored.error} retry={restored.reload} />
      <ErrorNotice error={run.error} retry={run.reload} />
      <ErrorNotice error={details.error} retry={details.reload} />
      <ErrorNotice error={artifacts.error} retry={artifacts.reload} />
      <ErrorNotice error={artifact.error} retry={artifact.reload} />
      {details.data?.limitations?.length ? (
        <details>
          <summary>本次任务说明</summary>
          <ul>
            {details.data.limitations.map((note, i) => (
              <li key={i}>{note}</li>
            ))}
          </ul>
        </details>
      ) : null}
      {preview && slides && page !== undefined && page >= 0 && (
        <section aria-label="已生成讲解幻灯片">
          <div className="row wrap">
            <button onClick={() => setIndex(page - 1)} disabled={!page}>
              上一张讲解
            </button>
            <span aria-live="polite">
              第 {page + 1} / {preview.slides.length} 张
            </span>
            <button
              onClick={() => setIndex(page + 1)}
              disabled={page + 1 === preview.slides.length}
            >
              下一张讲解
            </button>
            <button className="text-button" onClick={() => navigate('studio', slides.id)}>
              进入幻灯片编辑器
            </button>
          </div>
          <SourcePreview content={{ ...preview, slides: [preview.slides[page]] }} />
          <details>
            <summary>阅读这张讲解的文字</summary>
            {preview.slides[page].elements.map((e) => (
              <p key={e.id} className="preserve-text">
                {e.text || e.alt}
              </p>
            ))}
            {preview.slides[page].notes && <p>{preview.slides[page].notes}</p>}
          </details>
        </section>
      )}
    </section>
  );
}
