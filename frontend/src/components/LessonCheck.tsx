import { useId } from 'react';
import { useResource, useSessionDraft } from '../hooks';
import { Question } from '../pages/Practice';
import type { Attempt, Concept, CoursewareScene, Exercise } from '../types';
import { ErrorNotice, Loading } from './ui';

export function SelfCheck({ check, draftKey }: { check: NonNullable<CoursewareScene['selfCheck']>; draftKey?: string }) {
  const id=useId();
  const [{chosen,confirmed}, setDecision]=useSessionDraft(draftKey || 'tf.selfcheck.' + id, {chosen:-1, confirmed:false});
  return (
    <form
      className="scene-check"
      onSubmit={(event) => {
        event.preventDefault();
        setDecision({chosen,confirmed:true});
      }}
    >
      <fieldset>
        <legend>先试着判断</legend>
        {check.options.map((option, i) => (
          <label key={i}>
            <input
              type="radio"
              name={id}
              checked={chosen === i}
              onChange={() => {
                setDecision({chosen:i,confirmed:false});
              }}
            />
            {option}
          </label>
        ))}
      </fieldset>
      <button disabled={chosen < 0 || chosen >= check.options.length || confirmed}>核对我的判断</button>
      {confirmed && (
        <div role="status" className="check-explanation">
          <strong>{chosen === check.answerIndex ? '判断正确' : '再看这个条件'}</strong>
          <p>{check.explanation}</p>
        </div>
      )}
      <small className="muted">此处用于自测。正式练习提交后会计入学习记录。</small>
    </form>
  );
}

export function InlinePractice({
  concept,
  activity,
  onAttempt,
  onUpdate,
  onDiscuss,
  updating,
}: {
  concept: Concept;
  activity: CoursewareScene;
  onAttempt: (attempt: Attempt) => void;
  onUpdate: () => void;
  updating: boolean;
  onDiscuss?: (question: string, attemptId?: string) => void;
}) {
  const path =
    '/exercises/' +
    encodeURIComponent(activity.exerciseId!) +
    '?releaseId=' +
    encodeURIComponent(concept.releaseId) +
    '&revision=' +
    activity.exerciseRevision;
  const exercise = useResource<Exercise>(path);
  if (exercise.error) return <ErrorNotice error={exercise.error} retry={exercise.reload} />;
  if (!exercise.data) return <Loading label="正在读取本页关联的正式练习…" />;
  if (
    exercise.data.releaseId !== concept.releaseId ||
    exercise.data.id !== activity.exerciseId ||
    exercise.data.revision !== activity.exerciseRevision ||
    !exercise.data.conceptIds.includes(concept.id)
  )
    return <ErrorNotice error={Error('练习版本与本节课件不匹配。')} retry={exercise.reload} />;
  return (
    <Question
      exercise={exercise.data}
      onAttempt={onAttempt}
      onDiscussHint={onDiscuss}
      feedbackActions={(attempt) => (
        <div className="receipt-followup">
          {onDiscuss && (
            <button
              type="button"
              onClick={() =>
                onDiscuss(
                  '关于「' +
                    concept.title +
                    '」这次作答，怎样解释我的判断？请用本题的条件给出一个验证方法。',
                  attempt.id,
                )
              }
            >
              请导师讲解这次作答
            </button>
          )}
          <button type="button" disabled={updating} onClick={onUpdate}>
            {updating ? '正在更新课件…' : '依据这次反馈更新课件'}
          </button>
        </div>
      )}
    />
  );
}
