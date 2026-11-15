import { useMemo, useState } from 'react';
import { BookOpenCheck, FilePlus2, LibraryBig, RefreshCw, Send, ShieldCheck } from 'lucide-react';
import { api, safeSourceUrl } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource, useSessionDraft, useUnsavedChanges } from '../hooks';
import {
  Badge,
  Confirmed,
  Dialog,
  Empty,
  ErrorNotice,
  Field,
  Loading,
  SectionTitle,
  Status,
} from '../components/ui';

type Ref = { id: string; revision: number };
type Source = {
  id: string;
  title: string;
  url: string;
  edition: string;
  usageNote: string;
  captureCount: number;
};
type Capture = {
  id: string;
  sourceId: string;
  originalMarkdown: string;
  cleanedMarkdown: string;
  originalHash: string;
  cleanedHash: string;
  processingNote: string;
  createdAt: string;
};
type Concept = { id: string; code: string; title: string; prerequisiteIds: string[] };
type Draft = Ref & { title: string; status: string; authorId: string };
type SourceRef = { sourceId: string; ingestionId: string; section: string };
type Chunk = Draft & { conceptId: string; body: string; reviewHash: string; sources: SourceRef[] };
type Answer = {
  options: { id: string; text: string }[];
  optionIds: string[];
  explanation: string;
  misconceptions: string[];
  hints?: string[];
  compileChecks?: unknown[];
};
type Exercise = Ref & {
  familyId: string;
  kind: string;
  difficulty: number;
  prompt: string;
  conceptIds: string[];
  knowledgeRefs: Ref[];
  answerSpec: Answer;
  reviewHash: string;
  status: string;
  authorId: string;
};
type Review = {
  id: string;
  targetKind: string;
  targetId: string;
  targetRevision: number;
  decision: string;
  note: string;
  reviewerId: string;
  createdAt: string;
};
type Release = {
  id: string;
  version: string;
  status: string;
  manifestHash: string;
  manifest: unknown;
  indexStatus: string;
  active: boolean;
  activeRevision: number;
};
type Tab = 'sources' | 'knowledge' | 'exercises' | 'review' | 'releases';
type DraftKey = 'source' | 'capture' | 'concept' | 'chunk' | 'exercise' | 'review' | 'release';
const draftNames: Record<DraftKey, string> = {
  source: '资料来源',
  capture: '采集记录',
  concept: '新概念',
  chunk: '知识卡',
  exercise: '练习',
  review: '审核意见',
  release: '发布清单',
};
const tabs: { id: Tab; title: string }[] = [
  { id: 'sources', title: '资料来源' },
  { id: 'knowledge', title: '知识版本' },
  { id: 'exercises', title: '练习设计' },
  { id: 'review', title: '独立审核' },
  { id: 'releases', title: '课程发布' },
];
const freshChunk = () => ({
  id: crypto.randomUUID() as string,
  revision: 1,
  baseRevision: 0,
  conceptId: '',
  title: '',
  body: '',
  sources: [{ sourceId: '', ingestionId: '', section: '' }] as SourceRef[],
});
const freshExercise = () => ({
  id: crypto.randomUUID() as string,
  revision: 1,
  baseRevision: 0,
  familyId: crypto.randomUUID() as string,
  kind: 'SINGLE',
  difficulty: 1,
  prompt: '',
  conceptIds: [] as string[],
  knowledgeRefs: [] as Ref[],
  options: [
    { id: 'A', text: '' },
    { id: 'B', text: '' },
  ],
  correct: ['A'],
  explanation: '',
  misconceptions: [] as string[],
  hints: '',
  compileChecks: '',
});
const refKey = (ref: Ref) => `${ref.id}:${ref.revision}`;
function toggleRef(values: Ref[], ref: Ref) {
  return values.some((v) => refKey(v) === refKey(ref))
    ? values.filter((v) => refKey(v) !== refKey(ref))
    : [...values, { id: ref.id, revision: ref.revision }];
}

export function Content() {
  const { session, refresh } = useWorkspace();
  const allowed = session.roles.some((r) => ['EDITOR', 'ADMIN'].includes(r));
  const [tab, setTab] = useState<Tab>('sources');
  const [version, update] = useState(0);
  const draftPrefix = `tf.content.${session.userId}`;
  const [dirty, setDirty] = useSessionDraft<Partial<Record<DraftKey, boolean>>>(
    `${draftPrefix}.dirty`,
    {},
  );
  const [replacement, setReplacement] = useState<{ key: DraftKey; action: () => void } | null>(
    null,
  );
  const [notice, setNotice] = useState('');
  const mutation = useMutation();
  const base = allowed ? '/admin' : null;
  const sources = useResource<Source[]>(base && `${base}/sources?limit=100`, version);
  const concepts = useResource<Concept[]>(base && `${base}/concepts?limit=100`, version);
  const chunks = useResource<Draft[]>(base && `${base}/chunks?limit=100`, version);
  const exercises = useResource<Draft[]>(base && `${base}/exercises?limit=100`, version);
  const reviews = useResource<Review[]>(base && `${base}/reviews?limit=100`, version);
  const releases = useResource<Release[]>(base && `${base}/releases?limit=100`, version);
  const [source, setSource] = useSessionDraft(`${draftPrefix}.source`, {
    title: '',
    url: '',
    edition: '',
    usageNote: '',
  });
  const [sourceId, setSourceId] = useSessionDraft(`${draftPrefix}.sourceId`, '');
  const captures = useResource<Capture[]>(
    base && sourceId ? `${base}/sources/${sourceId}/imports?limit=100` : null,
    version,
  );
  const [capture, setCapture] = useSessionDraft(`${draftPrefix}.capture`, {
    originalMarkdown: '',
    cleanedMarkdown: '',
    processingNote: '',
  });
  const [concept, setConcept] = useSessionDraft(`${draftPrefix}.concept`, {
    id: crypto.randomUUID() as string,
    code: '',
    title: '',
    prerequisiteIds: [] as string[],
  });
  const [chunk, setChunk] = useSessionDraft(`${draftPrefix}.chunk`, freshChunk());
  const [exercise, setExercise] = useSessionDraft(`${draftPrefix}.exercise`, freshExercise());
  const [reviewTarget, setReviewTarget] = useSessionDraft<{
    kind: 'CHUNK' | 'EXERCISE';
    ref: Ref;
  } | null>(`${draftPrefix}.reviewTarget`, null);
  const detail = useResource<Chunk | Exercise>(
    base && reviewTarget
      ? `${base}/${reviewTarget.kind === 'CHUNK' ? 'chunks' : 'exercises'}/${reviewTarget.ref.id}/revisions/${reviewTarget.ref.revision}`
      : null,
    version,
  );
  const [reviewNote, setReviewNote] = useSessionDraft(`${draftPrefix}.reviewNote`, '');
  const [releaseVersion, setReleaseVersion] = useSessionDraft(`${draftPrefix}.releaseVersion`, '');
  const [releaseChunks, setReleaseChunks] = useSessionDraft<Ref[]>(
    `${draftPrefix}.releaseChunks`,
    [],
  );
  const [releaseExercises, setReleaseExercises] = useSessionDraft<Ref[]>(
    `${draftPrefix}.releaseExercises`,
    [],
  );
  const [selectedRelease, setSelectedRelease] = useState<Release | null>(null);
  useUnsavedChanges(Object.values(dirty).some(Boolean));
  const errors = [
    sources.error,
    concepts.error,
    chunks.error,
    exercises.error,
    reviews.error,
    releases.error,
    captures.error,
    detail.error,
  ].filter(Boolean);
  const approveCandidates = useMemo(
    () =>
      [
        ...(chunks.data || []).map((d) => ({ ...d, kind: 'CHUNK' as const })),
        ...(exercises.data || []).map((d) => ({ ...d, kind: 'EXERCISE' as const })),
      ].filter((d) => d.status === 'DRAFT'),
    [chunks.data, exercises.data],
  );
  const changed = (key: DraftKey) => {
    setDirty((current) => ({ ...current, [key]: true }));
    setNotice('');
  };
  const saved = (message: string, key?: DraftKey) => {
    if (key) setDirty((current) => ({ ...current, [key]: false }));
    setNotice(message);
    update((v) => v + 1);
    refresh();
  };
  const replaceDraft = (key: DraftKey, action: () => void) => {
    if (dirty[key]) setReplacement({ key, action });
    else action();
  };
  if (!allowed)
    return (
      <Empty title="内容工作台需要编辑权限">
        请由项目维护者为实际编辑账户配置 EDITOR 或 ADMIN 角色。学习数据和私人作品仍由你自己管理。
      </Empty>
    );
  const submit = <T,>(path: string, value: unknown, commit: (result: T) => void) =>
    mutation.run(
      () => api.request<T>(`/admin${path}`, 'POST', value, { idempotent: true }),
      commit,
    );
  const code = ['SIGNATURE', 'REFACTOR', 'FILL'].includes(exercise.kind);
  return (
    <div className="content-workbench">
      <SectionTitle
        eyebrow="课程内容管理"
        title="让每一条知识都有来处"
        description="来源、知识、题目、审核和发布在这里连续完成。每次修改都会保留历史版本。"
        action={
          <button
            className="icon-button"
            aria-label="刷新内容工作台"
            onClick={() => update((v) => v + 1)}
          >
            <RefreshCw size={18} />
          </button>
        }
      />
      <div className="segmented content-tabs" role="tablist" aria-label="内容管理流程">
        {tabs.map((t, index) => (
          <button
            key={t.id}
            role="tab"
            tabIndex={tab === t.id ? 0 : -1}
            aria-selected={tab === t.id}
            aria-controls={`content-${t.id}`}
            id={`tab-${t.id}`}
            className={tab === t.id ? 'active' : ''}
            onClick={() => setTab(t.id)}
            onKeyDown={(event) => {
              const next =
                event.key === 'ArrowRight'
                  ? (index + 1) % tabs.length
                  : event.key === 'ArrowLeft'
                    ? (index + tabs.length - 1) % tabs.length
                    : event.key === 'Home'
                      ? 0
                      : event.key === 'End'
                        ? tabs.length - 1
                        : null;
              if (next !== null) {
                event.preventDefault();
                setTab(tabs[next].id);
                document.getElementById(`tab-${tabs[next].id}`)?.focus();
              }
            }}
          >
            {t.title}
          </button>
        ))}
      </div>
      {notice && <Confirmed>{notice}</Confirmed>}
      <ErrorNotice error={mutation.error} />
      {errors.slice(0, 1).map((error, i) => (
        <ErrorNotice key={i} error={error} retry={() => update((v) => v + 1)} />
      ))}
      {Object.values(dirty).some(Boolean) && (
        <p className="muted small" role="status">
          尚未提交：
          {(Object.keys(dirty) as DraftKey[])
            .filter((key) => dirty[key])
            .map((key) => draftNames[key])
            .join('、')}
          。已在当前会话保留输入，保存某一表单不会清除其他草稿。
        </p>
      )}
      {sources.loading && !sources.data && <Loading />}
      <fieldset className="editor-scope" disabled={mutation.busy} aria-label="课程编辑表单">
        <section
          role="tabpanel"
          id="content-sources"
          aria-labelledby="tab-sources"
          hidden={tab !== 'sources'}
          className="content-columns"
        >
          <div className="panel">
            <div className="row spread">
              <h2>
                <LibraryBig size={20} /> 资料来源
              </h2>
              <Badge>{sources.data?.length || 0} 条</Badge>
            </div>
            <div className="content-list">
              {sources.data?.map((s) => (
                <button
                  className={`content-row ${sourceId === s.id ? 'selected' : ''}`}
                  key={s.id}
                  onClick={() => {
                    if (sourceId !== s.id)
                      replaceDraft('capture', () => {
                        setSourceId(s.id);
                        setCapture({
                          originalMarkdown: '',
                          cleanedMarkdown: '',
                          processingNote: '',
                        });
                        setDirty((current) => ({ ...current, capture: false }));
                      });
                  }}
                >
                  <strong>{s.title}</strong>
                  <span>
                    {s.edition} · {s.captureCount} 次采集
                  </span>
                </button>
              ))}
            </div>
            <details className="editor-disclosure">
              <summary>登记新来源</summary>
              <form
                className="stack"
                onSubmit={(e) => {
                  e.preventDefault();
                  void submit<Source>('/sources', source, (result) => {
                    if (!dirty.capture) setSourceId(result.id);
                    setSource({ title: '', url: '', edition: '', usageNote: '' });
                    saved('资料来源已登记，可以选择来源后继续录入原稿。', 'source');
                  });
                }}
                onChange={() => changed('source')}
              >
                <Field label="来源名称">
                  <input
                    required
                    maxLength={255}
                    value={source.title}
                    onChange={(e) => setSource({ ...source, title: e.target.value })}
                  />
                </Field>
                <Field label="来源网址">
                  <input
                    required
                    type="url"
                    maxLength={2048}
                    value={source.url}
                    onChange={(e) => setSource({ ...source, url: e.target.value })}
                    placeholder="https://…"
                  />
                </Field>
                <Field label="资料版本">
                  <input
                    required
                    maxLength={128}
                    value={source.edition}
                    onChange={(e) => setSource({ ...source, edition: e.target.value })}
                    placeholder="如 Java SE 17"
                  />
                </Field>
                <Field label="使用范围与许可说明">
                  <textarea
                    required
                    maxLength={512}
                    value={source.usageNote}
                    onChange={(e) => setSource({ ...source, usageNote: e.target.value })}
                  />
                </Field>
                <button className="primary" disabled={mutation.busy}>
                  <FilePlus2 size={16} /> 登记来源
                </button>
              </form>
            </details>
          </div>
          <div className="panel">
            {!sourceId ? (
              <Empty title="选择一条来源">
                登记网址之后，继续录入实际原稿和清洗稿，才能为知识卡提供可追溯依据。
              </Empty>
            ) : (
              <>
                <h2>原稿与清洗记录</h2>
                {sources.data
                  ?.filter((s) => s.id === sourceId)
                  .map((s) => (
                    <div className="source-line" key={s.id}>
                      <strong>{s.title}</strong>
                      {safeSourceUrl(s.url) && (
                        <a href={safeSourceUrl(s.url)} target="_blank" rel="noreferrer">
                          打开来源
                        </a>
                      )}
                      <p>{s.usageNote}</p>
                    </div>
                  ))}
                <form
                  className="stack"
                  onChange={() => changed('capture')}
                  onSubmit={(e) => {
                    e.preventDefault();
                    void submit<Capture>(`/sources/${sourceId}/imports`, capture, () => {
                      setCapture({ originalMarkdown: '', cleanedMarkdown: '', processingNote: '' });
                      saved('原稿与清洗稿已保存，哈希和处理记录可以核对。', 'capture');
                    });
                  }}
                >
                  <Field label="实际采集的原稿">
                    <textarea
                      required
                      rows={6}
                      maxLength={32000}
                      value={capture.originalMarkdown}
                      onChange={(e) => setCapture({ ...capture, originalMarkdown: e.target.value })}
                    />
                  </Field>
                  <Field label="清洗后的内容">
                    <textarea
                      required
                      rows={6}
                      maxLength={16000}
                      value={capture.cleanedMarkdown}
                      onChange={(e) => setCapture({ ...capture, cleanedMarkdown: e.target.value })}
                    />
                  </Field>
                  <Field label="处理说明">
                    <input
                      required
                      maxLength={4000}
                      value={capture.processingNote}
                      onChange={(e) => setCapture({ ...capture, processingNote: e.target.value })}
                      placeholder="说明删减、去重和代码整理"
                    />
                  </Field>
                  <button className="primary" disabled={mutation.busy}>
                    保存采集记录
                  </button>
                </form>
                <div className="content-list">
                  {captures.data?.map((c) => (
                    <details key={c.id}>
                      <summary>
                        {new Date(c.createdAt).toLocaleString()} · {c.processingNote}
                      </summary>
                      <pre className="preserve-text">{c.cleanedMarkdown}</pre>
                      <dl className="metadata">
                        <dt>原稿 SHA-256</dt>
                        <dd>{c.originalHash}</dd>
                        <dt>清洗稿 SHA-256</dt>
                        <dd>{c.cleanedHash}</dd>
                      </dl>
                      <button
                        className="text-button"
                        disabled={mutation.busy || chunk.sources.length >= 10}
                        onClick={() => {
                          const refs = chunk.sources.filter((ref) => ref.sourceId);
                          if (!refs.some((ref) => ref.ingestionId === c.id))
                            setChunk({
                              ...chunk,
                              sources: [...refs, { sourceId, ingestionId: c.id, section: '' }],
                            });
                          changed('chunk');
                          setTab('knowledge');
                        }}
                      >
                        用这份记录编写知识卡
                      </button>
                    </details>
                  ))}
                </div>
              </>
            )}
          </div>
        </section>
        <section
          role="tabpanel"
          id="content-knowledge"
          aria-labelledby="tab-knowledge"
          hidden={tab !== 'knowledge'}
          className="content-columns"
        >
          <div className="panel">
            <h2>知识版本</h2>
            <div className="content-list">
              {chunks.data?.map((d) => (
                <div className="content-row" key={refKey(d)}>
                  <strong>{d.title}</strong>
                  <span>
                    版本 {d.revision} · <Status value={d.status} />
                  </span>
                  <button
                    className="text-button"
                    disabled={mutation.busy}
                    onClick={() =>
                      replaceDraft('chunk', () => {
                        void mutation.run(
                          () => api.get<Chunk>(`/admin/chunks/${d.id}/revisions/${d.revision}`),
                          (value) => {
                            setChunk({
                              id: value.id,
                              revision: value.revision + 1,
                              baseRevision: value.revision,
                              conceptId: value.conceptId,
                              title: value.title,
                              body: value.body,
                              sources: value.sources.map((ref) => ({ ...ref })),
                            });
                            changed('chunk');
                          },
                        );
                      })
                    }
                  >
                    修订为新版本
                  </button>
                </div>
              ))}
            </div>
            <details className="editor-disclosure">
              <summary>新增概念</summary>
              <form
                className="stack"
                onChange={() => changed('concept')}
                onSubmit={(e) => {
                  e.preventDefault();
                  void submit<Concept>('/concepts', concept, (result) => {
                    setChunk({ ...chunk, conceptId: result.id });
                    setConcept({
                      id: crypto.randomUUID() as string,
                      code: '',
                      title: '',
                      prerequisiteIds: [],
                    });
                    changed('chunk');
                    saved('新概念已登记。', 'concept');
                  });
                }}
              >
                <Field label="概念代码">
                  <input
                    required
                    pattern="[A-Z][A-Z0-9_]{1,15}"
                    maxLength={16}
                    value={concept.code}
                    onChange={(e) => setConcept({ ...concept, code: e.target.value })}
                  />
                </Field>
                <Field label="概念名称">
                  <input
                    required
                    maxLength={128}
                    value={concept.title}
                    onChange={(e) => setConcept({ ...concept, title: e.target.value })}
                  />
                </Field>
                <fieldset>
                  <legend>前置概念</legend>
                  {concepts.data?.map((c) => (
                    <label className="check-row" key={c.id}>
                      <input
                        type="checkbox"
                        checked={concept.prerequisiteIds.includes(c.id)}
                        disabled={
                          !concept.prerequisiteIds.includes(c.id) &&
                          concept.prerequisiteIds.length >= 16
                        }
                        onChange={() =>
                          setConcept({
                            ...concept,
                            prerequisiteIds: concept.prerequisiteIds.includes(c.id)
                              ? concept.prerequisiteIds.filter((id) => id !== c.id)
                              : [...concept.prerequisiteIds, c.id],
                          })
                        }
                      />
                      {c.title}
                    </label>
                  ))}
                </fieldset>
                <button className="primary" disabled={mutation.busy}>
                  创建概念
                </button>
              </form>
            </details>
          </div>
          <div className="panel">
            <div className="row spread">
              <h2>{chunk.baseRevision ? `修订知识 · 版本 ${chunk.revision}` : '编写知识卡'}</h2>
              <button
                className="text-button"
                disabled={mutation.busy}
                onClick={() =>
                  replaceDraft('chunk', () => {
                    setChunk(freshChunk());
                    setDirty((current) => ({ ...current, chunk: false }));
                  })
                }
              >
                新建
              </button>
            </div>
            <form
              className="stack"
              onChange={() => changed('chunk')}
              onSubmit={(e) => {
                e.preventDefault();
                void submit<Chunk>('/chunks', chunk, () => {
                  setChunk(freshChunk());
                  saved('知识草稿已保存，请由另一位编辑审核。', 'chunk');
                });
              }}
            >
              <Field label="所属概念">
                <select
                  required
                  value={chunk.conceptId}
                  onChange={(e) => setChunk({ ...chunk, conceptId: e.target.value })}
                >
                  <option value="">选择概念</option>
                  {concepts.data?.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.title}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="知识卡标题">
                <input
                  required
                  maxLength={255}
                  value={chunk.title}
                  onChange={(e) => setChunk({ ...chunk, title: e.target.value })}
                />
              </Field>
              <Field label="知识正文">
                <textarea
                  required
                  rows={12}
                  maxLength={16000}
                  value={chunk.body}
                  onChange={(e) => setChunk({ ...chunk, body: e.target.value })}
                />
              </Field>
              <fieldset className="citation-editors">
                <legend>固定来源与采集记录</legend>
                {chunk.sources.map((ref, index) => (
                  <EditorialSource
                    key={index}
                    value={ref}
                    index={index}
                    sources={sources.data || []}
                    version={version}
                    onChange={(value) => {
                      setChunk({
                        ...chunk,
                        sources: chunk.sources.map((item, i) => (i === index ? value : item)),
                      });
                      changed('chunk');
                    }}
                    onRemove={
                      chunk.sources.length > 1
                        ? () => {
                            setChunk({
                              ...chunk,
                              sources: chunk.sources.filter((_, i) => i !== index),
                            });
                            changed('chunk');
                          }
                        : undefined
                    }
                  />
                ))}
                <button
                  type="button"
                  className="text-button"
                  disabled={chunk.sources.length >= 10}
                  onClick={() => {
                    setChunk({
                      ...chunk,
                      sources: [...chunk.sources, { sourceId: '', ingestionId: '', section: '' }],
                    });
                    changed('chunk');
                  }}
                >
                  添加另一条来源
                </button>
              </fieldset>
              <button className="primary" disabled={mutation.busy}>
                保存知识草稿
              </button>
            </form>
          </div>
        </section>
        <section
          role="tabpanel"
          id="content-exercises"
          aria-labelledby="tab-exercises"
          hidden={tab !== 'exercises'}
          className="content-columns"
        >
          <div className="panel">
            <h2>练习版本</h2>
            <div className="content-list">
              {exercises.data?.map((d) => (
                <div className="content-row" key={refKey(d)}>
                  <strong>{d.title}</strong>
                  <span>
                    版本 {d.revision} · <Status value={d.status} />
                  </span>
                  <button
                    className="text-button"
                    disabled={mutation.busy}
                    onClick={() =>
                      replaceDraft('exercise', () => {
                        void mutation.run(
                          () =>
                            api.get<Exercise>(`/admin/exercises/${d.id}/revisions/${d.revision}`),
                          (value) => {
                            setExercise({
                              id: value.id,
                              revision: value.revision + 1,
                              baseRevision: value.revision,
                              familyId: value.familyId,
                              kind: value.kind,
                              difficulty: value.difficulty,
                              prompt: value.prompt,
                              conceptIds: value.conceptIds,
                              knowledgeRefs: value.knowledgeRefs,
                              options: value.answerSpec.options,
                              correct: value.answerSpec.optionIds,
                              explanation: value.answerSpec.explanation,
                              misconceptions: value.answerSpec.misconceptions || [],
                              hints: (value.answerSpec.hints || []).join('\n---\n'),
                              compileChecks: JSON.stringify(
                                value.answerSpec.compileChecks || [],
                                null,
                                2,
                              ),
                            });
                            changed('exercise');
                          },
                        );
                      })
                    }
                  >
                    修订为新版本
                  </button>
                </div>
              ))}
            </div>
          </div>
          <div className="panel">
            <div className="row spread">
              <h2>设计可核验的练习</h2>
              <button
                className="text-button"
                onClick={() =>
                  replaceDraft('exercise', () => {
                    setExercise(freshExercise());
                    setDirty((current) => ({ ...current, exercise: false }));
                  })
                }
              >
                新建练习
              </button>
            </div>
            <form
              className="stack"
              onChange={() => changed('exercise')}
              onSubmit={(e) => {
                e.preventDefault();
                void mutation.run(
                  async () => {
                    let compileChecks: unknown[] = [];
                    if (code) {
                      try {
                        compileChecks = JSON.parse(exercise.compileChecks);
                      } catch {
                        throw new Error('编译检查不是有效的 JSON，请检查引号、逗号和括号。');
                      }
                      if (
                        !Array.isArray(compileChecks) ||
                        compileChecks.length < 2 ||
                        compileChecks.length > 8
                      )
                        throw new Error(
                          '代码题需要 2 至 8 个私有检查，并同时包括预期通过和预期拒绝的检查。',
                        );
                    }
                    const hints = exercise.hints
                      .split(/\r?\n---\r?\n/)
                      .map((h) => h.trim())
                      .filter(Boolean);
                    if (hints.length > 3 || hints.some((h) => h.length > 4000))
                      throw new Error('提示最多 3 层，每层最多 4,000 字符。');
                    return api.request(
                      '/admin/exercises',
                      'POST',
                      {
                        id: exercise.id,
                        revision: exercise.revision,
                        baseRevision: exercise.baseRevision,
                        familyId: exercise.familyId,
                        kind: exercise.kind,
                        difficulty: exercise.difficulty,
                        prompt: exercise.prompt,
                        conceptIds: exercise.conceptIds,
                        knowledgeRefs: exercise.knowledgeRefs,
                        answerSpec: {
                          options: code ? [] : exercise.options,
                          optionIds: code ? [] : exercise.correct,
                          explanation: exercise.explanation,
                          misconceptions: exercise.misconceptions,
                          hints,
                          compileChecks,
                        },
                      },
                      { idempotent: true },
                    );
                  },
                  () => {
                    setExercise(freshExercise());
                    saved('练习草稿已保存，标准答案只对编辑与独立审核者可见。', 'exercise');
                  },
                );
              }}
            >
              <div className="form-grid">
                <Field label="题型">
                  <select
                    value={exercise.kind}
                    onChange={(e) =>
                      setExercise({
                        ...exercise,
                        kind: e.target.value,
                        correct:
                          e.target.value === 'SINGLE'
                            ? [exercise.correct[0] || 'A']
                            : exercise.correct,
                        options: exercise.options.length
                          ? exercise.options
                          : [
                              { id: 'A', text: '' },
                              { id: 'B', text: '' },
                            ],
                      })
                    }
                  >
                    <option value="SINGLE">单项选择</option>
                    <option value="MULTIPLE">多项选择</option>
                    <option value="COMPILE_PREDICTION">编译预测</option>
                    <option value="READING">代码阅读</option>
                    <option value="DIAGNOSIS">错误诊断</option>
                    <option value="SIGNATURE">代码签名</option>
                    <option value="REFACTOR">代码重构</option>
                    <option value="FILL">代码填空</option>
                  </select>
                </Field>
                <Field label="难度">
                  <select
                    value={exercise.difficulty}
                    onChange={(e) =>
                      setExercise({ ...exercise, difficulty: Number(e.target.value) })
                    }
                  >
                    <option value={1}>基础</option>
                    <option value={2}>进阶</option>
                    <option value={3}>综合</option>
                  </select>
                </Field>
              </div>
              <Field label="题目内容">
                <textarea
                  required
                  rows={5}
                  maxLength={16000}
                  value={exercise.prompt}
                  onChange={(e) => setExercise({ ...exercise, prompt: e.target.value })}
                />
              </Field>
              <fieldset>
                <legend>关联概念</legend>
                {concepts.data?.map((c) => (
                  <label className="check-row" key={c.id}>
                    <input
                      type="checkbox"
                      checked={exercise.conceptIds.includes(c.id)}
                      disabled={
                        !exercise.conceptIds.includes(c.id) && exercise.conceptIds.length >= 16
                      }
                      onChange={() =>
                        setExercise({
                          ...exercise,
                          conceptIds: exercise.conceptIds.includes(c.id)
                            ? exercise.conceptIds.filter((id) => id !== c.id)
                            : [...exercise.conceptIds, c.id],
                        })
                      }
                    />
                    {c.title}
                  </label>
                ))}
              </fieldset>
              <fieldset>
                <legend>固定知识依据</legend>
                {chunks.data?.map((c) => (
                  <label className="check-row" key={refKey(c)}>
                    <input
                      type="checkbox"
                      checked={exercise.knowledgeRefs.some((r) => refKey(r) === refKey(c))}
                      disabled={
                        !exercise.knowledgeRefs.some((r) => refKey(r) === refKey(c)) &&
                        exercise.knowledgeRefs.length >= 16
                      }
                      onChange={() =>
                        setExercise({
                          ...exercise,
                          knowledgeRefs: toggleRef(exercise.knowledgeRefs, c),
                        })
                      }
                    />
                    {c.title} · 版本 {c.revision}
                  </label>
                ))}
              </fieldset>
              {code ? (
                <Field
                  label="私有编译检查 JSON"
                  hint="必须同时包含正向和反向检查；按编译工作进程协议填写，学生端不会收到这些检查。"
                >
                  <textarea
                    required
                    rows={10}
                    maxLength={160000}
                    className="code-input"
                    value={exercise.compileChecks}
                    onChange={(e) => setExercise({ ...exercise, compileChecks: e.target.value })}
                  />
                </Field>
              ) : (
                <fieldset>
                  <legend>选项与标准答案</legend>
                  {exercise.options.map((option, index) => (
                    <div className="row" key={option.id}>
                      <label className="check-row">
                        <input
                          type="checkbox"
                          aria-label={`正确答案 ${option.id}`}
                          checked={exercise.correct.includes(option.id)}
                          onChange={() =>
                            setExercise({
                              ...exercise,
                              correct:
                                exercise.kind === 'SINGLE'
                                  ? [option.id]
                                  : exercise.correct.includes(option.id)
                                    ? exercise.correct.filter((id) => id !== option.id)
                                    : [...exercise.correct, option.id],
                            })
                          }
                        />
                        {option.id}
                      </label>
                      <input
                        required
                        aria-label={`选项 ${option.id} 内容`}
                        maxLength={2000}
                        value={option.text}
                        onChange={(e) =>
                          setExercise({
                            ...exercise,
                            options: exercise.options.map((v, i) =>
                              i === index ? { ...v, text: e.target.value } : v,
                            ),
                          })
                        }
                      />
                    </div>
                  ))}
                  <button
                    type="button"
                    className="text-button"
                    disabled={exercise.options.length >= 8}
                    onClick={() => {
                      setExercise({
                        ...exercise,
                        options: [
                          ...exercise.options,
                          { id: String.fromCharCode(65 + exercise.options.length), text: '' },
                        ],
                      });
                      changed('exercise');
                    }}
                  >
                    添加选项
                  </button>
                </fieldset>
              )}
              <Field label="讲解与判分依据">
                <textarea
                  required
                  rows={4}
                  maxLength={12000}
                  value={exercise.explanation}
                  onChange={(e) => setExercise({ ...exercise, explanation: e.target.value })}
                />
              </Field>
              <Field
                label="递进提示"
                hint="最多三层；用单独一行 --- 分隔。使用提示会作为辅助记录保存。"
              >
                <textarea
                  rows={4}
                  maxLength={12008}
                  value={exercise.hints}
                  onChange={(e) => setExercise({ ...exercise, hints: e.target.value })}
                />
              </Field>
              <button
                className="primary"
                disabled={
                  mutation.busy || !exercise.conceptIds.length || !exercise.knowledgeRefs.length
                }
              >
                保存练习草稿
              </button>
            </form>
          </div>
        </section>
        <section
          role="tabpanel"
          id="content-review"
          aria-labelledby="tab-review"
          hidden={tab !== 'review'}
          className="content-columns"
        >
          <div className="panel">
            <h2>
              <ShieldCheck size={20} /> 等待独立审核
            </h2>
            {!approveCandidates.length && (
              <Empty title="没有待审草稿">
                保存新的知识或题目版本后，另一位编辑可以在这里逐项核对。
              </Empty>
            )}
            <div className="content-list">
              {approveCandidates.map((d) => (
                <button
                  key={`${d.kind}:${refKey(d)}`}
                  className={`content-row ${reviewTarget?.ref.id === d.id ? 'selected' : ''}`}
                  onClick={() =>
                    replaceDraft('review', () => {
                      setReviewTarget({ kind: d.kind, ref: d });
                      setReviewNote('');
                      setDirty((current) => ({ ...current, review: false }));
                    })
                  }
                >
                  <strong>{d.title}</strong>
                  <span>
                    {d.kind === 'CHUNK' ? '知识' : '练习'} · 版本 {d.revision}{' '}
                    {d.authorId === session.userId && '· 你是作者'}
                  </span>
                </button>
              ))}
            </div>
          </div>
          <div className="panel">
            {detail.loading ? (
              <Loading />
            ) : detail.data && reviewTarget ? (
              <>
                <h2>{'title' in detail.data ? detail.data.title : detail.data.prompt}</h2>
                <p className="preserve-text">
                  {'body' in detail.data ? detail.data.body : detail.data.prompt}
                </p>
                {'sources' in detail.data &&
                  detail.data.sources.map((ref, index) => (
                    <ReviewSource
                      key={`${ref.ingestionId}:${index}`}
                      value={ref}
                      sources={sources.data || []}
                    />
                  ))}
                {'answerSpec' in detail.data && (
                  <details open>
                    <summary>标准答案、提示与私有判定</summary>
                    <pre>{JSON.stringify(detail.data.answerSpec, null, 2)}</pre>
                  </details>
                )}
                <dl className="metadata">
                  <dt>内容版本</dt>
                  <dd>{detail.data.revision}</dd>
                  <dt>完整审核哈希</dt>
                  <dd>{detail.data.reviewHash}</dd>
                </dl>
                <Field label="审核意见">
                  <textarea
                    rows={5}
                    maxLength={4000}
                    value={reviewNote}
                    onChange={(e) => {
                      setReviewNote(e.target.value);
                      changed('review');
                    }}
                  />
                </Field>
                <div className="row">
                  <button
                    className="primary"
                    disabled={
                      mutation.busy ||
                      detail.data.status !== 'DRAFT' ||
                      detail.data.authorId === session.userId ||
                      !reviewNote.trim()
                    }
                    onClick={() => {
                      void submit(
                        '/reviews',
                        {
                          targetKind: reviewTarget.kind,
                          targetId: detail.data!.id,
                          targetRevision: detail.data!.revision,
                          expectedContentHash: detail.data!.reviewHash,
                          decision: 'APPROVED',
                          note: reviewNote,
                        },
                        () => {
                          setReviewNote('');
                          setReviewTarget(null);
                          saved('独立审核已记录，此版本现在可以加入发布清单。', 'review');
                        },
                      );
                    }}
                  >
                    <BookOpenCheck size={16} /> 通过审核
                  </button>
                  <button
                    className="secondary"
                    disabled={
                      mutation.busy ||
                      detail.data.status !== 'DRAFT' ||
                      detail.data.authorId === session.userId ||
                      !reviewNote.trim()
                    }
                    onClick={() => {
                      void submit(
                        '/reviews',
                        {
                          targetKind: reviewTarget.kind,
                          targetId: detail.data!.id,
                          targetRevision: detail.data!.revision,
                          expectedContentHash: detail.data!.reviewHash,
                          decision: 'REJECTED',
                          note: reviewNote,
                        },
                        () => {
                          setReviewNote('');
                          setReviewTarget(null);
                          saved('退回意见已记录，作者可以继续新建修订版本。', 'review');
                        },
                      );
                    }}
                  >
                    退回修改
                  </button>
                </div>
                {detail.data.authorId === session.userId && (
                  <p className="muted">你是该版本的作者，请由另一位真实编辑完成审核。</p>
                )}
              </>
            ) : (
              <Empty title="先选择待审版本">
                核对来源、知识、题目标准答案和判定边界后，再提交意见。
              </Empty>
            )}
            <details>
              <summary>最近审核记录</summary>
              <div className="content-list">
                {reviews.data?.map((r) => (
                  <article key={r.id}>
                    <Badge tone={r.decision === 'APPROVED' ? 'good' : 'warn'}>
                      {r.decision === 'APPROVED' ? '通过' : '退回'}
                    </Badge>
                    <p>{r.note}</p>
                    <small>
                      版本 {r.targetRevision} · {new Date(r.createdAt).toLocaleString()}
                    </small>
                  </article>
                ))}
              </div>
            </details>
          </div>
        </section>
        <section
          role="tabpanel"
          id="content-releases"
          aria-labelledby="tab-releases"
          hidden={tab !== 'releases'}
          className="content-columns"
        >
          <div className="panel">
            <h2>构建课程版本</h2>
            <form
              className="stack"
              onChange={() => changed('release')}
              onSubmit={(e) => {
                e.preventDefault();
                void submit<Release>(
                  '/releases',
                  { version: releaseVersion, chunks: releaseChunks, exercises: releaseExercises },
                  (result) => {
                    setSelectedRelease(result);
                    setReleaseVersion('');
                    setReleaseChunks([]);
                    setReleaseExercises([]);
                    saved('发布清单已冻结，请核对后发布。', 'release');
                  },
                );
              }}
            >
              <Field label="课程版本号">
                <input
                  required
                  pattern="[A-Za-z0-9][A-Za-z0-9_.-]{0,31}"
                  maxLength={32}
                  value={releaseVersion}
                  onChange={(e) => setReleaseVersion(e.target.value)}
                  placeholder="如 generics-2026-11-15"
                />
              </Field>
              <fieldset>
                <legend>纳入的知识版本</legend>
                {chunks.data
                  ?.filter((c) => c.status === 'REVIEWED')
                  .map((c) => (
                    <label className="check-row" key={refKey(c)}>
                      <input
                        type="checkbox"
                        checked={releaseChunks.some((r) => refKey(r) === refKey(c))}
                        onChange={() => setReleaseChunks(toggleRef(releaseChunks, c))}
                      />
                      {c.title} · {c.revision}
                    </label>
                  ))}
              </fieldset>
              <fieldset>
                <legend>纳入的练习版本</legend>
                {exercises.data
                  ?.filter((c) => c.status === 'REVIEWED')
                  .map((c) => (
                    <label className="check-row" key={refKey(c)}>
                      <input
                        type="checkbox"
                        checked={releaseExercises.some((r) => refKey(r) === refKey(c))}
                        onChange={() => setReleaseExercises(toggleRef(releaseExercises, c))}
                      />
                      {c.title} · {c.revision}
                    </label>
                  ))}
              </fieldset>
              <p className="muted">
                演示种子不具备真实的双人审核记录；服务端会再次验证采集、审核、知识覆盖和完整哈希。
              </p>
              <button
                className="primary"
                disabled={mutation.busy || !releaseChunks.length || !releaseExercises.length}
              >
                冻结发布清单
              </button>
            </form>
          </div>
          <div className="panel">
            <h2>发布与历史版本</h2>
            <div className="content-list">
              {releases.data?.map((r) => (
                <button
                  className={`content-row ${selectedRelease?.id === r.id ? 'selected' : ''}`}
                  key={r.id}
                  onClick={() => setSelectedRelease(r)}
                >
                  <strong>{r.version}</strong>
                  <span>
                    <Status value={r.status} />
                    {r.active && <Badge tone="good">当前课程</Badge>}
                  </span>
                </button>
              ))}
            </div>
            {selectedRelease && (
              <div className="stack">
                <h3>{selectedRelease.version}</h3>
                <dl className="metadata">
                  <dt>清单哈希</dt>
                  <dd>{selectedRelease.manifestHash}</dd>
                  <dt>检索索引</dt>
                  <dd>{selectedRelease.indexStatus}</dd>
                </dl>
                <details>
                  <summary>查看固定清单</summary>
                  <pre>{JSON.stringify(selectedRelease.manifest, null, 2)}</pre>
                </details>
                <p>
                  发布后，新建的工作空间会采用这个课程版本。已有空间继续使用创建时的课程和判分依据。
                </p>
                <button
                  className="primary"
                  disabled={mutation.busy || selectedRelease.active}
                  onClick={() => {
                    void submit<Release>(
                      `/releases/${selectedRelease.id}/${selectedRelease.status === 'DRAFT' ? 'publish' : 'activate'}`,
                      {
                        expectedManifestHash: selectedRelease.manifestHash,
                        expectedActiveRevision: selectedRelease.activeRevision,
                      },
                      (result) => {
                        setSelectedRelease(result);
                        saved('课程版本已激活，历史学习记录保持可追溯。');
                      },
                    );
                  }}
                >
                  <Send size={16} />
                  {selectedRelease.status === 'DRAFT' ? '发布该课程版本' : '激活此历史版本'}
                </button>
              </div>
            )}
          </div>
        </section>
      </fieldset>
      {replacement && (
        <Dialog title="替换尚未保存的草稿" onClose={() => setReplacement(null)}>
          <h2>替换当前{draftNames[replacement.key]}？</h2>
          <p>这份表单仍有未提交的修改。继续会放弃这份草稿，其他表单的输入会保留。</p>
          <div className="row wrap">
            <button onClick={() => setReplacement(null)}>继续编辑当前草稿</button>
            <button
              className="danger-button"
              onClick={() => {
                const action = replacement.action;
                setReplacement(null);
                action();
              }}
            >
              放弃这份草稿并继续
            </button>
          </div>
        </Dialog>
      )}
    </div>
  );
}

function EditorialSource({
  value,
  index,
  sources,
  version,
  onChange,
  onRemove,
}: {
  value: SourceRef;
  index: number;
  sources: Source[];
  version: number;
  onChange: (value: SourceRef) => void;
  onRemove?: () => void;
}) {
  const captures = useResource<Capture[]>(
    value.sourceId ? `/admin/sources/${value.sourceId}/imports?limit=100` : null,
    version,
  );
  return (
    <div className="editor-citation">
      <div className="row spread">
        <strong>来源 {index + 1}</strong>
        {onRemove && (
          <button type="button" className="text-button danger-text" onClick={onRemove}>
            移除这条来源
          </button>
        )}
      </div>
      <Field label={`引用来源 ${index + 1}`}>
        <select
          required
          value={value.sourceId}
          onChange={(event) =>
            onChange({ ...value, sourceId: event.target.value, ingestionId: '' })
          }
        >
          <option value="">选择来源</option>
          {sources.map((source) => (
            <option key={source.id} value={source.id}>
              {source.title}
            </option>
          ))}
        </select>
      </Field>
      <Field label={`固定采集记录 ${index + 1}`}>
        <select
          required
          value={value.ingestionId}
          onChange={(event) => onChange({ ...value, ingestionId: event.target.value })}
        >
          <option value="">选择已保存的实际原稿</option>
          {value.ingestionId && !captures.data?.some((item) => item.id === value.ingestionId) && (
            <option value={value.ingestionId}>已固定的记录 · {value.ingestionId}</option>
          )}
          {captures.data?.map((item) => (
            <option key={item.id} value={item.id}>
              {new Date(item.createdAt).toLocaleDateString()} · {item.processingNote}
            </option>
          ))}
        </select>
      </Field>
      <ErrorNotice error={captures.error} retry={captures.reload} />
      <Field label={`引用章节 ${index + 1}`}>
        <input
          required
          maxLength={255}
          value={value.section}
          onChange={(event) => onChange({ ...value, section: event.target.value })}
        />
      </Field>
    </div>
  );
}

function ReviewSource({ value, sources }: { value: SourceRef; sources: Source[] }) {
  const [opened, setOpened] = useState(false);
  const source = sources.find((item) => item.id === value.sourceId);
  const captures = useResource<Capture[]>(
    opened ? `/admin/sources/${value.sourceId}/imports?limit=100` : null,
  );
  const capture = captures.data?.find((item) => item.id === value.ingestionId);
  return (
    <details className="review-source" onToggle={(event) => setOpened(event.currentTarget.open)}>
      <summary>
        {source?.title || '固定资料来源'} · {value.section}
      </summary>
      {source && (
        <>
          <p>
            {source.edition} · {source.usageNote}
          </p>
          {safeSourceUrl(source.url) && (
            <a href={safeSourceUrl(source.url)} target="_blank" rel="noreferrer">
              打开原始资料
            </a>
          )}
        </>
      )}
      <ErrorNotice error={captures.error} retry={captures.reload} />
      {captures.loading ? (
        <Loading />
      ) : capture ? (
        <>
          <h4>实际采集原稿</h4>
          <pre className="preserve-text">{capture.originalMarkdown}</pre>
          <h4>清洗稿与处理说明</h4>
          <pre className="preserve-text">{capture.cleanedMarkdown}</pre>
          <p>{capture.processingNote}</p>
          <dl className="metadata">
            <dt>原稿摘要</dt>
            <dd>{capture.originalHash}</dd>
            <dt>清洗稿摘要</dt>
            <dd>{capture.cleanedHash}</dd>
          </dl>
        </>
      ) : (
        opened &&
        !captures.error && (
          <p className="notice warning">当前列表没有这份固定采集记录，请核实原始记录后再审核。</p>
        )
      )}
    </details>
  );
}
