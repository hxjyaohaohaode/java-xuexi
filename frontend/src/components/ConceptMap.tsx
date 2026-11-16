import { useMemo, useState } from 'react';
import { ArrowRight, BookOpen, Check, GitBranch, List, Network } from 'lucide-react';
import { useWorkspace } from '../context';
import { usePolling, useResource } from '../hooks';
import { Badge, Empty, ErrorNotice, Loading } from './ui';
import type { Concept, Progress } from '../types';

type PositionedConcept = { concept: Concept; level: number; row: number; x: number; y: number };

/** The published prerequisite edges define the layout; reading never changes those edges. */
export function conceptLayout(concepts: Concept[]) {
  const byId = new Map(concepts.map((concept) => [concept.id, concept]));
  const outgoing = new Map<string, string[]>();
  const remaining = new Map<string, number>();
  const levels = new Map<string, number>();
  const missing: string[] = [];
  for (const concept of concepts) {
    const parents = [...new Set(concept.prerequisiteIds)];
    remaining.set(concept.id, parents.filter((id) => byId.has(id)).length);
    for (const parent of parents) {
      if (!byId.has(parent)) missing.push(parent);
      else outgoing.set(parent, [...(outgoing.get(parent) || []), concept.id]);
    }
  }
  const queue = concepts.filter((concept) => remaining.get(concept.id) === 0).map((c) => c.id);
  for (let cursor = 0; cursor < queue.length; cursor += 1) {
    const id = queue[cursor];
    const level = levels.get(id) || 0;
    levels.set(id, level);
    for (const child of outgoing.get(id) || []) {
      levels.set(child, Math.max(levels.get(child) || 0, level + 1));
      remaining.set(child, remaining.get(child)! - 1);
      if (remaining.get(child) === 0) queue.push(child);
    }
  }
  const unresolved = concepts.filter((c) => !queue.includes(c.id)).map((c) => c.id);
  const lastLevel = Math.max(0, ...levels.values()) + 1;
  const rows = new Map<number, number>();
  const nodes: PositionedConcept[] = concepts.map((concept) => {
    const level = unresolved.includes(concept.id) ? lastLevel : levels.get(concept.id) || 0;
    const row = rows.get(level) || 0;
    rows.set(level, row + 1);
    return { concept, level, row, x: 20 + level * 250, y: 48 + row * 154 };
  });
  return {
    nodes,
    unresolved,
    missing: [...new Set(missing)],
    width: Math.max(450, ...nodes.map((node) => node.x + 234)),
    height: Math.max(230, ...nodes.map((node) => node.y + 150)),
  };
}

function relatives(concepts: Concept[], selectedId: string, direction: 'before' | 'after') {
  const byId = new Map(concepts.map((concept) => [concept.id, concept]));
  const seen = new Set<string>();
  const pending = [selectedId];
  while (pending.length) {
    const current = pending.pop()!;
    const related =
      direction === 'before'
        ? byId.get(current)?.prerequisiteIds || []
        : concepts.filter((concept) => concept.prerequisiteIds.includes(current)).map((c) => c.id);
    for (const id of related) {
      if (id !== selectedId && byId.has(id) && !seen.has(id)) {
        seen.add(id);
        pending.push(id);
      }
    }
  }
  return seen;
}

export function ConceptMap({
  concepts,
  selectedId,
  completed,
  onRead,
}: {
  concepts: Concept[];
  selectedId: string;
  completed: string[];
  onRead: (id: string) => void;
}) {
  const { tick, navigate } = useWorkspace();
  const [selected, setSelected] = useState(selectedId);
  const [view, setView] = useState<'map' | 'list'>(concepts.length > 80 ? 'list' : 'map');
  const [relatedOnly, setRelatedOnly] = useState(false);
  const [query, setQuery] = useState('');
  const [poll, setPoll] = useState(0);
  const progress = useResource<Progress>('/me/progress', tick + poll);
  usePolling(
    !!progress.data?.rebuilding,
    () => setPoll((v) => v + 1),
    progress.loading,
    !!progress.error,
  );
  const chosen = concepts.find((c) => c.id === selected) || concepts[0];
  const ancestors = useMemo(
    () => relatives(concepts, chosen?.id || '', 'before'),
    [concepts, chosen?.id],
  );
  const descendants = useMemo(
    () => relatives(concepts, chosen?.id || '', 'after'),
    [concepts, chosen?.id],
  );
  const layout = useMemo(() => conceptLayout(concepts), [concepts]);
  const visible = (concept: Concept) =>
    (!relatedOnly ||
      concept.id === chosen?.id ||
      ancestors.has(concept.id) ||
      descendants.has(concept.id)) &&
    (!query.trim() ||
      `${concept.code} ${concept.title}`
        .toLocaleLowerCase()
        .includes(query.trim().toLocaleLowerCase()));
  const shown = layout.nodes.filter((node) => visible(node.concept));
  const byId = new Map(layout.nodes.map((node) => [node.concept.id, node]));
  const evidence = (id: string) => progress.data?.items.find((item) => item.conceptId === id);
  const relation = (id: string) =>
    id === chosen?.id
      ? '当前概念'
      : ancestors.has(id)
        ? '前置基础'
        : descendants.has(id)
          ? '后续概念'
          : '';
  const selectionButton = (concept: Concept, style?: React.CSSProperties) => {
    const samples = evidence(concept.id)?.independentSamples;
    return (
      <button
        key={concept.id}
        type="button"
        className={`concept-map-node ${concept.id === chosen?.id ? 'selected' : ''} ${relation(concept.id) ? 'related' : ''}`}
        style={style}
        aria-pressed={concept.id === chosen?.id}
        aria-controls="concept-path-detail"
        onClick={() => setSelected(concept.id)}
      >
        <span className="row spread">
          <span className="concept-code">{concept.code}</span>
          <small>{relation(concept.id)}</small>
        </span>
        <strong>{concept.title}</strong>
        <span className="concept-node-status">
          <span>
            {completed.includes(concept.id) ? (
              <>
                <Check size={12} /> 已读
              </>
            ) : (
              '未记录阅读'
            )}
          </span>
          <span>
            {samples === undefined
              ? progress.data
                ? '暂无独立样本'
                : '证据待读取'
              : `${samples} 个独立样本`}
          </span>
        </span>
      </button>
    );
  };
  if (!chosen)
    return <Empty title="还没有可显示的概念路径">发布概念后，这里会显示真实的前置关系。</Empty>;
  const parents = concepts.filter((concept) => chosen.prerequisiteIds.includes(concept.id));
  const children = concepts.filter((concept) => concept.prerequisiteIds.includes(chosen.id));
  const unready = parents.filter((concept) => !completed.includes(concept.id));
  const nextReading = concepts.find(
    (concept) =>
      !completed.includes(concept.id) &&
      concept.prerequisiteIds.every((id) => completed.includes(id)),
  );
  return (
    <section className="concept-paths" aria-label="课程概念路径">
      <div className="concept-path-intro">
        <div>
          <h2>
            <GitBranch size={20} /> 看清每一步从哪里来
          </h2>
          <p className="muted">
            连线只表示当前课程发布的前置关系。已读来自这个工作空间，独立作答证据来自你的账号；两者都不等于已经掌握。
          </p>
        </div>
        {nextReading && (
          <button onClick={() => onRead(nextReading.id)}>
            <BookOpen size={16} /> 接着读 {nextReading.code}
          </button>
        )}
      </div>
      <div className="concept-path-tools">
        <div className="segments" aria-label="路径显示方式">
          <button
            aria-pressed={view === 'map'}
            onClick={() => setView('map')}
            disabled={concepts.length > 80}
          >
            <Network size={15} /> 关系图
          </button>
          <button aria-pressed={view === 'list'} onClick={() => setView('list')}>
            <List size={15} /> 层次列表
          </button>
        </div>
        <input
          aria-label="筛选路径中的概念"
          placeholder="查找概念或编号"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          maxLength={100}
        />
        <label className="check-row">
          <input
            type="checkbox"
            checked={relatedOnly}
            onChange={(e) => setRelatedOnly(e.target.checked)}
          />{' '}
          只看当前关联路径
        </label>
      </div>
      <ErrorNotice error={progress.error} retry={progress.reload} />
      {progress.loading && !progress.data && <Loading label="正在读取独立作答证据…" />}
      {(layout.unresolved.length > 0 || layout.missing.length > 0) && (
        <p className="notice warning">
          部分发布关系无法排列为完整前置路径，已保留对应概念供阅读，请课程编辑核对关系。路径不会阻止你继续学习。
        </p>
      )}
      {concepts.length > 80 && (
        <p className="muted small">
          较大的课程使用层次列表，避免关系图过于密集；所有前置关系仍可在右侧逐项查看。
        </p>
      )}
      <div className="concept-path-layout">
        <div className="concept-path-board">
          {shown.length === 0 ? (
            <Empty title="没有匹配的概念">
              <button
                onClick={() => {
                  setQuery('');
                  setRelatedOnly(false);
                }}
              >
                清除筛选
              </button>
            </Empty>
          ) : view === 'map' ? (
            <>
              <p className="muted small" id="concept-map-help">
                从左向右查看前置层次。选择节点查看关系；可拖动滚动条、触摸滑动，或聚焦图区域后用方向键滚动。
              </p>
              <div
                className="concept-map-scroll"
                role="region"
                aria-label="概念关系图"
                aria-describedby="concept-map-help"
                tabIndex={0}
              >
                <div
                  className="concept-map-canvas"
                  style={{ width: layout.width, height: layout.height }}
                >
                  <svg aria-hidden="true" width={layout.width} height={layout.height}>
                    <defs>
                      <marker
                        id="prerequisite-arrow"
                        viewBox="0 0 10 10"
                        refX="9"
                        refY="5"
                        markerWidth="5"
                        markerHeight="5"
                        orient="auto"
                      >
                        <path d="M 0 0 L 10 5 L 0 10 z" />
                      </marker>
                    </defs>
                    {shown.flatMap((node) =>
                      node.concept.prerequisiteIds.map((id) => {
                        const parent = byId.get(id);
                        if (!parent || !visible(parent.concept)) return null;
                        const active = !!relation(node.concept.id) && !!relation(id);
                        return (
                          <path
                            key={`${id}:${node.concept.id}`}
                            className={active ? 'related' : ''}
                            markerEnd="url(#prerequisite-arrow)"
                            d={`M ${parent.x + 214} ${parent.y + 61} C ${parent.x + 232} ${parent.y + 61}, ${node.x - 18} ${node.y + 61}, ${node.x - 4} ${node.y + 61}`}
                          />
                        );
                      }),
                    )}
                  </svg>
                  {[...new Set(shown.map((node) => node.level))].map((level) => (
                    <span
                      className="concept-level-label"
                      key={level}
                      style={{ left: 20 + level * 250 }}
                    >
                      {level === 0 ? '起点' : `前置层次 ${level + 1}`}
                    </span>
                  ))}
                  {shown.map((node) =>
                    selectionButton(node.concept, { left: node.x, top: node.y }),
                  )}
                </div>
              </div>
            </>
          ) : (
            <div className="concept-path-list" aria-label="概念层次列表">
              {[...new Set(shown.map((node) => node.level))]
                .sort((a, b) => a - b)
                .map((level) => (
                  <section key={level}>
                    <h3>{level === 0 ? '起点：不依赖其他课程概念' : `前置层次 ${level + 1}`}</h3>
                    <div>
                      {shown
                        .filter((node) => node.level === level)
                        .map((node) => selectionButton(node.concept))}
                    </div>
                  </section>
                ))}
            </div>
          )}
        </div>
        <aside
          className="concept-path-detail panel"
          id="concept-path-detail"
          aria-label="选中概念的前置与后续"
        >
          <div className="row spread">
            <span className="concept-code">{chosen.code}</span>
            <Badge>{completed.includes(chosen.id) ? '已记录阅读' : '尚未记录阅读'}</Badge>
          </div>
          <h3 aria-live="polite">{chosen.title}</h3>
          <p className="muted small">
            {progress.data?.rebuilding
              ? '独立证据正在汇总，以下是最近一次确认结果。'
              : evidence(chosen.id)?.evidenceStatus === 'AVAILABLE'
                ? '已有足够样本形成当前证据解释，仍可继续用新题检验。'
                : '独立证据仍不足，先阅读并用新的题目检验判断。'}
          </p>
          <div className="concept-relation-group">
            <h4>先理解这些</h4>
            {parents.length ? (
              parents.map((concept) => (
                <button key={concept.id} onClick={() => setSelected(concept.id)}>
                  <span>
                    {concept.code} · {concept.title}
                  </span>
                  {completed.includes(concept.id) && <Check size={14} />}
                </button>
              ))
            ) : (
              <p className="muted small">当前课程没有为它指定前置概念。</p>
            )}
          </div>
          {unready.length > 0 && (
            <p className="muted small">
              还有 {unready.length} 个直接前置概念未记录阅读。你可以先回看，也可以直接开始。
            </p>
          )}
          <div className="concept-relation-group">
            <h4>接下来会用到</h4>
            {children.length ? (
              children.map((concept) => (
                <button key={concept.id} onClick={() => setSelected(concept.id)}>
                  {concept.code} · {concept.title}
                  <ArrowRight size={14} />
                </button>
              ))
            ) : (
              <p className="muted small">当前发布中没有直接后续概念。</p>
            )}
          </div>
          <div className="stack">
            <button className="primary" onClick={() => onRead(chosen.id)}>
              <BookOpen size={16} /> 阅读这一概念
            </button>
            <button onClick={() => navigate('practice', chosen.id)}>
              练习这一概念 <ArrowRight size={15} />
            </button>
          </div>
          <p className="muted small">
            {chosen.relatedExerciseIds.length} 道已发布关联练习 · {chosen.evidence.length}{' '}
            条课程证据
          </p>
        </aside>
      </div>
    </section>
  );
}
