import { useMemo } from 'react';
import { ChevronLeft, ChevronRight, MessageCircle } from 'lucide-react';
import { SlideCanvas } from '@openmaic/renderer';
import { useSessionDraft } from '../hooks';
import { buildMaicLesson } from '../openmaic';
import type { Concept } from '../types';
import './maic-player.css';

export default function MaicLessonPlayer({
  concept,
  scope,
  discussionEnabled,
  onDiscuss,
}: {
  concept: Concept;
  scope: string;
  discussionEnabled: boolean;
  onDiscuss: (question: string) => void;
}) {
  const lesson = useMemo(() => buildMaicLesson(concept), [concept]);
  const key = 'tf.maic.' + scope + '.' + concept.id;
  const [savedIndex, saveIndex] = useSessionDraft(key + '.page', 0);
  const [drafts, setDrafts] = useSessionDraft<Record<number, string>>(key + '.responses', {});
  const index = Number.isInteger(savedIndex)
    ? Math.max(0, Math.min(lesson.scenes.length - 1, savedIndex))
    : 0;
  const scene = lesson.scenes[index];
  const canvas = scene.content.canvas;
  const code = canvas.elements.find((element) => element.type === 'code');
  const topic = scene.actions?.find((action) => action.type === 'discussion')?.topic;
  const text = drafts[index] || '';
  const count = lesson.scenes.length;
  return (
    <section className="maic-lesson" aria-label="OpenMAIC 课堂播放器">
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
