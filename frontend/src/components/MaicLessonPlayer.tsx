import { useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight, MessageCircle } from 'lucide-react';
import { SlideCanvas } from '@openmaic/renderer';
import { useSessionDraft, useResource } from '../hooks';
import { buildMaicLesson } from '../openmaic';
import type { Concept, CoursewareScene, PersonalCourseware, Attempt } from '../types';
import { CompilePanel } from './Compiler';
import { ErrorNotice, Loading } from './ui';
import './maic-player.css';

export default function MaicLessonPlayer({
  concept,
  scope,
  discussionEnabled,
  onDiscuss,
  personal,
  onActivity,
}: {
  concept: Concept;
  scope: string;
  discussionEnabled: boolean;
  onDiscuss: (question: string, attemptId?: string) => void;
  personal?: PersonalCourseware;
  onActivity?: (activity: CoursewareScene) => void;
}) {
  const lesson = useMemo(() => buildMaicLesson(concept, Date.now(), personal), [concept, personal]);
  const key = 'tf.maic.' + scope + '.' + concept.id + (personal ? '.' + personal.id : '');
  const [editor, setEditor] = useState('');
  const [savedIndex, saveIndex] = useSessionDraft(key + '.page', 0);
  const [drafts, setDrafts] = useSessionDraft<Record<number, string>>(key + '.responses', {});
  const index = Number.isInteger(savedIndex)
    ? Math.max(0, Math.min(lesson.scenes.length - 1, savedIndex))
    : 0;
  const scene = lesson.scenes[index];
  const activity = lesson.activities[index];
  const [feedbackId, showFeedback] = useState('');
  const feedback = useResource<Attempt>(
    feedbackId && activity?.attemptId === feedbackId ? '/attempts/' + feedbackId : null,
  );
  const receipt =
    feedback.data?.workspaceId === personal?.workspaceId &&
    feedback.data?.releaseId === concept.releaseId
      ? feedback.data
      : null;
  const example =
    activity?.exampleId &&
    concept.companion?.examples.find(
      (e) => e.id === activity.exampleId && e.sha256 === activity.exampleSha256,
    );
  const canvas = scene.content.canvas;
  const code = canvas.elements.find((element) => element.type === 'code');
  const topic = scene.actions?.find((action) => action.type === 'discussion')?.topic;
  const text = drafts[index] || '';
  const count = lesson.scenes.length;
  return (
    <section
      className="maic-lesson"
      aria-label={personal ? '我的课件播放器' : 'OpenMAIC 课堂播放器'}
    >
      <div className="maic-controls">
        <label>
          <span className="sr-only">选择课件场景</span>
          <select value={index} onChange={(event) => saveIndex(Number(event.target.value))}>
            {lesson.scenes.map((slide, i) => (
              <option key={slide.order} value={i}>
                {i + 1}. {slide.title}
              </option>
            ))}
          </select>
        </label>
        <div className="row">
          <button
            type="button"
            className="icon-button"
            aria-label="上一页课件"
            disabled={index === 0}
            onClick={() => saveIndex(index - 1)}
          >
            <ChevronLeft size={18} />
          </button>
          <span className="maic-counter" aria-live="polite">
            {index + 1} / {count}
          </span>
          <button
            type="button"
            className="icon-button"
            aria-label="下一页课件"
            disabled={index + 1 === count}
            onClick={() => saveIndex(index + 1)}
          >
            <ChevronRight size={18} />
          </button>
        </div>
      </div>
      <div className="maic-canvas" aria-label={'场景画布 · ' + scene.title}>
        <SlideCanvas
          slide={canvas}
          chrome={false}
          canvasPercentage={100}
          elementIdPrefix={'maic-' + concept.code + '-' + index + '-'}
        />
      </div>
      <details className="maic-transcript" key={scene.order}>
        <summary>{code ? '阅读本页代码与出处' : '阅读本页文字与出处'}</summary>
        {code ? (
          <pre>
            <code>{lesson.transcripts[index]}</code>
          </pre>
        ) : (
          <p>{lesson.transcripts[index]}</p>
        )}
        {canvas.script && <small>{canvas.script}</small>}
      </details>
      {activity && (
        <div className="personal-activity">
          {activity.prompt && <p>{activity.prompt}</p>}
          {activity.attemptId && (
            <>
              <button
                className="text-button"
                onClick={() =>
                  showFeedback(feedbackId === activity.attemptId ? '' : activity.attemptId!)
                }
              >
                {feedbackId === activity.attemptId ? '收起原作答反馈' : '查看这次作答反馈'}
              </button>
              {feedback.loading && <Loading label="正在读取原作答反馈…" />}
              <ErrorNotice error={feedback.error} retry={feedback.reload} />
              {receipt && (
                <div className="personal-feedback">
                  <strong>
                    判分 {receipt.score} / {receipt.maximum}
                  </strong>
                  <p>{receipt.feedback}</p>
                </div>
              )}
            </>
          )}
          {activity.kind === 'PRACTICE' && activity.exerciseId && (
            <button className="primary" onClick={() => onActivity?.(activity)}>
              作答这道练习
            </button>
          )}
          {activity.kind === 'READ' && (
            <button className="text-button" onClick={() => onActivity?.(activity)}>
              打开本节原文
            </button>
          )}
          {activity.kind === 'REFLECT' && (
            <button className="text-button" onClick={() => onActivity?.(activity)}>
              查看本节作答与复习
            </button>
          )}
          {activity.kind === 'EXAMPLE' && example && (
            <>
              <button onClick={() => setEditor(editor === scene.title ? '' : scene.title)}>
                {editor === scene.title ? '收起编辑器' : '在编辑器中验证'}
              </button>
              {editor === scene.title && (
                <CompilePanel initialConceptCode={concept.code} lessonExample={example} />
              )}
            </>
          )}
        </div>
      )}
      {discussionEnabled && (
        <form
          className="maic-discussion"
          onSubmit={(e) => {
            e.preventDefault();
            if (text.trim())
              onDiscuss(
                '课件第 ' +
                  (index + 1) +
                  ' 页 · ' +
                  (topic || scene.title) +
                  '\n我的想法或疑问：' +
                  text.trim(),
                activity?.attemptId || undefined,
              );
          }}
        >
          <label htmlFor="maic-discussion">{topic ? '我的课堂观点' : '就这一页提问'}</label>
          <textarea
            id="maic-discussion"
            rows={2}
            maxLength={1400}
            value={text}
            onChange={(e) => setDrafts({ ...drafts, [index]: e.target.value })}
            placeholder={topic ? '写下你的判断和理由' : '写下你还想弄清楚的问题'}
          />
          <button className="text-button" disabled={!text.trim()}>
            <MessageCircle size={16} />
            带入课堂导师
          </button>
        </form>
      )}
    </section>
  );
}
