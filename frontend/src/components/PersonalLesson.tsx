import { lazy, Suspense, useEffect, useRef, useState, useMemo } from 'react';
import { api, ApiError, isAbort } from '../api';
import { useWorkspace } from '../context';
import { useMutation } from '../hooks';
import type {
  Attempt,
  Concept,
  CoursewareBrief,
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
  onExperiment,
  resumeActivity,
  onDiscuss,
  onAttempt,
  courseEvidence,
  refreshAfterAttempt,
}: {
  concept: Concept;
  scope: string;
  onActivity: (activity: CoursewareScene) => void;
  onExperiment?: (planId: string, sceneKey: string) => void;
  resumeActivity?: string;
  onDiscuss: (text: string, attemptId?: string) => void;
  onAttempt: (attempt: Attempt) => void;
  courseEvidence: Evidence[];
  refreshAfterAttempt?: string;
}) {
  const { workspace, tick, can } = useWorkspace();
  const endpoint = '/workspaces/' + workspace.id + '/courseware/' + concept.id;
  const [plan, setPlan] = useState<PersonalCourseware | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [retry, reload] = useState(0);
  const [editor, setEditor] = useState(false);
  const [draft, setDraft] = useState<(CoursewareBrief & { approach: CoursewareApproach }) | null>(
    null,
  );
  const briefButton = useRef<HTMLButtonElement>(null);
  const interestInput = useRef<HTMLInputElement>(null);
  const generationPending = useRef(false);
  const values = draft ?? {
    interest: plan?.brief?.interest || '',
    requirements: plan?.brief?.requirements || '',
    approach: plan?.requestedApproach || 'AUTO',
  };
  const interestLength = Array.from(values.interest).length;
  const requirementLength = Array.from(values.requirements).length;
  const withinLimits = interestLength <= 160 && requirementLength <= 600;
  useEffect(() => {
    if (editor) interestInput.current?.focus();
  }, [editor]);
  function closeEditor() {
    setEditor(false);
    setDraft(null);
    briefButton.current?.focus();
  }
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
            ? [card]
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
    if (generationPending.current) return;
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
  function regenerate(brief?: CoursewareBrief) {
    if (generationPending.current) return;
    generationPending.current = true;
    ++requestVersion.current;
    const approach = brief ? values.approach : plan?.requestedApproach || 'AUTO';
    void generation
      .run(
        async () =>
          validate(
            await api.request<PersonalCourseware>(
              endpoint,
              'POST',
              brief ? { approach, brief } : { approach },
              { idempotent: true },
            ),
          ),
        (saved) => {
          setPlan(saved);
          setError(null);
          if (brief) closeEditor();
        },
      )
      .finally(() => {
        generationPending.current = false;
      });
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
      {(plan || (error instanceof ApiError && error.status === 409)) && (
        <div className="lesson-settings">
          <div className="lesson-settings-bar">
            <div>
              {plan && (
                <strong>
                  {strategies[plan.strategy]} · {paces[plan.pace]}
                </strong>
              )}
              {plan?.brief && (
                <span className="brief-summary">
                  {plan.brief.interest
                    ? '本节兴趣 · ' + plan.brief.interest
                    : '本节要求 · ' + plan.brief.requirements}
                </span>
              )}
              <small className="muted" role="status">
                {plan ? '个人版本 ' + plan.revision : '本节课件待更新'}
              </small>
            </div>
            <button
              ref={briefButton}
              type="button"
              aria-expanded={editor}
              aria-controls="lesson-brief-editor"
              onClick={() => (editor ? closeEditor() : setEditor(true))}
            >
              {editor ? '收起设置' : '调整兴趣与要求'}
            </button>
          </div>
          {editor && (
            <form
              id="lesson-brief-editor"
              className="lesson-brief"
              onSubmit={(e) => {
                e.preventDefault();
                if (withinLimits)
                  regenerate({
                    interest: values.interest.trim(),
                    requirements: values.requirements.trim(),
                  });
              }}
            >
              <label>
                我的兴趣
                <input
                  ref={interestInput}
                  disabled={generation.busy}
                  placeholder="例如，篮球比赛或独立音乐"
                  value={values.interest}
                  aria-invalid={interestLength > 160}
                  aria-describedby="lesson-interest-help"
                  onChange={(e) => setDraft({ ...values, interest: e.target.value })}
                />
              </label>
              <small id="lesson-interest-help" className="muted">
                用你熟悉的事物来讲这个知识点。{interestLength} / 160
              </small>
              <label>
                本节学习要求
                <textarea
                  rows={3}
                  disabled={generation.busy}
                  placeholder="例如，先用反例解释原因，再给我一道迁移题"
                  value={values.requirements}
                  aria-invalid={requirementLength > 600}
                  aria-describedby="lesson-requirements-help"
                  onChange={(e) => setDraft({ ...values, requirements: e.target.value })}
                />
              </label>
              <small id="lesson-requirements-help" className="muted">
                仅用于这节个人课件。{requirementLength} / 600
              </small>
              <label>
                教学方式
                <select
                  disabled={generation.busy}
                  value={values.approach}
                  onChange={(e) =>
                    setDraft({ ...values, approach: e.target.value as CoursewareApproach })
                  }
                >
                  <option value="AUTO">由学习记录决定</option>
                  <option value="EXAMPLE_FIRST">先看示例</option>
                  <option value="GUIDED">分步讲解</option>
                  <option value="CHALLENGE">挑战迁移</option>
                </select>
              </label>
              <div className="row">
                <button className="primary" disabled={!withinLimits || generation.busy}>
                  {generation.busy ? '正在更新课件…' : '按我的要求生成课件'}
                </button>
                {plan?.brief && (
                  <button
                    type="button"
                    disabled={generation.busy}
                    onClick={() => regenerate({ interest: '', requirements: '' })}
                  >
                    清除本节兴趣与要求
                  </button>
                )}
              </div>
            </form>
          )}
        </div>
      )}
      {plan && (
        <>
          <Suspense fallback={<Loading label="正在打开个人课件…" />}>
            <Player
              key={plan.id}
              concept={material}
              scope={scope}
              personal={plan}
              discussionEnabled={can('TUTOR_EVIDENCE_ONLY')}
              onDiscuss={onDiscuss}
              onExperiment={onExperiment}
              resumeActivity={resumeActivity}
              onActivity={onActivity}
              onAttempt={onAttempt}
              onUpdate={() => regenerate()}
              updating={generation.busy}
            />
          </Suspense>
          <div className="lesson-plan-footer">
            <details className="personal-reasons">
              <summary>为什么这样安排</summary>
              <ul>
                {plan.reasons.map((reason, i) => (
                  <li key={i}>{reason}</li>
                ))}
              </ul>
            </details>
            <button type="button" disabled={generation.busy} onClick={() => regenerate()}>
              {generation.busy ? '正在更新…' : '按最新学习记录更新课件'}
            </button>
          </div>
          {can('STUDY_WORKFLOW') && <CoursewareStudy key={plan.id} plan={plan} scope={scope} />}
        </>
      )}
      {plan && (
        <>
          <details className="personal-export">
            <summary>导出我的个性化课件</summary>
            <p>
              下载保留本节的编排、课程出处和可分享的教学情境。私人兴趣、要求和作答记录留在工作空间。
            </p>
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
