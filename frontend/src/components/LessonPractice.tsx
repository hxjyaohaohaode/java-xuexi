import { useEffect, useState } from 'react';
import { useWorkspace } from '../context';
import { useResource } from '../hooks';
import { Question } from '../pages/Practice';
import type { Attempt, Concept, Exercise, Page } from '../types';
import { Empty, ErrorNotice, Field, Loading } from './ui';

export function LessonPractice({
  concept,
  requestedId,
  onAttempt,
}: {
  concept: Concept;
  requestedId?: string;
  onAttempt: (attempt: Attempt) => void;
}) {
  const { workspace } = useWorkspace();
  const base = `/exercises?releaseId=${workspace.releaseId}&conceptId=${concept.id}&limit=100`;
  const [cursor, setCursor] = useState('');
  const [previous, setPrevious] = useState<string[]>([]);
  const [chosenId, setChosen] = useState(requestedId || '');
  const exercises = useResource<Page<Exercise>>(
    base + (cursor ? '&cursor=' + encodeURIComponent(cursor) : ''),
  );
  const items = exercises.data?.items || [];
  const requested = items.find((item) => item.id === chosenId);
  const seeking = !!(exercises.data && requestedId && chosenId === requestedId && !requested);
  const nextPage = exercises.data?.nextCursor;
  const canContinue = !!(nextPage && nextPage !== cursor && !previous.includes(nextPage));
  useEffect(() => {
    if (seeking && nextPage && canContinue) {
      setPrevious((pages) => [...pages, cursor]);
      setCursor(nextPage);
    }
  }, [seeking, nextPage, canContinue, cursor]);
  const selected = requested || items[0];
  if (exercises.error) return <ErrorNotice error={exercises.error} retry={exercises.reload} />;
  if (!exercises.data) return <Loading label="正在读取本节练习…" />;
  if (seeking && canContinue) return <Loading label="正在定位指定练习…" />;
  if (seeking)
    return (
      <Empty title="这道题不在本节已发布的练习中">
        <button
          onClick={() => {
            setChosen('');
            setPrevious([]);
            setCursor('');
          }}
        >
          浏览本节练习
        </button>
      </Empty>
    );
  if (!selected)
    return (
      <Empty title="本节尚无已发布的练习">可以先在实验台检验本节例子，或向课堂导师提问。</Empty>
    );
  return (
    <div className="lesson-practice">
      {items.length > 1 && (
        <Field label="本节练习">
          <select value={selected.id} onChange={(e) => setChosen(e.target.value)}>
            {items.map((item, i) => (
              <option key={item.id} value={item.id}>
                第 {i + 1} 题 · {item.prompt.slice(0, 72)}
              </option>
            ))}
          </select>
        </Field>
      )}
      <Question
        key={selected.id + ':' + selected.revision}
        exercise={selected}
        onAttempt={onAttempt}
      />
      {(previous.length > 0 || exercises.data.nextCursor) && (
        <div className="row">
          <button
            disabled={!previous.length}
            onClick={() => {
              setCursor(previous.at(-1) || '');
              setPrevious(previous.slice(0, -1));
              setChosen('');
            }}
          >
            上一页练习
          </button>
          <button
            disabled={!exercises.data.nextCursor}
            onClick={() => {
              setPrevious([...previous, cursor]);
              setCursor(exercises.data!.nextCursor!);
              setChosen('');
            }}
          >
            下一页练习
          </button>
        </div>
      )}
    </div>
  );
}
