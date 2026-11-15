import { useRef, useState } from 'react';
import { ArrowDown, ArrowUp, Copy, Grip, Plus, Trash2 } from 'lucide-react';
import { Field } from './ui';
import type { ArtifactContent, Slide, SlideElement } from '../types';

type Slides = Extract<ArtifactContent, { kind: 'SLIDES' }>;
const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));
export function SlideEditor({
  content,
  onChange,
}: {
  content: Slides;
  onChange: (content: ArtifactContent) => void;
}) {
  const [selected, setSelected] = useState(content.slides[0]?.id || '');
  const [elementId, setElementId] = useState('');
  const slide = content.slides.find((item) => item.id === selected) || content.slides[0];
  const index = content.slides.indexOf(slide);
  const element = slide.elements.find((item) => item.id === elementId) || slide.elements[0];
  const changeSlide = (patch: Partial<Slide>) =>
    onChange({
      ...content,
      slides: content.slides.map((item) => (item.id === slide.id ? { ...item, ...patch } : item)),
    });
  const changeElement = (id: string, patch: Partial<SlideElement>) =>
    changeSlide({
      elements: slide.elements.map((item) => (item.id === id ? { ...item, ...patch } : item)),
    });
  const move = (offset: number) => {
    const slides = [...content.slides];
    [slides[index], slides[index + offset]] = [slides[index + offset], slides[index]];
    onChange({ ...content, slides });
  };
  const add = () => {
    const next: Slide = {
      id: crypto.randomUUID(),
      title: '新的观点',
      elements: [
        {
          id: crypto.randomUUID(),
          kind: 'TEXT',
          x: 0.08,
          y: 0.28,
          width: 0.84,
          height: 0.52,
          text: '',
          assetId: null,
          alt: '',
        },
      ],
      notes: '',
      evidenceRefs: [],
    };
    onChange({ ...content, slides: [...content.slides, next] });
    setSelected(next.id);
  };
  return (
    <div className="slide-editor">
      <div className="slide-filmstrip" aria-label="幻灯片页面">
        {content.slides.map((item, position) => (
          <button
            key={item.id}
            aria-pressed={item.id === slide.id}
            className={item.id === slide.id ? 'selected' : ''}
            onClick={() => {
              setSelected(item.id);
              setElementId('');
            }}
          >
            <span>{String(position + 1).padStart(2, '0')}</span>
            <strong>{item.title || '未命名页面'}</strong>
          </button>
        ))}
        <button disabled={content.slides.length >= 80} onClick={add}>
          <Plus size={17} />
          添加一页
        </button>
      </div>
      <div className="row spread wrap slide-page-heading">
        <h3>第 {index + 1} 页</h3>
        <div className="row">
          <button
            className="icon-button"
            aria-label={`前移第 ${index + 1} 页`}
            disabled={index === 0}
            onClick={() => move(-1)}
          >
            <ArrowUp size={16} />
          </button>
          <button
            className="icon-button"
            aria-label={`后移第 ${index + 1} 页`}
            disabled={index === content.slides.length - 1}
            onClick={() => move(1)}
          >
            <ArrowDown size={16} />
          </button>
          <button
            className="icon-button"
            aria-label={`复制第 ${index + 1} 页`}
            disabled={content.slides.length >= 80}
            onClick={() => {
              const copy = {
                ...slide,
                id: crypto.randomUUID(),
                elements: slide.elements.map((item) => ({ ...item, id: crypto.randomUUID() })),
              };
              const slides = [...content.slides];
              slides.splice(index + 1, 0, copy);
              onChange({ ...content, slides });
              setSelected(copy.id);
            }}
          >
            <Copy size={16} />
          </button>
          <button
            className="icon-button"
            aria-label={`删除第 ${index + 1} 页`}
            disabled={content.slides.length <= 1}
            onClick={() =>
              onChange({
                ...content,
                slides: content.slides.filter((item) => item.id !== slide.id),
              })
            }
          >
            <Trash2 size={16} />
          </button>
        </div>
      </div>
      <Field label={`第 ${index + 1} 页标题`}>
        <input
          maxLength={255}
          value={slide.title}
          onChange={(event) => changeSlide({ title: event.target.value })}
        />
      </Field>
      <div className="slide-designer" aria-label="幻灯片布局画布">
        <div
          className="slide-canvas editable"
          style={{ aspectRatio: content.aspect === '16:9' ? '16/9' : '4/3' }}
        >
          <h3>{slide.title}</h3>
          {slide.elements.map((item) => (
            <CanvasElement
              key={item.id}
              element={item}
              selected={item.id === element?.id}
              select={() => setElementId(item.id)}
              change={(patch) => changeElement(item.id, patch)}
            />
          ))}
        </div>
        <p className="muted small">
          拖动内容可调整位置，拖动右下角可缩放；也可用下面的滑块。选中内容后，方向键微调，Shift +
          方向键加快移动。
        </p>
      </div>
      <div className="slide-properties">
        <div className="row spread wrap">
          <Field label="当前元素">
            <select
              value={element?.id || ''}
              onChange={(event) => setElementId(event.target.value)}
            >
              {!slide.elements.length && <option value="">还没有内容元素</option>}
              {slide.elements.map((item, i) => (
                <option key={item.id} value={item.id}>
                  {i + 1} ·{' '}
                  {item.kind === 'IMAGE' ? '图片' : item.kind === 'CODE' ? '代码' : '文字'}
                </option>
              ))}
            </select>
          </Field>
          <div className="row wrap">
            <button
              disabled={slide.elements.length >= 100}
              onClick={() => {
                const next: SlideElement = {
                  id: crypto.randomUUID(),
                  kind: 'TEXT',
                  x: 0.08,
                  y: 0.68,
                  width: 0.84,
                  height: 0.2,
                  text: '',
                  assetId: null,
                  alt: '',
                };
                changeSlide({ elements: [...slide.elements, next] });
                setElementId(next.id);
              }}
            >
              <Plus size={15} />
              添加文字
            </button>
            <button
              className="icon-button"
              disabled={!element}
              aria-label="删除选中元素"
              onClick={() =>
                changeSlide({ elements: slide.elements.filter((item) => item.id !== element.id) })
              }
            >
              <Trash2 size={16} />
            </button>
          </div>
        </div>
        {element && (
          <>
            {element.kind === 'IMAGE' ? (
              <Field label={`第 ${index + 1} 页图片说明`} hint="说明会提供给无法看到图片的读者。">
                <input
                  value={element.alt}
                  maxLength={1000}
                  onChange={(event) => changeElement(element.id, { alt: event.target.value })}
                />
              </Field>
            ) : (
              <>
                <Field label={`第 ${index + 1} 页元素 ${slide.elements.indexOf(element) + 1}`}>
                  <textarea
                    className={element.kind === 'CODE' ? 'code-input' : ''}
                    rows={5}
                    maxLength={8000}
                    value={element.text}
                    onChange={(event) => changeElement(element.id, { text: event.target.value })}
                  />
                </Field>
                <label className="checkbox-field">
                  <input
                    type="checkbox"
                    checked={element.kind === 'CODE'}
                    onChange={(event) =>
                      changeElement(element.id, { kind: event.target.checked ? 'CODE' : 'TEXT' })
                    }
                  />
                  作为代码显示
                </label>
              </>
            )}
            <div className="position-controls">
              {(['x', 'y', 'width', 'height'] as const).map((axis) => {
                const label = { x: '水平位置', y: '垂直位置', width: '宽度', height: '高度' }[axis];
                const max =
                  axis === 'x'
                    ? 100 - element.width * 100
                    : axis === 'y'
                      ? 100 - element.height * 100
                      : axis === 'width'
                        ? 100 - element.x * 100
                        : 100 - element.y * 100;
                return (
                  <Field key={axis} label={`${label} · ${Math.round(element[axis] * 100)}%`}>
                    <input
                      type="range"
                      min={axis === 'width' || axis === 'height' ? 1 : 0}
                      max={Math.max(0, Math.floor(max))}
                      step={1}
                      value={Math.round(element[axis] * 100)}
                      onChange={(event) =>
                        changeElement(element.id, {
                          [axis]: Math.min(Number(event.target.value), max) / 100,
                        })
                      }
                    />
                  </Field>
                );
              })}
            </div>
          </>
        )}
      </div>
      <div className="two-column slide-notes">
        <Field label={`第 ${index + 1} 页讲解备注`}>
          <textarea
            rows={3}
            maxLength={6000}
            value={slide.notes}
            onChange={(event) => changeSlide({ notes: event.target.value })}
          />
        </Field>
        <Field label="画幅">
          <select
            value={content.aspect}
            onChange={(event) =>
              onChange({ ...content, aspect: event.target.value as Slides['aspect'] })
            }
          >
            <option value="16:9">16:9 宽屏</option>
            <option value="4:3">4:3 标准</option>
          </select>
        </Field>
      </div>
    </div>
  );
}

function CanvasElement({
  element,
  selected,
  select,
  change,
}: {
  element: SlideElement;
  selected: boolean;
  select: () => void;
  change: (patch: Partial<SlideElement>) => void;
}) {
  const drag = useRef<{
    x: number;
    y: number;
    width: number;
    height: number;
    source: SlideElement;
    resize: boolean;
  } | null>(null);
  const begin = (event: React.PointerEvent<HTMLElement>, resize: boolean) => {
    if (event.button !== 0 || event.currentTarget.closest('fieldset[disabled]')) return;
    event.preventDefault();
    event.stopPropagation();
    select();
    const rect = event.currentTarget.closest('.slide-canvas')!.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    drag.current = {
      x: event.clientX,
      y: event.clientY,
      width: rect.width,
      height: rect.height,
      source: element,
      resize,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const move = (event: React.PointerEvent<HTMLElement>) => {
    if (!drag.current) return;
    const { x, y, width, height, source, resize } = drag.current;
    const dx = (event.clientX - x) / width,
      dy = (event.clientY - y) / height;
    change(
      resize
        ? {
            width: clamp(source.width + dx, 0.03, 1 - source.x),
            height: clamp(source.height + dy, 0.03, 1 - source.y),
          }
        : {
            x: clamp(source.x + dx, 0, 1 - source.width),
            y: clamp(source.y + dy, 0, 1 - source.height),
          },
    );
  };
  return (
    <div
      role="button"
      tabIndex={0}
      aria-label={`布局元素 ${element.kind === 'IMAGE' ? element.alt || '图片' : element.text.slice(0, 50) || '空白文字'}`}
      aria-pressed={selected}
      className={`slide-element draggable ${element.kind === 'CODE' ? 'code' : ''} ${selected ? 'selected' : ''}`}
      style={{
        left: `${element.x * 100}%`,
        top: `${element.y * 100}%`,
        width: `${element.width * 100}%`,
        height: `${element.height * 100}%`,
      }}
      onFocus={select}
      onPointerDown={(event) => begin(event, false)}
      onPointerMove={move}
      onPointerUp={() => {
        drag.current = null;
      }}
      onPointerCancel={() => {
        drag.current = null;
      }}
      onKeyDown={(event) => {
        if (event.currentTarget.closest('fieldset[disabled]')) return;
        const step = event.shiftKey ? 0.05 : 0.01;
        if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
          event.preventDefault();
          change({
            x: clamp(
              element.x +
                (event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0),
              0,
              1 - element.width,
            ),
            y: clamp(
              element.y + (event.key === 'ArrowUp' ? -step : event.key === 'ArrowDown' ? step : 0),
              0,
              1 - element.height,
            ),
          });
        }
      }}
    >
      {element.kind === 'IMAGE' && element.assetId ? (
        <img
          className="slide-image"
          src={`/api/v1/assets/${encodeURIComponent(element.assetId)}/download`}
          alt={element.alt}
          draggable={false}
        />
      ) : element.kind === 'CODE' ? (
        <pre>
          <code>{element.text || '// 写下一段代码'}</code>
        </pre>
      ) : (
        <p>{element.text || '写下你的内容'}</p>
      )}
      {selected && (
        <span
          className="resize-grip"
          aria-hidden="true"
          onPointerDown={(event) => begin(event, true)}
          onPointerMove={move}
          onPointerUp={() => {
            drag.current = null;
          }}
          onPointerCancel={() => {
            drag.current = null;
          }}
        >
          <Grip size={16} />
        </span>
      )}
    </div>
  );
}
