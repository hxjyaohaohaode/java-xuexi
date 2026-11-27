import { lazy, Suspense, useEffect, useRef, useState, useMemo } from 'react';
import { api, ApiError, isAbort } from '../api';
import { useWorkspace } from '../context';
import { useMutation } from '../hooks';
import type {
  Concept,
  CoursewareApproach,
  CoursewareScene,
  PersonalCourseware,
  Evidence,
} from '../types';
import { ErrorNotice, Loading } from './ui';
import { CoursewareStudy } from './CoursewareStudy';

const Player = lazy(() => import('./MaicLessonPlayer'));
const strategies = {
  FOUNDATION: '建立基础',
  EXAMPLE_FIRST: '从例子入手',
  REPAIR: '针对错因复练',
  TRANSFER: '迁移应用',
};
const paces = { GUIDED: '分步引导', STANDARD: '常规节奏', COMPACT: '紧凑练习' };

export function PersonalLesson({
  concept,
  scope,
  onActivity,
  onDiscuss,
  courseEvidence,
  refreshAfterAttempt,
}: {
  concept: Concept;
  scope: string;
  onActivity: (activity: CoursewareScene) => void;
  onDiscuss: (text: string, attemptId?: string) => void;
  courseEvidence: Evidence[];
  refreshAfterAttempt?: string;
}) {
  const { workspace, tick, can } = useWorkspace();
  const endpoint = '/workspaces/' + workspace.id + '/courseware/' + concept.id;
  const [plan, setPlan] = useState<PersonalCourseware | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [retry, reload] = useState(0);
  const [approach, selectApproach] = useState<CoursewareApproach>('AUTO');
  const generation = useMutation();
  const download = useMutation();
  const requestVersion = useRef(0);
  const refreshedAttempt = useRef('');
  const material = useMemo(() => {
    if (!plan) return concept;
    const cards = new Map(
      [...courseEvidence, ...concept.evidence].map((card) => [card.chunkId, card]),
    );
    return {
      ...concept,
      evidence: plan.scenes
        .flatMap((s) => {
          const card = s.chunkId && cards.get(s.chunkId);
          return card && card.releaseId === concept.releaseId && card.revision === s.chunkRevision
            ? [{ ...card, fullText: s.kind === 'READ' ? s.body : card.fullText }]
            : [];
        })
        .filter((card, i, all) => all.findIndex((other) => other.chunkId === card.chunkId) === i),
    };
  }, [concept, plan, courseEvidence]);
  function validate(saved: PersonalCourseware) {
    if (
      saved.workspaceId !== workspace.id ||
      saved.releaseId !== concept.releaseId ||
      saved.conceptId !== concept.id ||
      !saved.scenes.length
    )
      throw Error('课件与当前工作空间或课程不匹配。');
    return saved;
  }
  useEffect(() => {
    const ticket = ++requestVersion.current;
    const control = new AbortController();
    async function load() {
      const latest = await api.get<PersonalCourseware | undefined>(
        endpoint + '?optional=true',
        control.signal,
      );
      const update = refreshAfterAttempt && refreshedAttempt.current !== refreshAfterAttempt;
      const saved =
        latest && !update
          ? latest
          : await api.request<PersonalCourseware>(
              endpoint,
              'POST',
              { approach: latest?.requestedApproach || 'AUTO' },
              { idempotent: true, signal: control.signal },
            );
      if (ticket !== requestVersion.current || control.signal.aborted) return;
      const checked = validate(saved);
      if (update) refreshedAttempt.current = refreshAfterAttempt!;
      setPlan(checked);
      selectApproach(checked.requestedApproach);
      setError(null);
    }
    void load().catch((e) => {
      if (ticket === requestVersion.current && !isAbort(e)) {
        setError(e);
        setPlan(null);
      }
    });
    return () => control.abort();
  }, [endpoint, tick, retry, refreshAfterAttempt]);
  function regenerate() {
    const ticket = ++requestVersion.current;
    void generation.run(
      async () =>
        validate(
          await api.request<PersonalCourseware>(
            endpoint,
            'POST',
            { approach },
            { idempotent: true },
          ),
        ),
      (saved) => {
        if (ticket === requestVersion.current) {
          setPlan(saved);
          setError(null);
        } else reload((n) => n + 1);
      },
    );
  }
  async function exportPersonal() {
    if (!plan) return;
    // Reauthorize the exact frozen version immediately before making bytes available.
    const admitted = validate(
      await api.get<PersonalCourseware>(endpoint + '?planId=' + encodeURIComponent(plan.id)),
    );
    if (admitted.id !== plan.id || admitted.contentHash !== plan.contentHash)
      throw Error('课件版本已发生变化，请重新打开。');
    const { downloadMaicArchive } = await import('../openmaic');
    downloadMaicArchive(material, admitted);
  }
  if (!plan && !error) return <Loading label="正在准备你的个人课件…" />;
  return (
    <section className="personal-lesson" aria-label="我的个性化课件">
      <ErrorNotice error={error} retry={() => reload((n) => n + 1)} />
      <ErrorNotice error={generation.error} />
      {plan ? (
        <>
          <header className="personal-lesson-head">
            <div>
              <p className="classroom-kicker">
                {strategies[plan.strategy]} · {paces[plan.pace]}
              </p>
              <h3>{plan.title}</h3>
            </div>
            <span className="muted small">个人版本 {plan.revision}</span>
          </header>
          <details className="personal-reasons">
            <summary>为什么这样安排</summary>
            <ul>
              {plan.reasons.map((reason, i) => (
                <li key={i}>{reason}</li>
              ))}
            </ul>
          </details>
          {can('STUDY_WORKFLOW') && <CoursewareStudy key={plan.id} plan={plan} scope={scope} />}
          <Suspense fallback={<Loading label="正在打开个人课件…" />}>
            <Player
              key={plan.id}
              concept={material}
              scope={scope}
              personal={plan}
              discussionEnabled={can('TUTOR_EVIDENCE_ONLY')}
              onDiscuss={onDiscuss}
              onActivity={onActivity}
            />
          </Suspense>
        </>
      ) : null}
      {(plan || (error instanceof ApiError && error.status === 409)) && (
        <>
          <div className="personal-plan-controls">
            <label>
              教学方式
              <select
                value={approach}
                onChange={(event) => selectApproach(event.target.value as CoursewareApproach)}
              >
                <option value="AUTO">由学习记录决定</option>
                <option value="EXAMPLE_FIRST">先看示例</option>
                <option value="GUIDED">分步讲解</option>
                <option value="CHALLENGE">挑战迁移</option>
              </select>
            </label>
            <button type="button" disabled={generation.busy} onClick={regenerate}>
              {generation.busy ? '正在更新…' : '按最新学习记录更新课件'}
            </button>
          </div>
        </>
      )}
      {plan && (
        <>
          <details className="personal-export">
            <summary>导出我的个性化课件</summary>
            <p>包含本版为你编排的教学文字、代码、讨论与课程出处。分享前请核对其中的个性化讲解。</p>
            <button
              type="button"
              disabled={download.busy}
              onClick={() => void download.run(exportPersonal)}
            >
              下载我的 OpenMAIC 课件
            </button>
            <ErrorNotice error={download.error} />
          </details>
        </>
      )}
    </section>
  );
}
