import { useEffect, useState } from 'react';
import { ArrowRight, BookMarked, CheckCircle2 } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource } from '../hooks';
import { readSaved, writeSaved } from '../state';
import { Badge, Empty, ErrorNotice, Field, Loading, SectionTitle, Status } from '../components/ui';
import type { Artifact, Attempt, Concept, Exercise, Page } from '../types';

export function Practice() {
  const { workspace, selection, tick, events, session, navigate } = useWorkspace();
  const [conceptId, setConceptId] = useState(selection);
  const [exerciseId, setExerciseId] = useState('');
  const concepts = useResource<{ items: Concept[] }>(`/concepts?releaseId=${workspace.releaseId}`);
  const exercises = useResource<Page<Exercise>>(
    `/exercises?releaseId=${workspace.releaseId}&limit=100${conceptId ? `&conceptId=${conceptId}` : ''}`,
  );
  const [attemptIds, setAttemptIds] = useState<string[]>(() =>
    readSaved(`tf.attempts.${session.userId}.${workspace.id}`, []),
  );
  const persistedAttempts = useResource<Attempt[]>(`/workspaces/${workspace.id}/attempts`, tick);
  useEffect(() => {
    if (persistedAttempts.data)
      setAttemptIds((p) =>
        [
          ...new Set([
            ...p,
            ...persistedAttempts
              .data!.slice()
              .reverse()
              .map((a) => a.id),
          ]),
        ].slice(-100),
      );
  }, [persistedAttempts.data]);
  useEffect(() => {
    setConceptId(selection);
    setExerciseId('');
  }, [selection]);
  useEffect(() => {
    const ids = events
      .map((e) => (typeof e.data.attemptId === 'string' ? e.data.attemptId : ''))
      .filter(Boolean);
    if (ids.length)
      setAttemptIds((previous) => {
        const next = [...new Set([...previous, ...ids])].slice(-40);
        writeSaved(`tf.attempts.${session.userId}.${workspace.id}`, next);
        return next;
      });
  }, [events, session.userId, workspace.id]);
  const selected =
    exercises.data?.items.find((e) => e.id === exerciseId) || exercises.data?.items[0];
  const remember = (a: Attempt) =>
    setAttemptIds((previous) => {
      const next = [...new Set([...previous, a.id])].slice(-40);
      writeSaved(`tf.attempts.${session.userId}.${workspace.id}`, next);
      return next;
    });
  return (
    <>
      <SectionTitle
        eyebrow="预测 · 作答 · 回看证据"
        title="练习与反馈"
        description="先提交自己的判断，再阅读判分依据。同一题目家族的重复作答不会增加独立掌握样本。"
      />
      <div className="practice-layout">
        <aside className="practice-list">
          <Field label="按概念筛选">
            <select
              value={conceptId}
              onChange={(e) => {
                setConceptId(e.target.value);
                setExerciseId('');
              }}
            >
              <option value="">全部概念</option>
              {concepts.data?.items.map((c) => (
                <option value={c.id} key={c.id}>
                  {c.code} · {c.title}
                </option>
              ))}
            </select>
          </Field>
          <ErrorNotice error={exercises.error} retry={exercises.reload} />
          {exercises.loading ? (
            <Loading />
          ) : (
            exercises.data?.items.map((exercise, i) => (
              <button
                className={`exercise-nav ${exercise.id === selected?.id ? 'selected' : ''}`}
                key={exercise.id}
                onClick={() => setExerciseId(exercise.id)}
              >
                <span>{String(i + 1).padStart(2, '0')}</span>
                <div>
                  {exercise.prompt.slice(0, 84)}
                  {exercise.prompt.length > 84 ? '…' : ''}
                  <small>
                    难度 {exercise.difficulty} · 版本 {exercise.revision}
                  </small>
                </div>
              </button>
            ))
          )}
        </aside>
        <section>
          {selected ? (
            <Question key={selected.id} exercise={selected} onAttempt={remember} />
          ) : (
            !exercises.loading && (
              <Empty title="这个范围还没有练习">切换概念或查看全部已发布练习。</Empty>
            )
          )}
        </section>
      </div>
      <section className="recent-attempts">
        <div className="row spread">
          <h2>这个工作空间的近期作答</h2>
          <span className="muted">从真实事件恢复</span>
        </div>
        {attemptIds.length ? (
          <div className="attempt-history">
            {[...attemptIds]
              .reverse()
              .slice(0, 8)
              .map((id) => (
                <AttemptHistory
                  key={id}
                  id={id}
                  tick={tick}
                  onPractice={(id) => {
                    setConceptId('');
                    setExerciseId(id);
                  }}
                />
              ))}
          </div>
        ) : (
          <p className="muted">尚未收到作答记录。完成一题后，反馈与错题集入口会出现在这里。</p>
        )}
        <button className="text-button" onClick={() => navigate('progress')}>
          查看独立证据与复习安排 <ArrowRight size={15} />
        </button>
      </section>
    </>
  );
}
function Question({
  exercise,
  onAttempt,
}: {
  exercise: Exercise;
  onAttempt: (attempt: Attempt) => void;
}) {
  const { workspace, refresh, navigate } = useWorkspace();
  const [options, setOptions] = useState<string[]>([]);
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const mutation = useMutation();
  const notebook = useMutation();
  const submit = (e: React.SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    void mutation.run(
      () =>
        api.request<Attempt>(
          '/attempts',
          'POST',
          {
            workspaceId: workspace.id,
            releaseId: workspace.releaseId,
            exerciseId: exercise.id,
            exerciseRevision: exercise.revision,
            answer: { kind: 'CHOICE', optionIds: options },
          },
          { idempotent: true },
        ),
      (a) => {
        setAttempt(a);
        onAttempt(a);
        refresh();
      },
    );
  };
  const build = () =>
    attempt &&
    notebook.run(
      () =>
        api.request<Artifact>(
          `/workspaces/${workspace.id}/notebook-drafts`,
          'POST',
          { attemptIds: [attempt.id], title: `错题回看 · ${exercise.prompt.slice(0, 35)}` },
          { idempotent: true },
        ),
      (artifact) => {
        refresh();
        navigate('studio', artifact.id);
      },
    );
  return (
    <article className="question-card">
      <div className="row spread">
        <Badge>
          {exercise.kind === 'SINGLE' ? '单选题 · 请选择一项' : '客观选择题 · 按题意选择'}
        </Badge>
        <small className="muted">题目版本 {exercise.revision}</small>
      </div>
      <h2 className="question-prompt">{exercise.prompt}</h2>
      {['SINGLE', 'MULTIPLE', 'COMPILE_PREDICTION', 'READING', 'DIAGNOSIS'].includes(
        exercise.kind,
      ) ? (
        <form onSubmit={submit}>
          <fieldset disabled={mutation.busy} className="answer-options">
            <legend className="sr-only">选择你的答案</legend>
            {exercise.options.map((option) => (
              <label
                className={`answer-option ${options.includes(option.id) ? 'selected' : ''}`}
                key={option.id}
              >
                <input
                  type={exercise.kind === 'SINGLE' ? 'radio' : 'checkbox'}
                  name={`answer-${exercise.id}`}
                  value={option.id}
                  checked={options.includes(option.id)}
                  onChange={(e) => {
                    setOptions((old) =>
                      exercise.kind === 'SINGLE'
                        ? [option.id]
                        : e.target.checked
                          ? [...old, option.id]
                          : old.filter((o) => o !== option.id),
                    );
                    setAttempt(null);
                  }}
                />
                <span className="option-label">{option.id}</span>
                <span>{option.text}</span>
              </label>
            ))}
          </fieldset>
          <ErrorNotice error={mutation.error} />
          <div className="row spread">
            <p className="muted small">答案只由服务端判分。</p>
            <button className="primary" disabled={mutation.busy || options.length === 0}>
              {mutation.busy ? '正在提交…' : '提交作答'}
              <ArrowRight size={16} />
            </button>
          </div>
        </form>
      ) : (
        <Empty title="当前题型尚未接入判分">此版本仅支持客观选择题。</Empty>
      )}
      {attempt && (
        <section
          className={`feedback ${attempt.score === attempt.maximum ? 'correct' : ''}`}
          aria-live="polite"
        >
          <div className="row spread">
            <h3>
              <CheckCircle2 size={20} />
              服务端反馈
            </h3>
            <Status value={attempt.status} />
          </div>
          {attempt.score !== null && (
            <p className="score">
              {attempt.score}
              <span> / {attempt.maximum}</span>
            </p>
          )}
          <p className="preserve-text">{attempt.feedback}</p>
          {attempt.misconceptionCodes.length > 0 && (
            <div className="row wrap">
              {attempt.misconceptionCodes.map((m) => (
                <Badge key={m} tone="warn">
                  {m}
                </Badge>
              ))}
            </div>
          )}
          <p className="muted small">
            {attempt.hintLevel > 0
              ? '这次属于已有作答后的复练，不会新增独立样本。'
              : '首次独立作答已进入学习证据。'}{' '}
            判分版本：{attempt.graderVersion}
          </p>
          {(attempt.score !== attempt.maximum || attempt.hintLevel > 0) && (
            <button onClick={() => void build()} disabled={notebook.busy}>
              <BookMarked size={16} />
              {notebook.busy ? '正在构建…' : '加入可复练错题集'}
            </button>
          )}
          <ErrorNotice error={notebook.error} />
        </section>
      )}
    </article>
  );
}
function AttemptHistory({
  id,
  tick,
  onPractice,
}: {
  id: string;
  tick: number;
  onPractice: (id: string) => void;
}) {
  const { workspace, refresh, navigate } = useWorkspace();
  const attempt = useResource<Attempt>(`/attempts/${id}`, tick);
  const mutation = useMutation();
  const a = attempt.data;
  if (attempt.error) return <ErrorNotice error={attempt.error} retry={attempt.reload} />;
  if (!a) return null;
  if (a.workspaceId !== workspace.id) return null;
  const build = () =>
    mutation.run(
      () =>
        api.request<Artifact>(
          `/workspaces/${workspace.id}/notebook-drafts`,
          'POST',
          { attemptIds: [a.id], title: '泛型错题 · 证据回看' },
          { idempotent: true },
        ),
      (artifact) => {
        refresh();
        navigate('studio', artifact.id);
      },
    );
  return (
    <div className="history-row">
      <div>
        <strong>
          {a.score === a.maximum ? '判断正确' : '需要回看'} · {a.score}/{a.maximum}
        </strong>
        <p className="muted small">
          {new Date(a.createdAt).toLocaleString('zh-CN')} · {a.hintLevel > 0 ? '复练' : '独立作答'}
        </p>
      </div>
      <div className="row wrap">
        <button className="text-button" onClick={() => onPractice(a.exerciseId)}>
          再次练习
        </button>
        {(a.score !== a.maximum || a.hintLevel > 0) && (
          <button onClick={() => void build()} disabled={mutation.busy}>
            构建错题集
          </button>
        )}
      </div>
      <ErrorNotice error={mutation.error} />
    </div>
  );
}
