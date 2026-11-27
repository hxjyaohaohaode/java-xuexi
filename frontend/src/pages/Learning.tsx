import { lazy, Suspense, useEffect, useRef, useState, type CSSProperties } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  BookOpen,
  Check,
  CheckSquare,
  ChevronDown,
  ChevronRight,
  FlaskConical,
  GitBranch,
  List,
  MessageCircle,
  PanelRightClose,
  RotateCcw,
  Search,
  Workflow,
  X,
} from 'lucide-react';
import { api } from '../api';
import { WorkspaceContext, useWorkspace, type ClassroomScene } from '../context';
import { useMutation, useResource, useSessionDraft } from '../hooks';
import { Confirmed, Empty, ErrorNotice, EvidenceList, Loading } from '../components/ui';
import { ConceptMap } from '../components/ConceptMap';
import { ClassroomExport } from '../components/ClassroomExport';
import { CourseExamples } from '../components/CourseExamples';
import { LessonPractice } from '../components/LessonPractice';
import { ConversationPanel } from './Tutor';
import { Lab } from './Lab';
import { ProgressPage } from './Progress';
import { GoalsPanel } from './Personal';
import type { Concept, Evidence } from '../types';

const Workshop = lazy(() => import('./Agents').then(({ Agents }) => ({ default: Agents })));
const scenes = [
  ['read', '课程阅读', BookOpen],
  ['practice', '随堂练习', CheckSquare],
  ['lab', '代码实验', FlaskConical],
  ['review', '本节复盘', RotateCcw],
  ['workshop', '协作创作', Workflow],
] as const;

type PathStep = {
  conceptId: string;
  code: string;
  title: string;
  action: 'READ' | 'PRACTICE' | 'REPAIR' | 'REVISIT';
  reason: string;
  exerciseId: string | null;
  attemptId: string | null;
  readCompleted: boolean;
  gradedAttempts: number;
  independentCorrectFamilies: number;
};
const actionNames = {
  READ: '开始阅读',
  PRACTICE: '做一道练习',
  REPAIR: '复练这个概念',
  REVISIT: '回看课程',
};

export function Learning() {
  const context = useWorkspace();
  const { workspace, session, tick, refresh, selection, can } = context;
  const scope = `${session.userId}.${workspace.id}.${workspace.releaseId}`;
  const concepts = useResource<{ items: Concept[] }>(`/concepts?releaseId=${workspace.releaseId}`);
  const readings = useResource<{ items: { conceptId: string; completed: boolean }[] }>(
    can('READING_HISTORY') ? `/workspaces/${workspace.id}/readings` : null,
    tick,
  );
  const path = useResource<{ workspaceId: string; releaseId: string; steps: PathStep[] }>(
    can('LEARNING_PATH') ? `/workspaces/${workspace.id}/learning-path` : null,
    tick,
  );
  const [lastConcept, remember] = useSessionDraft('tf.classroom.last.' + scope, '');
  const [helperOpen, setHelper] = useSessionDraft(
    'tf.classroom.tutor.' + scope,
    workspace.guidanceMode === 'COLLABORATIVE',
  );
  const [width, setWidth] = useSessionDraft('tf.classroom.width.' + scope, 36);
  const [directory, setDirectory] = useState(false);
  const [recommendations, setRecommendations] = useState(false);
  const [confirmedIds, setConfirmed] = useState<string[]>([]);
  const [lastAttempt, setAttempt] = useState<{ conceptId: string; id: string } | null>(null);
  const [query, setQuery] = useSessionDraft('tf.search.' + scope, '');
  const [submitted, setSubmitted] = useState('');
  const search = useResource<{ items: Evidence[] }>(
    submitted
      ? `/knowledge/search?releaseId=${workspace.releaseId}&q=${encodeURIComponent(submitted)}&topK=8`
      : null,
  );
  const mutation = useMutation();
  const archive = useMutation();
  const tutorButton = useRef<HTMLButtonElement>(null);
  const helperHeading = useRef<HTMLHeadingElement>(null);
  const helperFocused = useRef(false);
  const stage = useRef<HTMLDivElement>(null);
  const scene = context.classroomScene || 'read';
  const items = concepts.data?.items || [];
  const chosen = selection
    ? items.find((c) => c.id === selection)
    : items.find((c) => c.id === lastConcept) || items[0];
  const activePath =
    path.data?.workspaceId === workspace.id && path.data.releaseId === workspace.releaseId
      ? path.data
      : null;
  const ordered = activePath?.steps || [];
  const next = ordered[0];
  const current = ordered.find((step) => step.conceptId === chosen?.id);
  const read = [
    ...new Set([
      ...confirmedIds,
      ...(readings.data?.items.filter((r) => r.completed).map((r) => r.conceptId) || []),
      ...ordered.filter((s) => s.readCompleted).map((s) => s.conceptId),
    ]),
  ];
  const idx = items.findIndex((c) => c.id === chosen?.id);
  const attemptId =
    lastAttempt && lastAttempt.conceptId === chosen?.id
      ? lastAttempt.id
      : current?.attemptId || undefined;
  const studyAttempt = current?.action === 'REPAIR' ? current.attemptId || undefined : undefined;
  useEffect(() => {
    if (chosen && selection && selection !== lastConcept) remember(chosen.id);
  }, [selection, chosen?.id]);
  useEffect(() => {
    if (helperOpen && helperFocused.current) {
      helperHeading.current?.focus();
      helperFocused.current = false;
    }
  }, [helperOpen]);
  const open = (id: string, nextScene: ClassroomScene = 'read', exerciseId?: string) => {
    setSubmitted('');
    setDirectory(false);
    setRecommendations(false);
    if (context.openClassroom) context.openClassroom(id, nextScene, exerciseId);
    else context.navigate('learn', id);
  };
  const closeHelper = () => {
    setHelper(false);
    tutorButton.current?.focus();
  };
  const follow = (step: PathStep) =>
    open(
      step.conceptId,
      step.action === 'PRACTICE' || step.action === 'REPAIR' ? 'practice' : 'read',
      step.exerciseId || undefined,
    );
  const recordRead = (id: string) =>
    mutation.run(
      () =>
        api.request(
          '/learning-events',
          'POST',
          {
            workspaceId: workspace.id,
            releaseId: workspace.releaseId,
            conceptId: id,
            eventType: 'READ_COMPLETED',
          },
          { idempotent: true },
        ),
      () => {
        setConfirmed((prev) => [...new Set([...prev, id])]);
        refresh();
      },
    );
  const effectiveWidth = Math.min(48, Math.max(27, Number(width) || 36));
  const resize = (clientX: number) => {
    const rect = stage.current?.getBoundingClientRect();
    if (rect?.width)
      setWidth(Math.round(Math.min(48, Math.max(27, (100 * (rect.right - clientX)) / rect.width))));
  };
  return (
    <section className="classroom" aria-label="互动课堂">
      <header className="classroom-header">
        <div>
          <p className="classroom-kicker">Java 应用开发</p>
          <h1>泛型与类型安全</h1>
        </div>
        {chosen && (
          <div className="classroom-tools">
            <button
              aria-expanded={directory}
              aria-controls="classroom-directory"
              onClick={() => setDirectory(!directory)}
            >
              <List size={17} />
              课程目录
            </button>
            <button
              ref={tutorButton}
              aria-expanded={helperOpen}
              aria-controls="classroom-tutor"
              onClick={() => {
                if (helperOpen) closeHelper();
                else {
                  helperFocused.current = true;
                  setHelper(true);
                }
              }}
            >
              <MessageCircle size={17} />
              {helperOpen ? '收起导师' : '课堂导师'}
            </button>
          </div>
        )}
      </header>
      <ErrorNotice error={concepts.error} retry={concepts.reload} />
      <ErrorNotice error={readings.error} retry={readings.reload} />
      <ErrorNotice error={path.error} retry={path.reload} />
      {concepts.loading && !concepts.data ? (
        <Loading />
      ) : !chosen ? (
        !concepts.error && (
          <Empty title={selection ? '这个概念不在当前课程版本中' : '课程目录暂时为空'}>
            {selection && items[0] && (
              <button onClick={() => open(items[0].id)}>回到当前课程</button>
            )}
          </Empty>
        )
      ) : (
        <>
          {next && (
            <section className="classroom-next" aria-label="个性化学习路径">
              <div>
                <span className="classroom-kicker">建议下一步</span>
                <strong>{next.title}</strong>
                <p>{next.reason}</p>
              </div>
              <div className="classroom-tools">
                <button className="text-button" onClick={() => follow(next)}>
                  {actionNames[next.action]} <ArrowRight size={16} />
                </button>
                <button
                  className="icon-button"
                  aria-label="查看完整学习路径"
                  aria-expanded={recommendations}
                  aria-controls="classroom-route"
                  onClick={() => setRecommendations(!recommendations)}
                >
                  <ChevronDown size={17} />
                </button>
              </div>
            </section>
          )}
          {recommendations && (
            <div id="classroom-route">
              <details className="classroom-goals">
                <summary>调整学习目标</summary>
                <GoalsPanel />
              </details>
              <ol className="classroom-route">
                {ordered.map((step) => (
                  <li key={step.conceptId}>
                    <button onClick={() => follow(step)}>
                      <span>
                        {step.code} · {step.title}
                        <small>{step.reason}</small>
                      </span>
                      <strong>
                        {actionNames[step.action]} <ChevronRight size={14} />
                      </strong>
                    </button>
                  </li>
                ))}
              </ol>
            </div>
          )}
          {directory && (
            <section className="classroom-directory" id="classroom-directory" aria-label="课程目录">
              <div className="row spread">
                <h2>课程目录</h2>
                <button
                  className="icon-button"
                  aria-label="关闭课程目录"
                  onClick={() => setDirectory(false)}
                >
                  <X size={17} />
                </button>
              </div>
              <form
                className="search-form"
                role="search"
                onSubmit={(e) => {
                  e.preventDefault();
                  setSubmitted(query.trim());
                }}
              >
                <Search size={16} />
                <input
                  aria-label="搜索知识"
                  placeholder="检索课程原文与来源"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  maxLength={500}
                />
                <button disabled={!query.trim() || search.loading}>检索</button>
                {submitted && (
                  <button
                    type="button"
                    onClick={() => {
                      setSubmitted('');
                      setQuery('');
                    }}
                  >
                    返回课程
                  </button>
                )}
              </form>
              {submitted ? (
                search.loading ? (
                  <Loading />
                ) : search.error ? (
                  <ErrorNotice error={search.error} retry={search.reload} />
                ) : search.data?.items.length ? (
                  <div className="classroom-results">
                    {search.data.items.map((item) => (
                      <div key={item.chunkId}>
                        <EvidenceList compact items={[item]} />
                        <button className="text-button" onClick={() => open(item.conceptId)}>
                          打开所在概念 <ArrowRight size={14} />
                        </button>
                      </div>
                    ))}
                  </div>
                ) : (
                  <Empty title="当前课程中没有匹配证据">试试更具体的术语。</Empty>
                )
              ) : (
                <div className="classroom-index">
                  {items.map((c) => (
                    <button
                      key={c.id}
                      aria-current={chosen.id === c.id ? 'step' : undefined}
                      onClick={() => open(c.id)}
                    >
                      <small>{c.code}</small>
                      <span>{c.title}</span>
                      {read.includes(c.id) && <Check aria-label="已读" size={14} />}
                    </button>
                  ))}
                </div>
              )}
            </section>
          )}
          <div
            className={'classroom-panes' + (helperOpen ? ' with-tutor' : '')}
            ref={stage}
            style={{ '--tutor-width': effectiveWidth + '%' } as CSSProperties}
          >
            <div className="classroom-stage">
              <header className="classroom-lesson">
                <div>
                  <span className="classroom-kicker">
                    {chosen.code} · 第 {idx + 1} / {items.length} 节
                  </span>
                  <h2>{chosen.title}</h2>
                </div>
                <div className="classroom-tools">
                  <button
                    className="text-button"
                    aria-pressed={scene === 'map'}
                    onClick={() => open(chosen.id, 'map')}
                  >
                    <GitBranch size={16} />
                    概念路径
                  </button>
                </div>
              </header>
              <nav className="classroom-scenes" aria-label="本节学习活动">
                {scenes
                  .filter(([id]) => id !== 'workshop' || can('STUDY_WORKFLOW'))
                  .map(([id, label, Icon]) => (
                    <button
                      key={id}
                      aria-current={scene === id ? 'page' : undefined}
                      onClick={() => open(chosen.id, id)}
                    >
                      <Icon size={16} />
                      {label}
                    </button>
                  ))}
              </nav>
              <WorkspaceContext.Provider
                value={{
                  ...context,
                  selection: chosen.id,
                  navigate: (route, id) => {
                    if (route === 'learn') open(id || chosen.id);
                    else if (route === 'practice') open(id || chosen.id, 'practice');
                    else if (route === 'progress' || route === 'lab')
                      open(chosen.id, route === 'lab' ? 'lab' : 'review');
                    else context.navigate(route, id);
                  },
                }}
              >
                <div className={'classroom-activity scene-' + scene} key={chosen.id + ':' + scene}>
                  {scene === 'read' && (
                    <>
                      {chosen.prerequisiteIds.length > 0 && (
                        <div className="prerequisites">
                          <span>建议先读</span>
                          {chosen.prerequisiteIds.map((id) => (
                            <button key={id} className="text-button" onClick={() => open(id)}>
                              {items.find((c) => c.id === id)?.title || id}
                            </button>
                          ))}
                        </div>
                      )}
                      <EvidenceList items={chosen.evidence} />
                      <CourseExamples concept={chosen} />
                      <div className="classroom-read-actions">
                        <button
                          onClick={() => void recordRead(chosen.id)}
                          disabled={
                            !can('READING_EVENTS') || mutation.busy || read.includes(chosen.id)
                          }
                        >
                          {read.includes(chosen.id) ? '已记录阅读' : '记录已读'}
                        </button>
                        <button className="primary" onClick={() => open(chosen.id, 'practice')}>
                          练习这一概念 <ArrowRight size={16} />
                        </button>
                      </div>
                      <ErrorNotice error={mutation.error} />
                      {read.includes(chosen.id) && <Confirmed>服务端已记录本次阅读。</Confirmed>}
                    </>
                  )}
                  {scene === 'map' && (
                    <ConceptMap
                      concepts={items}
                      selectedId={chosen.id}
                      completed={read}
                      onRead={(id) => open(id)}
                    />
                  )}
                  {scene === 'practice' && (
                    <LessonPractice
                      key={context.classroomExercise || chosen.id}
                      concept={chosen}
                      requestedId={context.classroomExercise}
                      onAttempt={(a) => setAttempt({ conceptId: chosen.id, id: a.id })}
                    />
                  )}
                  {scene === 'lab' && <Lab initialConceptCode={chosen.code} />}
                  {scene === 'review' && <ProgressPage lesson={chosen} />}
                  {scene === 'workshop' && (
                    <Suspense fallback={<Loading />}>
                      <Workshop lesson={chosen} associatedAttempt={studyAttempt} />
                    </Suspense>
                  )}
                </div>
              </WorkspaceContext.Provider>
              <details className="classroom-export">
                <summary>导出本节课堂</summary>
                <p className="muted small">
                  OpenMAIC 课堂包含本节课程证据、配套 Java 示例和讨论活动。
                </p>
                <button
                  disabled={archive.busy}
                  onClick={() =>
                    void archive.run(async () => {
                      const { buildMaicArchive } = await import('../openmaic');
                      const bytes = buildMaicArchive(chosen);
                      const url = URL.createObjectURL(
                        new Blob([new Uint8Array(bytes)], { type: 'application/zip' }),
                      );
                      const link = document.createElement('a');
                      link.href = url;
                      link.download = chosen.code.replace(/[^a-zA-Z0-9_-]/g, '_') + '.maic.zip';
                      document.body.append(link);
                      link.click();
                      link.remove();
                      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
                    })
                  }
                >
                  {archive.busy ? '正在打包…' : '下载 OpenMAIC 课堂'}
                </button>
                <ErrorNotice error={archive.error} />
                <ClassroomExport topic={chosen} path={activePath} />
              </details>
              <nav className="reading-pagination" aria-label="相邻概念">
                <button
                  className="text-button"
                  disabled={idx === 0}
                  onClick={() => open(items[idx - 1].id)}
                >
                  <ArrowLeft size={16} />
                  上一个概念
                </button>
                <button
                  className="text-button"
                  disabled={idx === items.length - 1}
                  onClick={() => open(items[idx + 1].id)}
                >
                  下一个概念
                  <ArrowRight size={16} />
                </button>
              </nav>
            </div>
            {helperOpen && (
              <>
                <div
                  role="separator"
                  aria-label="调整导师窗格宽度"
                  aria-orientation="vertical"
                  aria-valuemin={27}
                  aria-valuemax={48}
                  aria-valuenow={effectiveWidth}
                  aria-valuetext={effectiveWidth + '%'}
                  tabIndex={0}
                  className="classroom-resize"
                  onPointerDown={(e) => {
                    e.preventDefault();
                    e.currentTarget.setPointerCapture(e.pointerId);
                  }}
                  onPointerMove={(e) => {
                    if (e.currentTarget.hasPointerCapture(e.pointerId)) resize(e.clientX);
                  }}
                  onDoubleClick={() => setWidth(36)}
                  onKeyDown={(e) => {
                    if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) {
                      e.preventDefault();
                      setWidth(
                        e.key === 'Home'
                          ? 27
                          : e.key === 'End'
                            ? 48
                            : Math.min(
                                48,
                                Math.max(27, effectiveWidth + (e.key === 'ArrowLeft' ? 2 : -2)),
                              ),
                      );
                    }
                  }}
                />
                <aside className="classroom-helper" id="classroom-tutor" aria-label="课堂导师">
                  <div className="classroom-helper-head">
                    <div>
                      <h2 ref={helperHeading} tabIndex={-1}>
                        课堂导师
                      </h2>
                      <p>{chosen.title}</p>
                    </div>
                    <button className="icon-button" aria-label="关闭课堂导师" onClick={closeHelper}>
                      <PanelRightClose size={18} />
                    </button>
                  </div>
                  <ConversationPanel key={chosen.id} lesson={chosen} attemptId={attemptId} />
                </aside>
              </>
            )}
          </div>
        </>
      )}
    </section>
  );
}
