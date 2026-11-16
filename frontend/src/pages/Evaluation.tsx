import { useEffect, useMemo, useState } from 'react';
import {
  Archive,
  ArrowLeft,
  ArrowRight,
  BarChart3,
  Check,
  Download,
  Play,
  Plus,
  RefreshCw,
  Save,
  Square,
  Trash2,
  Undo2,
} from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, usePolling, useResource, useSessionDraft, useUnsavedChanges } from '../hooks';
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
import type { Concept, Evidence } from '../types';
import type {
  EvaluationCaseInput,
  EvaluationCaseResult,
  EvaluationMetrics,
  EvaluationRun,
  EvaluationSuite,
  EvaluationSuiteInput,
  EvaluationSuiteSummary,
  Page,
} from '../evaluation-types';

type SuiteDraft = EvaluationSuiteInput & { baseRevision: number; dirty: boolean };
const freshCase = (): EvaluationCaseInput => ({
  key: crypto.randomUUID(),
  query: '',
  conceptId: null,
  expectation: 'MATCH',
  expectedChunkIds: [],
  note: '',
});
const freshSuite = (): SuiteDraft => ({
  title: '',
  description: '',
  cases: [freshCase()],
  baseRevision: 0,
  dirty: false,
});
const fromSuite = (suite: EvaluationSuite): SuiteDraft => ({
  title: suite.title,
  description: suite.description,
  baseRevision: suite.revision,
  dirty: false,
  cases: suite.cases.map((item) => ({
    key: item.key,
    query: item.query,
    conceptId: item.conceptId,
    expectation: item.expectation,
    expectedChunkIds: item.expectedChunks.map((chunk) => chunk.chunkId),
    note: item.note,
  })),
});
const inFlight = (status?: string) => status === 'QUEUED' || status === 'RUNNING';
const percent = (value: number | null | undefined) =>
  value == null ? '—' : `${(value * 100).toFixed(1)}%`;

export function Evaluation() {
  const { workspace, session, selection, navigate, can, refresh } = useWorkspace();
  const enabled = can('EVALUATION_WORKBENCH');
  const [includeArchived, setIncludeArchived] = useState(false);
  const [version, setVersion] = useState(0);
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [page, setPage] = useState(0);
  const suites = useResource<Page<EvaluationSuiteSummary>>(
    enabled
      ? `/workspaces/${workspace.id}/evaluation-suites?limit=30&includeArchived=${includeArchived}${cursors[page] ? `&cursor=${cursors[page]}` : ''}`
      : null,
    version,
  );
  const suite = useResource<EvaluationSuite>(
    enabled && selection ? `/evaluation-suites/${encodeURIComponent(selection)}` : null,
    version,
  );
  const concepts = useResource<{ items: Concept[] }>(
    enabled ? `/concepts?releaseId=${workspace.releaseId}` : null,
  );
  const reload = () => {
    setVersion((v) => v + 1);
    refresh();
  };
  if (!enabled)
    return (
      <Empty title="当前环境尚未开启检索评测">
        该入口需要实际评测服务与持久运行结果。其他学习与检索功能仍可继续使用。
      </Empty>
    );
  const ownSuite = suite.data?.workspaceId === workspace.id ? suite.data : null;
  return (
    <div className="evaluation-page">
      <SectionTitle
        eyebrow="用固定样例核对真实检索"
        title="检索评测"
        description="把你期望找到的课程证据标出来，再运行同一份检索。评测集、内容版本和逐条结果都可追溯；这些是个人标注，不代表已经通过独立教学审核。"
        action={
          <button className="icon-button" aria-label="刷新检索评测" onClick={reload}>
            <RefreshCw size={18} />
          </button>
        }
      />
      <div className="evaluation-layout">
        <aside className="evaluation-suite-list">
          <button className="primary" onClick={() => navigate('evaluation')}>
            <Plus size={16} /> 新建评测集
          </button>
          <label className="check-row">
            <input
              type="checkbox"
              checked={includeArchived}
              onChange={(event) => {
                setIncludeArchived(event.target.checked);
                setCursors([null]);
                setPage(0);
              }}
            />{' '}
            显示已归档
          </label>
          <ErrorNotice error={suites.error} retry={suites.reload} />
          {suites.loading && !suites.data ? (
            <Loading />
          ) : suites.data?.items.length ? (
            <div className="evaluation-suite-items">
              {suites.data.items.map((item) => (
                <button
                  key={item.id}
                  className={`evaluation-suite-item ${selection === item.id ? 'selected' : ''}`}
                  aria-current={selection === item.id ? 'true' : undefined}
                  onClick={() => navigate('evaluation', item.id)}
                >
                  <strong>{item.title}</strong>
                  <span>
                    {item.caseCount} 个样例 · 版本 {item.revision}
                  </span>
                  {item.status === 'ARCHIVED' && <Badge>已归档</Badge>}
                </button>
              ))}
            </div>
          ) : (
            !suites.error && (
              <p className="muted small">
                当前范围还没有评测集。从一个实际查询和你期望的证据开始。
              </p>
            )
          )}
          <Pagination
            page={page}
            loading={suites.loading}
            hasNext={!!suites.data?.nextCursor}
            onPrevious={() => setPage((p) => p - 1)}
            onNext={() => {
              const next = suites.data?.nextCursor;
              if (next) {
                setCursors((values) => [...values.slice(0, page + 1), next]);
                setPage((p) => p + 1);
              }
            }}
          />
        </aside>
        <section className="evaluation-main" aria-label="评测集编辑与结果">
          <ErrorNotice error={concepts.error} retry={concepts.reload} />
          <ErrorNotice error={suite.error} retry={suite.reload} />
          {selection && suite.loading && !suite.data ? (
            <Loading />
          ) : selection && suite.data && !ownSuite ? (
            <Empty title="这个评测集不属于当前工作空间">
              <button onClick={() => navigate('evaluation')}>在当前空间新建</button>
            </Empty>
          ) : selection && !ownSuite ? null : ownSuite ? (
            <SavedSuite
              key={`${workspace.id}:${ownSuite.id}`}
              suite={ownSuite}
              concepts={concepts.data?.items || []}
              reload={reload}
            />
          ) : (
            <SuiteEditor
              key={`new:${workspace.id}`}
              draftKey={`tf.eval.${session.userId}.${workspace.id}.new`}
              suite={null}
              concepts={concepts.data?.items || []}
              onSaved={(created) => {
                setCursors([null]);
                setPage(0);
                reload();
                navigate('evaluation', created.id);
              }}
            />
          )}
        </section>
      </div>
    </div>
  );
}

function Pagination({
  page,
  loading,
  hasNext,
  onPrevious,
  onNext,
}: {
  page: number;
  loading: boolean;
  hasNext: boolean;
  onPrevious: () => void;
  onNext: () => void;
}) {
  if (page === 0 && !hasNext) return null;
  return (
    <nav className="list-pagination" aria-label="列表分页">
      <button
        className="icon-button"
        aria-label="上一页"
        disabled={loading || page === 0}
        onClick={onPrevious}
      >
        <ArrowLeft size={16} />
      </button>
      <span className="muted small">第 {page + 1} 页</span>
      <button
        className="icon-button"
        aria-label="下一页"
        disabled={loading || !hasNext}
        onClick={onNext}
      >
        <ArrowRight size={16} />
      </button>
    </nav>
  );
}

function SavedSuite({
  suite,
  concepts,
  reload,
}: {
  suite: EvaluationSuite;
  concepts: Concept[];
  reload: () => void;
}) {
  const { workspace, session } = useWorkspace();
  const [tab, setTab] = useState<'cases' | 'runs'>('cases');
  const [historyInput, setHistoryInput] = useState(String(suite.revision));
  const [historyRevision, setHistoryRevision] = useState(0);
  const historical = useResource<EvaluationSuite>(
    historyRevision && historyRevision !== suite.revision
      ? `/evaluation-suites/${suite.id}/revisions/${historyRevision}`
      : null,
  );
  const displayed = historyRevision && historyRevision !== suite.revision ? historical.data : suite;
  const history = !!historyRevision && historyRevision !== suite.revision;
  return (
    <>
      <div className="evaluation-suite-heading">
        <div>
          <h2>{suite.title}</h2>
          <p className="muted small">
            固定课程发布 · 当前版本 {suite.revision} · {suite.caseCount} 个样例
          </p>
        </div>
        <Badge>{suite.status === 'ARCHIVED' ? '已归档 · 历史保留' : '个人标注评测集'}</Badge>
      </div>
      <div className="segments evaluation-tabs" aria-label="评测集内容">
        <button aria-pressed={tab === 'cases'} onClick={() => setTab('cases')}>
          样例与版本
        </button>
        <button aria-pressed={tab === 'runs'} onClick={() => setTab('runs')}>
          运行与对照
        </button>
      </div>
      {tab === 'cases' ? (
        <>
          <details className="evaluation-provenance">
            <summary>版本与数据来源</summary>
            <dl>
              <dt>课程发布</dt>
              <dd>
                <code>{suite.releaseId}</code>
              </dd>
              <dt>语料摘要</dt>
              <dd>
                <code>{suite.corpusHash}</code>
              </dd>
              <dt>索引版本</dt>
              <dd>{suite.indexVersion}</dd>
              <dt>当前标注摘要</dt>
              <dd>
                <code>{suite.contentHash}</code>
              </dd>
            </dl>
            <div className="row wrap">
              <Field label="查看历史版本">
                <input
                  type="number"
                  min={1}
                  max={suite.revision}
                  value={historyInput}
                  onChange={(event) => setHistoryInput(event.target.value)}
                />
              </Field>
              <button
                disabled={
                  !Number.isInteger(Number(historyInput)) ||
                  Number(historyInput) < 1 ||
                  Number(historyInput) > suite.revision
                }
                onClick={() => setHistoryRevision(Number(historyInput))}
              >
                读取版本
              </button>
              {history && <button onClick={() => setHistoryRevision(0)}>回到当前版本</button>}
            </div>
          </details>
          <ErrorNotice error={historical.error} retry={historical.reload} />
          {historical.loading ? (
            <Loading />
          ) : (
            displayed && (
              <SuiteEditor
                key={`${suite.id}:${history ? historyRevision : 'current'}`}
                draftKey={`tf.eval.${session.userId}.${workspace.id}.${suite.id}.${history ? historyRevision : 'current'}`}
                suite={displayed}
                latestSuite={suite}
                readOnly={history || suite.status === 'ARCHIVED'}
                concepts={concepts}
                onSaved={reload}
              />
            )
          )}
        </>
      ) : (
        <EvaluationRuns suite={suite} />
      )}
    </>
  );
}

function SuiteEditor({
  draftKey,
  suite,
  latestSuite = suite,
  readOnly = false,
  concepts,
  onSaved,
}: {
  draftKey: string;
  suite: EvaluationSuite | null;
  latestSuite?: EvaluationSuite | null;
  readOnly?: boolean;
  concepts: Concept[];
  onSaved: (suite: EvaluationSuite) => void;
}) {
  const { workspace } = useWorkspace();
  const [draft, setDraft] = useSessionDraft<SuiteDraft>(
    draftKey,
    suite ? fromSuite(suite) : freshSuite(),
  );
  const [selectedKey, setSelectedKey] = useState(draft.cases[0]?.key || '');
  const [deleted, setDeleted] = useState<{ index: number; value: EvaluationCaseInput } | null>(
    null,
  );
  const [dialog, setDialog] = useState<'reset' | 'rebase' | 'archive' | null>(null);
  const [notice, setNotice] = useState('');
  const mutation = useMutation();
  useUnsavedChanges(draft.dirty && !readOnly);
  useEffect(() => {
    if (suite && !draft.dirty && draft.baseRevision !== suite.revision) setDraft(fromSuite(suite));
  }, [suite?.revision, draft.dirty, draft.baseRevision]);
  const patch = (value: Partial<SuiteDraft>) => {
    setDraft((current) => ({ ...current, ...value, dirty: true }));
    setNotice('');
  };
  const chosen = draft.cases.find((item) => item.key === selectedKey) || draft.cases[0];
  const allEvidence = useMemo(
    () => [
      ...new Map(
        concepts.flatMap((concept) => concept.evidence).map((item) => [item.chunkId, item]),
      ).values(),
    ],
    [concepts],
  );
  const updateCase = (value: Partial<EvaluationCaseInput>) =>
    patch({
      cases: draft.cases.map((item) => (item.key === chosen.key ? { ...item, ...value } : item)),
    });
  const invalid = draft.cases.find(
    (item) =>
      !item.query.trim() ||
      (item.expectation === 'MATCH' &&
        (!item.expectedChunkIds.length ||
          item.expectedChunkIds.length > 8 ||
          item.expectedChunkIds.some(
            (id) =>
              !allEvidence.some(
                (evidence) =>
                  evidence.chunkId === id &&
                  (!item.conceptId || evidence.conceptId === item.conceptId),
              ),
          ))),
  );
  const conflict = !!suite && draft.baseRevision !== suite.revision;
  const save = () =>
    mutation.run(
      () =>
        api.request<EvaluationSuite>(
          suite
            ? `/evaluation-suites/${suite.id}/revisions`
            : `/workspaces/${workspace.id}/evaluation-suites`,
          'POST',
          {
            title: draft.title,
            description: draft.description,
            cases: draft.cases.map((item) => ({
              ...item,
              expectedChunkIds: item.expectation === 'NO_RESULTS' ? [] : item.expectedChunkIds,
            })),
            ...(suite ? { expectedRevision: draft.baseRevision } : {}),
          },
          { idempotent: true },
        ),
      (saved) => {
        setDraft(suite ? fromSuite(saved) : freshSuite());
        setDeleted(null);
        setNotice(`服务端已保存评测集版本 ${saved.revision}。`);
        onSaved(saved);
      },
    );
  const archived = suite?.status === 'ARCHIVED';
  const knownSelected = (chosen?.expectedChunkIds || [])
    .map((id) => allEvidence.find((item) => item.chunkId === id))
    .filter((item): item is Evidence => !!item);
  if (readOnly && suite)
    return (
      <section className="panel evaluation-readonly">
        <div className="row spread wrap">
          <h2>评测集版本 {suite.revision}</h2>
          <Badge>只读 · {suite.cases.length} 个样例</Badge>
        </div>
        <h3>{suite.title}</h3>
        <p className="preserve-text">{suite.description}</p>
        {suite.cases.map((item, index) => (
          <details key={item.key} open={index === 0}>
            <summary>
              {String(index + 1).padStart(2, '0')} · {item.query}
            </summary>
            <p className="muted small">
              {item.conceptId
                ? concepts.find((concept) => concept.id === item.conceptId)?.title || '固定概念范围'
                : '当前课程全部概念'}{' '}
              · {item.expectation === 'MATCH' ? '期望命中' : '期望无结果'}
            </p>
            <ul>
              {item.expectedChunks.map((chunk) => (
                <li key={chunk.chunkId}>
                  {chunk.title} · 知识版本 {chunk.revision}
                </li>
              ))}
            </ul>
            <p className="preserve-text">{item.note}</p>
          </details>
        ))}
      </section>
    );
  return (
    <section className="evaluation-editor panel">
      <div className="row spread wrap">
        <h2>
          {readOnly
            ? `查看评测集版本 ${suite?.revision}`
            : suite
              ? '维护你的检索样例'
              : '创建个人评测集'}
        </h2>
        <Badge>
          {readOnly ? '只读' : draft.dirty ? '有未保存修改' : suite ? '与所读版本同步' : '新草稿'}
        </Badge>
      </div>
      {conflict && !readOnly && (
        <div className="notice warning">
          <div>
            <strong>
              服务端已有版本 {suite.revision}，你的草稿仍基于版本 {draft.baseRevision}
            </strong>
            <p>先查看当前版本的标注，再决定是否沿用这份草稿。保存不会自动覆盖新版本。</p>
            <details>
              <summary>查看服务端当前样例</summary>
              <ul>
                {suite.cases.map((item) => (
                  <li key={item.key}>
                    {item.query} ·{' '}
                    {item.expectation === 'MATCH'
                      ? `${item.expectedChunks.length} 条期望证据`
                      : '期望无结果'}
                  </li>
                ))}
              </ul>
            </details>
            <button onClick={() => setDialog('rebase')}>保留草稿，更新保存基线</button>
          </div>
        </div>
      )}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (!invalid && !readOnly && !conflict && !mutation.busy && draft.title.trim())
            void save();
        }}
      >
        <fieldset className="editor-fieldset" disabled={readOnly || mutation.busy}>
          <Field label="评测集标题">
            <input
              required
              maxLength={120}
              value={draft.title}
              onChange={(event) => patch({ title: event.target.value })}
            />
          </Field>
          <Field label="标注目的与范围">
            <textarea
              maxLength={2000}
              rows={2}
              value={draft.description}
              onChange={(event) => patch({ description: event.target.value })}
              placeholder="例如：核对 PECS 相关问法能否找到当前课程的对应依据。"
            />
          </Field>
          <div className="evaluation-cases-layout">
            <div className="evaluation-case-navigation">
              <div className="row spread">
                <strong>查询样例</strong>
                <small>{draft.cases.length} / 40</small>
              </div>
              <div className="evaluation-case-buttons">
                {draft.cases.map((item, index) => (
                  <button
                    type="button"
                    key={item.key}
                    aria-pressed={item.key === chosen?.key}
                    className={item.key === chosen?.key ? 'selected' : ''}
                    onClick={() => setSelectedKey(item.key)}
                  >
                    <span>{String(index + 1).padStart(2, '0')}</span>
                    <span>
                      {item.query || '未填写查询'}
                      <small>
                        {item.expectation === 'NO_RESULTS'
                          ? '期望无结果'
                          : `${item.expectedChunkIds.length} 条期望证据`}
                      </small>
                    </span>
                  </button>
                ))}
              </div>
              <button
                type="button"
                disabled={draft.cases.length >= 40}
                onClick={() => {
                  const next = freshCase();
                  patch({ cases: [...draft.cases, next] });
                  setSelectedKey(next.key);
                }}
              >
                <Plus size={15} /> 添加样例
              </button>
            </div>
            {chosen && (
              <div className="evaluation-case-editor">
                <Field label="实际检索查询">
                  <textarea
                    required
                    maxLength={500}
                    rows={3}
                    value={chosen.query}
                    onChange={(event) => updateCase({ query: event.target.value })}
                  />
                </Field>
                <div className="form-grid">
                  <Field label="限定概念">
                    <select
                      value={chosen.conceptId || ''}
                      onChange={(event) => updateCase({ conceptId: event.target.value || null })}
                    >
                      <option value="">当前课程全部概念</option>
                      {concepts.map((concept) => (
                        <option key={concept.id} value={concept.id}>
                          {concept.code} · {concept.title}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="期望结果">
                    <select
                      value={chosen.expectation}
                      onChange={(event) =>
                        updateCase({
                          expectation: event.target.value as EvaluationCaseInput['expectation'],
                        })
                      }
                    >
                      <option value="MATCH">应找到指定证据</option>
                      <option value="NO_RESULTS">应返回空结果</option>
                    </select>
                  </Field>
                </div>
                {chosen.expectation === 'MATCH' ? (
                  <fieldset className="expected-evidence">
                    <legend>期望命中的课程证据 · 选择 1 至 8 条</legend>
                    <div className="expected-evidence-list">
                      {allEvidence
                        .filter((item) => !chosen.conceptId || item.conceptId === chosen.conceptId)
                        .map((item) => (
                          <label className="expected-evidence-option" key={item.chunkId}>
                            <input
                              type="checkbox"
                              checked={chosen.expectedChunkIds.includes(item.chunkId)}
                              disabled={
                                !chosen.expectedChunkIds.includes(item.chunkId) &&
                                chosen.expectedChunkIds.length >= 8
                              }
                              onChange={(event) =>
                                updateCase({
                                  expectedChunkIds: event.target.checked
                                    ? [...chosen.expectedChunkIds, item.chunkId]
                                    : chosen.expectedChunkIds.filter((id) => id !== item.chunkId),
                                })
                              }
                            />
                            <span>
                              <strong>{item.title}</strong>
                              <small>
                                知识版本 {item.revision} · {item.excerpt.slice(0, 110)}
                                {item.excerpt.length > 110 ? '…' : ''}
                              </small>
                            </span>
                          </label>
                        ))}
                    </div>
                    {chosen.expectedChunkIds.length > 0 && (
                      <div className="expected-selected">
                        <span className="muted small">已选择</span>
                        {chosen.expectedChunkIds.map((id) => (
                          <button
                            key={id}
                            type="button"
                            className="text-button small"
                            onClick={() =>
                              updateCase({
                                expectedChunkIds: chosen.expectedChunkIds.filter(
                                  (item) => item !== id,
                                ),
                              })
                            }
                          >
                            {knownSelected.find((item) => item.chunkId === id)?.title ||
                              '当前发布未找到这条证据'}{' '}
                            <span aria-label="移除">×</span>
                          </button>
                        ))}
                      </div>
                    )}
                    {chosen.conceptId &&
                      knownSelected.some((item) => item.conceptId !== chosen.conceptId) && (
                        <p className="field-error">
                          部分已选证据不属于当前限定概念，请移除或调整概念范围。
                        </p>
                      )}
                  </fieldset>
                ) : (
                  <p className="notice">
                    这条样例只核对检索是否返回空列表，不使用隐藏答案或模型裁判。
                  </p>
                )}
                <Field label="这条标注的理由">
                  <textarea
                    maxLength={1000}
                    rows={2}
                    value={chosen.note}
                    onChange={(event) => updateCase({ note: event.target.value })}
                  />
                </Field>
                <button
                  type="button"
                  className="text-button danger-text"
                  disabled={draft.cases.length === 1}
                  onClick={() => {
                    const index = draft.cases.findIndex((item) => item.key === chosen.key);
                    setDeleted({ index, value: chosen });
                    const remaining = draft.cases.filter((item) => item.key !== chosen.key);
                    patch({ cases: remaining });
                    setSelectedKey(remaining[Math.min(index, remaining.length - 1)].key);
                  }}
                >
                  <Trash2 size={15} /> 从草稿移除这条样例
                </button>
              </div>
            )}
          </div>
        </fieldset>
        {deleted && !readOnly && (
          <div className="row wrap notice">
            <span>已从当前草稿移除一个样例。</span>
            <button
              type="button"
              disabled={mutation.busy || draft.cases.length >= 40}
              onClick={() => {
                const next = [...draft.cases];
                next.splice(deleted.index, 0, deleted.value);
                patch({ cases: next });
                setSelectedKey(deleted.value.key);
                setDeleted(null);
              }}
            >
              <Undo2 size={15} /> 撤销移除
            </button>
          </div>
        )}
        <ErrorNotice error={mutation.error} />
        {!!mutation.error && suite && (
          <button type="button" onClick={() => onSaved(suite)}>
            读取最新版本并保留草稿
          </button>
        )}
        {notice && <Confirmed>{notice}</Confirmed>}
        {!readOnly && (
          <div className="evaluation-save-row">
            <div>
              <p className="muted small">保存整版标注；历史版本和既有运行不会被改写。</p>
              {invalid && (
                <p className="muted small">还有样例未填写查询，或缺少当前概念下的有效期望证据。</p>
              )}
            </div>
            <button
              className="primary"
              disabled={
                mutation.busy ||
                !draft.title.trim() ||
                !!invalid ||
                conflict ||
                (!draft.dirty && !!suite)
              }
            >
              <Save size={16} /> {mutation.busy ? '正在保存…' : '保存评测集'}
            </button>
          </div>
        )}
      </form>
      <div className="evaluation-editor-footer">
        {!readOnly && draft.dirty && (
          <button
            className="text-button"
            disabled={mutation.busy}
            onClick={() => setDialog('reset')}
          >
            恢复到已保存内容
          </button>
        )}
        {suite && !archived && !readOnly && (
          <button
            className="text-button"
            disabled={mutation.busy}
            onClick={() => setDialog('archive')}
          >
            <Archive size={15} /> 归档评测集
          </button>
        )}
      </div>
      {dialog && (
        <Dialog
          title={
            dialog === 'archive'
              ? '归档这个评测集'
              : dialog === 'rebase'
                ? '使用新版本作为保存基线'
                : '恢复草稿'
          }
          onClose={() => {
            if (!mutation.busy) setDialog(null);
          }}
        >
          <h2>
            {dialog === 'archive'
              ? '归档这个评测集？'
              : dialog === 'rebase'
                ? '保留当前草稿并更新基线？'
                : '放弃当前未保存修改？'}
          </h2>
          <p>
            {dialog === 'archive'
              ? '归档会取消该评测集未完成的运行，历史版本与结果保留；归档后不能再编辑或发起运行。'
              : dialog === 'rebase'
                ? '接下来的保存会以这份完整草稿创建新版本。请先核对服务端当前样例，确保没有遗漏其他页面保存的修改。'
                : '将恢复为当前所读的服务端版本；尚未保存的本地修改会被替换。'}
          </p>
          {dialog === 'archive' && draft.dirty && (
            <p className="notice warning">当前仍有未保存修改，归档后这些修改无法提交。</p>
          )}
          <ErrorNotice error={mutation.error} />
          <div className="row wrap">
            <button disabled={mutation.busy} onClick={() => setDialog(null)}>
              继续编辑
            </button>
            <button
              className="primary"
              disabled={mutation.busy}
              onClick={() => {
                if (dialog === 'archive' && latestSuite)
                  void mutation.run(
                    () =>
                      api.request<EvaluationSuite>(
                        `/evaluation-suites/${latestSuite.id}/archive`,
                        'POST',
                        { expectedRevision: latestSuite.revision },
                      ),
                    (archivedSuite) => {
                      setDialog(null);
                      setDraft(fromSuite(archivedSuite));
                      onSaved(archivedSuite);
                    },
                  );
                else if (dialog === 'rebase' && suite) {
                  patch({ baseRevision: suite.revision });
                  setDialog(null);
                } else {
                  setDraft(suite ? fromSuite(suite) : freshSuite());
                  setDeleted(null);
                  setDialog(null);
                }
              }}
            >
              {mutation.busy
                ? '正在处理…'
                : dialog === 'archive'
                  ? '确认归档'
                  : dialog === 'rebase'
                    ? '保留草稿并更新基线'
                    : '恢复内容'}
            </button>
          </div>
        </Dialog>
      )}
    </section>
  );
}

function EvaluationRuns({ suite }: { suite: EvaluationSuite }) {
  const { session, workspace } = useWorkspace();
  const [tick, setTick] = useState(0);
  const [topK, setTopK] = useState(5);
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useSessionDraft(
    `tf.eval-run.${session.userId}.${workspace.id}.${suite.id}`,
    '',
  );
  const [compare, setCompare] = useState('');
  const [caseKey, setCaseKey] = useState('');
  const [onlyFailures, setOnlyFailures] = useState(false);
  const mutation = useMutation();
  const runs = useResource<Page<EvaluationRun>>(
    `/evaluation-suites/${suite.id}/runs?limit=30${cursors[page] ? `&cursor=${cursors[page]}` : ''}`,
    tick,
  );
  const run = useResource<EvaluationRun>(selected ? `/evaluation-runs/${selected}` : null, tick);
  const baseline = useResource<EvaluationRun>(compare ? `/evaluation-runs/${compare}` : null);
  useEffect(() => {
    if (!selected && runs.data?.items.length) setSelected(runs.data.items[0].id);
  }, [runs.data, selected]);
  const active =
    inFlight(run.data?.status) || !!runs.data?.items.some((item) => inFlight(item.status));
  usePolling(
    active,
    () => setTick((v) => v + 1),
    runs.loading || run.loading,
    !!runs.error || !!run.error,
    1200,
  );
  const displayed =
    run.data?.suiteId === suite.id && run.data.workspaceId === workspace.id ? run.data : null;
  const rows = displayed?.results.filter((item) => !onlyFailures || !item.passed) || [];
  const chosen = rows.find((item) => item.caseKey === caseKey) || rows[0];
  const start = () =>
    mutation.run(
      () =>
        api.request<EvaluationRun>(
          `/evaluation-suites/${suite.id}/runs`,
          'POST',
          { suiteRevision: suite.revision, topK },
          { idempotent: true },
        ),
      (created) => {
        setSelected(created.id);
        setCompare('');
        setCursors([null]);
        setPage(0);
        setTick((v) => v + 1);
      },
    );
  return (
    <section className="evaluation-runs">
      <div className="evaluation-run-controls panel">
        <div>
          <h2>
            <BarChart3 size={19} /> 运行固定版本的检索
          </h2>
          <p className="muted small">
            使用已保存的版本 {suite.revision}，共 {suite.caseCount}{' '}
            个样例。未保存草稿不参与本次运行。
          </p>
        </div>
        <Field label="检索前 K 条">
          <select
            value={topK}
            onChange={(event) => setTopK(Number(event.target.value))}
            disabled={mutation.busy}
          >
            {Array.from({ length: 8 }, (_, index) => (
              <option key={index + 1} value={index + 1}>
                前 {index + 1} 条
              </option>
            ))}
          </select>
        </Field>
        <button
          className="primary"
          disabled={mutation.busy || active || suite.status !== 'ACTIVE'}
          onClick={() => void start()}
        >
          <Play size={16} />{' '}
          {mutation.busy ? '正在提交…' : active ? '已有运行进行中' : '开始检索评测'}
        </button>
      </div>
      <ErrorNotice error={mutation.error} />
      <ErrorNotice error={runs.error} retry={runs.reload} />
      <div className="evaluation-run-selection">
        <Field label="查看运行记录">
          <select
            value={selected}
            onChange={(event) => {
              setSelected(event.target.value);
              setCaseKey('');
              setCompare('');
            }}
          >
            <option value="">选择一次实际运行</option>
            {selected && !runs.data?.items.some((item) => item.id === selected) && (
              <option value={selected}>
                {displayed
                  ? `当前选择 · 版本 ${displayed.suiteRevision} · K=${displayed.topK} · ${displayed.status}`
                  : '正在读取所选运行'}
              </option>
            )}
            {runs.data?.items.map((item) => (
              <option key={item.id} value={item.id}>
                {new Date(item.createdAt).toLocaleString('zh-CN')} · v{item.suiteRevision} · K=
                {item.topK} · {item.status}
              </option>
            ))}
          </select>
        </Field>
        <Pagination
          page={page}
          loading={runs.loading}
          hasNext={!!runs.data?.nextCursor}
          onPrevious={() => setPage((p) => p - 1)}
          onNext={() => {
            if (runs.data?.nextCursor) {
              setCursors((values) => [...values.slice(0, page + 1), runs.data!.nextCursor]);
              setPage((p) => p + 1);
            }
          }}
        />
      </div>
      <ErrorNotice error={run.error} retry={run.reload} />
      {run.loading && !run.data ? (
        <Loading />
      ) : displayed ? (
        <>
          <div className="evaluation-run-status panel">
            <div className="row spread wrap">
              <h2>
                评测集版本 {displayed.suiteRevision} · 前 {displayed.topK} 条
              </h2>
              <Status value={displayed.status} />
            </div>
            <p aria-live="polite">
              已处理 {displayed.completedCases} / {displayed.caseCount} 个样例
              {inFlight(displayed.status) ? '，运行进度由服务端确认。' : '。'}
            </p>
            {inFlight(displayed.status) && (
              <button
                disabled={mutation.busy}
                onClick={() =>
                  void mutation.run(
                    () =>
                      api.request<EvaluationRun>(`/evaluation-runs/${displayed.id}/cancel`, 'POST'),
                    () => setTick((v) => v + 1),
                  )
                }
              >
                <Square size={14} /> 取消这次运行
              </button>
            )}
            {displayed.errorCode && (
              <p className="notice error">
                本次运行未完成：{displayed.errorCode}。基础设施失败不会被计为检索样例判错。
              </p>
            )}
            {displayed.status === 'CANCELLED' && (
              <p className="muted small">
                已取消，保留此前实际产生的逐例结果，不计算完整评测指标。
              </p>
            )}
            {displayed.status === 'COMPLETED' && displayed.metrics && (
              <>
                <MetricSummary metrics={displayed.metrics} topK={displayed.topK} />
                <div className="row spread wrap">
                  <p className="muted small">
                    {displayed.metrics.passedCases} 项满足个人标注，{displayed.metrics.failedCases}{' '}
                    项需回看。实际检索累计耗时 {displayed.metrics.totalElapsedMillis}{' '}
                    ms，不是生产性能 SLO。
                  </p>
                  {displayed.reportPath === `/api/v1/evaluation-runs/${displayed.id}/report` && (
                    <a className="button" href={displayed.reportPath} download>
                      <Download size={16} /> 下载真实 JSON 报告
                    </a>
                  )}
                </div>
              </>
            )}
            <details className="evaluation-provenance">
              <summary>本次运行的固定版本与摘要</summary>
              <dl>
                <dt>运行编号</dt>
                <dd>
                  <code>{displayed.id}</code>
                </dd>
                <dt>标注摘要</dt>
                <dd>
                  <code>{displayed.suiteHash}</code>
                </dd>
                <dt>语料摘要</dt>
                <dd>
                  <code>{displayed.corpusHash}</code>
                </dd>
                <dt>判定版本</dt>
                <dd>{displayed.evaluatorVersion}</dd>
                <dt>结果摘要</dt>
                <dd>
                  <code>{displayed.resultHash || '结果尚未冻结'}</code>
                </dd>
              </dl>
            </details>
          </div>
          {displayed.status === 'COMPLETED' && (
            <section className="panel evaluation-comparison">
              <Field label="与另一份已完成运行对照">
                <select value={compare} onChange={(event) => setCompare(event.target.value)}>
                  <option value="">不对照</option>
                  {runs.data?.items
                    .filter((item) => item.status === 'COMPLETED' && item.id !== displayed.id)
                    .map((item) => (
                      <option key={item.id} value={item.id}>
                        {new Date(item.createdAt).toLocaleString('zh-CN')} · v{item.suiteRevision} ·
                        K={item.topK}
                      </option>
                    ))}
                </select>
              </Field>
              <ErrorNotice error={baseline.error} retry={baseline.reload} />
              {baseline.loading ? (
                <Loading />
              ) : (
                baseline.data?.metrics &&
                baseline.data.suiteId === suite.id &&
                displayed.metrics && (
                  <>
                    <p className="notice">
                      {baseline.data.suiteHash === displayed.suiteHash &&
                      baseline.data.corpusHash === displayed.corpusHash
                        ? '两次使用相同标注与语料，可以对照不同 K 值下的实际检索结果。'
                        : '两次的标注或语料版本不同，指标差异不能单独解释为检索能力提升。'}
                    </p>
                    <MetricComparison
                      current={displayed.metrics}
                      baseline={baseline.data.metrics}
                    />
                  </>
                )
              )}
            </section>
          )}
          <section className="panel evaluation-results">
            <div className="row spread wrap">
              <h2>逐条核对结果</h2>
              <label className="check-row">
                <input
                  type="checkbox"
                  checked={onlyFailures}
                  onChange={(event) => setOnlyFailures(event.target.checked)}
                />{' '}
                只看未满足标注的样例
              </label>
            </div>
            {rows.length ? (
              <div className="evaluation-result-layout">
                <nav aria-label="评测样例结果">
                  {rows.map((item, index) => (
                    <button
                      key={item.caseKey}
                      aria-pressed={chosen?.caseKey === item.caseKey}
                      className={chosen?.caseKey === item.caseKey ? 'selected' : ''}
                      onClick={() => setCaseKey(item.caseKey)}
                    >
                      <span>
                        {item.passed ? (
                          <Check size={15} />
                        ) : (
                          <span className="result-miss">需回看</span>
                        )}
                      </span>
                      <span>
                        {item.query}
                        <small>
                          {item.expectation === 'NO_RESULTS'
                            ? '期望空结果'
                            : `${item.relevantCount} / ${item.expectedChunkIds.length} 条期望证据`}{' '}
                          · 实际 {item.hits.length} 条
                        </small>
                      </span>
                    </button>
                  ))}
                </nav>
                {chosen && <CaseResult key={chosen.caseKey} result={chosen} />}
              </div>
            ) : (
              <Empty
                title={
                  onlyFailures
                    ? '当前已返回结果中没有未满足的样例'
                    : inFlight(displayed.status)
                      ? '等待第一条真实结果'
                      : '这次没有逐条结果'
                }
              >
                {inFlight(displayed.status)
                  ? '运行仍在进行，完整结论会在服务端结束后显示。'
                  : '筛选只影响展示，不改变原始评测记录。'}
              </Empty>
            )}
          </section>
        </>
      ) : (
        !run.error && <Empty title="还没有选择运行结果">先保存标注，再运行同一份课程检索。</Empty>
      )}
    </section>
  );
}

const metricRows: [keyof EvaluationMetrics, string][] = [
  ['meanPrecisionAtK', '平均 Precision@K'],
  ['meanRecallAtK', '平均 Recall@K'],
  ['meanReciprocalRank', '平均倒数排名 MRR'],
  ['hitRateAtK', '至少命中一条的比例'],
  ['noResultAccuracy', '空结果符合率'],
];
function MetricSummary({ metrics, topK }: { metrics: EvaluationMetrics; topK: number }) {
  return (
    <>
      <div className="evaluation-metrics">
        {metricRows.map(([key, label]) => (
          <div key={key}>
            <span>{label}</span>
            <strong>
              {key === 'meanReciprocalRank'
                ? metrics[key] == null
                  ? '—'
                  : Number(metrics[key]).toFixed(3)
                : percent(metrics[key])}
            </strong>
          </div>
        ))}
      </div>
      <details className="metric-explanation">
        <summary>这些数字如何计算</summary>
        <p>
          命中类样例 {metrics.matchCases} 条，空结果类 {metrics.noResultCases} 条。Precision@{topK}{' '}
          = 命中的期望证据数 ÷ {topK}；Recall = 命中数 ÷ 标注证据数；MRR =
          首条命中排名的倒数，无命中为 0。前三项只对命中类样例求平均。没有对应样例的指标显示“—”。
        </p>
        <p>只有全部期望证据都被返回，命中类样例才满足标注。空结果类必须实际返回空列表。</p>
      </details>
    </>
  );
}
function MetricComparison({
  current,
  baseline,
}: {
  current: EvaluationMetrics;
  baseline: EvaluationMetrics;
}) {
  return (
    <div
      className="table-scroll evaluation-metric-table"
      tabIndex={0}
      role="region"
      aria-label="两次检索指标对照"
    >
      <table>
        <thead>
          <tr>
            <th>指标</th>
            <th>对照运行</th>
            <th>当前运行</th>
            <th>差值</th>
          </tr>
        </thead>
        <tbody>
          {metricRows.map(([key, label]) => {
            const a = baseline[key],
              b = current[key];
            return (
              <tr key={key}>
                <td>{label}</td>
                <td>{a == null ? '—' : Number(a).toFixed(3)}</td>
                <td>{b == null ? '—' : Number(b).toFixed(3)}</td>
                <td>
                  {a == null || b == null
                    ? '—'
                    : `${Number(b) - Number(a) >= 0 ? '+' : ''}${(Number(b) - Number(a)).toFixed(3)}`}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
function CaseResult({ result }: { result: EvaluationCaseResult }) {
  return (
    <article className="evaluation-result-detail">
      <div className="row spread wrap">
        <h3>{result.query}</h3>
        <Badge tone={result.passed ? 'good' : 'warn'}>
          {result.passed ? '满足标注' : '需要回看'}
        </Badge>
      </div>
      <p className="muted small">
        {result.expectation === 'NO_RESULTS'
          ? '期望没有检索结果。'
          : `期望 ${result.expectedChunkIds.length} 条证据，实际命中 ${result.relevantCount} 条。`}{' '}
        实际检索耗时 {result.elapsedMillis} ms。
      </p>
      {result.expectation === 'MATCH' && (
        <p className="muted small">
          Precision：{percent(result.precisionAtK)} · Recall：{percent(result.recallAtK)} ·
          首条命中：{result.firstRelevantRank ? `第 ${result.firstRelevantRank} 条` : '无'}
        </p>
      )}
      {result.hits.length ? (
        <ol className="evaluation-hits">
          {result.hits.map((hit) => (
            <li key={`${hit.chunkId}:${hit.revision}`}>
              <div className="row spread wrap">
                <strong>
                  #{hit.rank} · {hit.title}
                </strong>
                <Badge tone={hit.relevant ? 'good' : 'neutral'}>
                  {hit.relevant ? '命中期望' : '未标为期望'}
                </Badge>
              </div>
              <p className="preserve-text">{hit.excerpt}</p>
              <small className="muted">
                知识版本 {hit.revision} · 来源：{hit.source.title} · {hit.sourceSection}
              </small>
            </li>
          ))}
        </ol>
      ) : (
        <p className="notice">服务端这次确实返回了空结果。</p>
      )}
      {result.expectedChunkIds.some((id) => !result.hits.some((hit) => hit.chunkId === id)) && (
        <details>
          <summary>未命中的期望证据编号</summary>
          <ul>
            {result.expectedChunkIds
              .filter((id) => !result.hits.some((hit) => hit.chunkId === id))
              .map((id) => (
                <li key={id}>
                  <code>{id}</code>
                </li>
              ))}
          </ul>
        </details>
      )}
    </article>
  );
}
