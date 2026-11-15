import { useState } from 'react';
import { Badge, Empty } from './ui';
import type { ArtifactContent, TextBlock } from '../types';

function Block({ block }: { block: TextBlock }) {
  switch (block.kind) {
    case 'HEADING':
      return <h3>{block.text}</h3>;
    case 'CODE':
      return (
        <pre>
          <code>{block.text}</code>
        </pre>
      );
    case 'QUOTE':
      return <blockquote>{block.text}</blockquote>;
    default:
      return <p className="preserve-text">{block.text}</p>;
  }
}
/** Every source value is rendered as text or fixed React elements. No user HTML, SVG, JS or eval. */
export function SourcePreview({ content }: { content: ArtifactContent }) {
  if (
    content.schemaVersion !== '1.0' ||
    !['DOCUMENT', 'SLIDES', 'DIAGRAM', 'NOTEBOOK', 'INTERACTION'].includes(content.kind)
  )
    return (
      <Empty title="当前源稿类型或版本尚无安全预览适配器">
        文件内容不会被作为任意 HTML 或脚本执行。
      </Empty>
    );
  if (content.kind === 'DOCUMENT')
    return (
      <article className="document-preview">
        <p className="eyebrow">TypeForge · 学习讲义</p>
        <h2>{content.title}</h2>
        {content.blocks.map((block) => (
          <section key={block.id}>
            <Block block={block} />
            {block.evidenceRefs.length > 0 && (
              <small className="citation-count">关联 {block.evidenceRefs.length} 条知识引用</small>
            )}
          </section>
        ))}
      </article>
    );
  if (content.kind === 'SLIDES')
    return (
      <div className="slides-preview">
        {content.slides.map((slide, i) => (
          <section className="slide-preview-wrap" key={slide.id}>
            <div
              className="slide-canvas"
              style={{ aspectRatio: content.aspect === '16:9' ? '16/9' : '4/3' }}
            >
              <h3>{slide.title}</h3>
              {slide.elements.map((element) => (
                <div
                  key={element.id}
                  className={`slide-element ${element.kind === 'CODE' ? 'code' : ''}`}
                  style={{
                    left: `${Math.max(0, Math.min(1, element.x)) * 100}%`,
                    top: `${Math.max(0, Math.min(1, element.y)) * 100}%`,
                    width: `${Math.max(0.01, Math.min(1, element.width)) * 100}%`,
                    height: `${Math.max(0.01, Math.min(1, element.height)) * 100}%`,
                  }}
                >
                  {element.kind === 'CODE' ? (
                    <pre>
                      <code>{element.text}</code>
                    </pre>
                  ) : element.kind === 'IMAGE' && element.assetId ? (
                    <PrivateImage
                      key={element.assetId}
                      id={element.assetId}
                      alt={element.alt || '学习图片'}
                    />
                  ) : element.kind === 'TEXT' ? (
                    <p>{element.text}</p>
                  ) : (
                    <span className="muted">{element.alt || '此媒体元素尚无预览适配器'}</span>
                  )}
                </div>
              ))}
              <span className="slide-index">{String(i + 1).padStart(2, '0')}</span>
            </div>
            <details>
              <summary>查看完整文本与讲解备注</summary>
              {slide.elements
                .filter((element) => element.kind === 'TEXT' || element.kind === 'CODE')
                .map((element) =>
                  element.kind === 'CODE' ? (
                    <pre key={element.id}>
                      <code>{element.text}</code>
                    </pre>
                  ) : (
                    <p className="preserve-text" key={element.id}>
                      {element.text}
                    </p>
                  ),
                )}
              {slide.notes && (
                <>
                  <h4>讲解备注</h4>
                  <p className="preserve-text">{slide.notes}</p>
                </>
              )}
            </details>
          </section>
        ))}
      </div>
    );
  if (content.kind === 'DIAGRAM')
    return (
      <div className="diagram-preview">
        <h3>类型关系</h3>
        <div className="diagram-nodes">
          {content.nodes.map((node) => (
            <div key={node.id}>
              <strong>{node.label}</strong>
              <code>{node.typeExpression}</code>
            </div>
          ))}
        </div>
        <table>
          <thead>
            <tr>
              <th>起点</th>
              <th>关系</th>
              <th>终点</th>
              <th>判断</th>
            </tr>
          </thead>
          <tbody>
            {content.edges.map((edge, i) => (
              <tr key={i}>
                <td>{content.nodes.find((n) => n.id === edge.from)?.label || edge.from}</td>
                <td>{edge.relation}</td>
                <td>{content.nodes.find((n) => n.id === edge.to)?.label || edge.to}</td>
                <td>{edge.verdict}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  return (
    <div className="notebook-preview">
      {content.blocks.map((block) => (
        <section className="notebook-block" key={block.id}>
          <div className="row spread">
            <h3>错题回看</h3>
            <Badge>块版本 {block.revision}</Badge>
          </div>
          <p className="preserve-text">{block.prompt}</p>
          {typeof block.config.studentReason === 'string' && (
            <blockquote>{block.config.studentReason}</blockquote>
          )}
          {typeof block.config.originalSource === 'string' && block.config.originalSource && (
            <pre>
              <code>{block.config.originalSource}</code>
            </pre>
          )}
          <p className="muted small">
            关联 {block.evidenceRefs.length} 条知识证据。练习操作请使用在线错题集。
          </p>
        </section>
      ))}
    </div>
  );
}

function PrivateImage({ id, alt }: { id: string; alt: string }) {
  const [failed, setFailed] = useState(false);
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id) || failed)
    return <span className="muted">图片暂不可用，请检查素材是否仍有效。</span>;
  return (
    <img
      className="slide-image"
      src={`/api/v1/assets/${id}/download`}
      alt={alt}
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}
