import { useState } from 'react';
import type { Concept, CourseCompanion } from '../types';
import { CompilePanel } from './Compiler';
import './course-examples.css';

export function CourseExamples({ concept }: { concept: Concept }) {
  return concept.companion ? (
    <Companion key={concept.id} course={concept.companion} conceptCode={concept.code} />
  ) : null;
}

function Companion({ course, conceptCode }: { course: CourseCompanion; conceptCode: string }) {
  const [selectedId, select] = useState(course.examples[0]?.id);
  const [editing, setEditing] = useState(false);
  const example = course.examples.find((item) => item.id === selectedId);
  return (
    <section className="course-examples" aria-label="配套课程实验">
      <h3>配套课程实验</h3>
      {example && (
        <>
          {course.examples.length > 1 ? (
            <select
              aria-label="选择课程样例"
              value={selectedId}
              onChange={(e) => select(e.target.value)}
            >
              {course.examples.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.title}
                </option>
              ))}
            </select>
          ) : (
            <h4>{example.title}</h4>
          )}
          <pre className="course-source" tabIndex={0} aria-label="课程 Java 源码">
            <code>{example.code.trimEnd()}</code>
          </pre>
          <div className="row wrap course-example-actions">
            <button type="button" aria-expanded={editing} onClick={() => setEditing(!editing)}>
              {editing ? '收起编辑器' : '动手改这段代码'}
            </button>
            <a href={example.sourceUrl} target="_blank" rel="noreferrer">
              {example.section}
            </a>
          </div>
          {editing && <CompilePanel initialConceptCode={conceptCode} lessonExample={example} />}
          <details key={example.id}>
            <summary>预测之后，查看课程参考结果</summary>
            <p>{example.compiles ? 'Java 17 编译通过。' : 'Java 17 在编译阶段拒绝这段代码。'}</p>
            {example.warning && (
              <p>
                编译器警告 <code>{example.warning}</code>
              </p>
            )}
            {example.stdout !== null && <pre aria-label="参考标准输出">{example.stdout}</pre>}
          </details>
        </>
      )}
      {course.reviews.map((review) => (
        <details key={review.id}>
          <summary>{review.question}</summary>
          <p>{review.criterion}</p>
          {review.correction && (
            <p>
              <strong>课程组对模型回答的订正</strong>　{review.correction}
            </p>
          )}
        </details>
      ))}
      <a
        className="course-edition"
        href="https://github.com/wioo111/java-xuexi/releases/tag/typeforge-course-20261118"
        target="_blank"
        rel="noreferrer"
      >
        课程组原始材料 · 2026.11.18
      </a>
    </section>
  );
}
