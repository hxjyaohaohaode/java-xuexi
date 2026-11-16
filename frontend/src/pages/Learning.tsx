import { useEffect, useState } from 'react';
import { ArrowLeft, ArrowRight, BookOpen, Check, GitBranch, Search } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource, useSessionDraft } from '../hooks';
import {
  Badge,
  Confirmed,
  Empty,
  ErrorNotice,
  EvidenceList,
  Loading,
  SectionTitle,
} from '../components/ui';
import type { Concept, Evidence } from '../types';
import { ConceptMap } from '../components/ConceptMap';

export function Learning() {
  const { workspace, session, tick, events, navigate, selection, can } = useWorkspace();
  const concepts = useResource<{ releaseId: string; items: Concept[] }>(
    `/concepts?releaseId=${workspace.releaseId}`,
  );
  const [query, setQuery] = useSessionDraft(`tf.search.${session.userId}.${workspace.id}`, '');
  const [submitted, setSubmitted] = useState('');
  const [view, setView] = useSessionDraft<'reading' | 'map'>(
    `tf.learning-view.${session.userId}.${workspace.id}`,
    'reading',
  );
  const [read, setRead] = useSessionDraft<string[]>(
    `tf.read.${session.userId}.${workspace.id}`,
    [],
  );
  useEffect(() => {
    const confirmed = events
      .filter((e) => e.type === 'READ_COMPLETED' && typeof e.data.conceptId === 'string')
      .map((e) => String(e.data.conceptId));
    if (confirmed.some((id) => !read.includes(id)))
      setRead((previous) => [...new Set([...previous, ...confirmed])]);
  }, [events, read]);
  const mutation = useMutation();
  const readings = useResource<{
    items: { conceptId: string; completed: boolean; lastCompletedAt: string | null }[];
  }>(can('READING_HISTORY') ? `/workspaces/${workspace.id}/readings` : null, tick);
  useEffect(() => {
    const confirmed =
      readings.data?.items.filter((item) => item.completed).map((item) => item.conceptId) || [];
    if (confirmed.some((id) => !read.includes(id)))
      setRead((previous) => [...new Set([...previous, ...confirmed])]);
  }, [readings.data, read]);
  const search = useResource<{
    items: Evidence[];
    insufficientEvidence: boolean;
    indexVersion: string;
  }>(
    submitted
      ? `/knowledge/search?releaseId=${workspace.releaseId}&q=${encodeURIComponent(submitted)}&topK=8`
      : null,
  );
  const chosen = concepts.data?.items.find((c) => c.id === selection) || concepts.data?.items[0];
  const recordRead = () =>
    chosen &&
    mutation.run(
      () =>
        api.request(
          '/learning-events',
          'POST',
          {
            workspaceId: workspace.id,
            releaseId: workspace.releaseId,
            conceptId: chosen.id,
            eventType: 'READ_COMPLETED',
          },
          { idempotent: true },
        ),
      () => setRead((p) => [...new Set([...p, chosen.id])]),
    );
  return (
    <>
      <SectionTitle
        eyebrow="学习从一条明确的规则开始"
        title="泛型与类型安全"
        description="先建立类型边界，再用具体题目检验理解。每张知识卡都保留资料出处与课程版本。"
      />
      <form
        className="search-form"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          setSubmitted(query.trim());
        }}
      >
        <Search size={19} />
        <input
          aria-label="搜索知识"
          placeholder="搜索一个概念、类型或规则，例如 PECS"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          maxLength={500}
        />
        <button type="submit" disabled={!query.trim() || search.loading}>
          检索
        </button>
        {submitted && (
          <button
            type="button"
            className="text-button"
            onClick={() => {
              setSubmitted('');
              setQuery('');
            }}
          >
            返回课程
          </button>
        )}
      </form>
      <ErrorNotice error={concepts.error} retry={concepts.reload} />
      <ErrorNotice error={readings.error} retry={readings.reload} />
      {!submitted && !!concepts.data?.items.length && (
        <div className="segments learning-view-switch" aria-label="课程浏览方式">
          <button aria-pressed={view === 'reading'} onClick={() => setView('reading')}>
            <BookOpen size={16} /> 课程阅读
          </button>
          <button aria-pressed={view === 'map'} onClick={() => setView('map')}>
            <GitBranch size={16} /> 概念路径
          </button>
        </div>
      )}
      {submitted ? (
        <section className="search-results">
          <div className="row spread">
            <h2>“{submitted}”的课程证据</h2>
            <Badge>本地词项检索</Badge>
          </div>
          {search.loading ? (
            <Loading />
          ) : search.error ? (
            <ErrorNotice error={search.error} retry={search.reload} />
          ) : search.data?.items.length ? (
            <EvidenceList items={search.data.items} />
          ) : (
            <Empty title="当前课程中没有匹配证据">
              试试更具体的术语；系统不会用无关资料填补结果。
            </Empty>
          )}
        </section>
      ) : concepts.loading ? (
        <Loading />
      ) : chosen && view === 'map' ? (
        <ConceptMap
          concepts={concepts.data!.items}
          selectedId={chosen.id}
          completed={read}
          onRead={(id) => {
            setView('reading');
            navigate('learn', id);
          }}
        />
      ) : chosen ? (
        <div className="reading-layout">
          <aside className="concept-outline" aria-label="概念目录">
            <div className="outline-heading">
              课程目录 <span>{concepts.data!.items.length} 个概念</span>
            </div>
            {concepts.data!.items.map((c) => (
              <button
                key={c.id}
                className={c.id === chosen.id ? 'selected' : ''}
                aria-current={c.id === chosen.id ? 'true' : undefined}
                onClick={() => navigate('learn', c.id)}
              >
                <span className="concept-code">{c.code}</span>
                <span>{c.title}</span>
                {read.includes(c.id) && <Check size={14} />}
              </button>
            ))}
          </aside>
          <article className="reading-main" key={chosen.id}>
            <div className="row spread">
              <span className="concept-number">{chosen.code}</span>
              <Badge>已发布课程</Badge>
            </div>
            <h2>{chosen.title}</h2>
            {chosen.prerequisiteIds.length > 0 && (
              <div className="prerequisites">
                <span>建议先读</span>
                {chosen.prerequisiteIds.map((id) => {
                  const parent = concepts.data!.items.find((c) => c.id === id);
                  return (
                    <button className="text-button" key={id} onClick={() => navigate('learn', id)}>
                      {parent?.title || id}
                    </button>
                  );
                })}
              </div>
            )}
            <button className="text-button small path-backlink" onClick={() => setView('map')}>
              <GitBranch size={15} /> 查看前置与后续路径
            </button>
            <EvidenceList items={chosen.evidence} />
            <div className="reading-actions">
              <div>
                <strong>把规则变成自己的判断。</strong>
                <p className="muted">阅读记录不会直接增加掌握度；作答会提供独立证据。</p>
              </div>
              <div className="row">
                <button
                  onClick={() => void recordRead()}
                  disabled={!can('READING_EVENTS') || mutation.busy || read.includes(chosen.id)}
                >
                  {read.includes(chosen.id) ? '已记录阅读' : '记录已读'}
                </button>
                <button className="primary" onClick={() => navigate('practice', chosen.id)}>
                  练习这一概念
                  <ArrowRight size={16} />
                </button>
              </div>
            </div>
            <ErrorNotice error={mutation.error} />
            {read.includes(chosen.id) && <Confirmed>服务端已记录本次阅读。</Confirmed>}
            <nav className="reading-pagination" aria-label="相邻概念">
              <button
                className="text-button"
                disabled={concepts.data!.items.indexOf(chosen) === 0}
                onClick={() =>
                  navigate(
                    'learn',
                    concepts.data!.items[concepts.data!.items.indexOf(chosen) - 1].id,
                  )
                }
              >
                <ArrowLeft size={16} /> 上一个概念
              </button>
              <span className="muted small">
                {concepts.data!.items.indexOf(chosen) + 1} / {concepts.data!.items.length}
              </span>
              <button
                className="text-button"
                disabled={concepts.data!.items.indexOf(chosen) === concepts.data!.items.length - 1}
                onClick={() =>
                  navigate(
                    'learn',
                    concepts.data!.items[concepts.data!.items.indexOf(chosen) + 1].id,
                  )
                }
              >
                下一个概念 <ArrowRight size={16} />
              </button>
            </nav>
          </article>
        </div>
      ) : (
        <Empty title="课程目录暂时为空">发布的课程尚未包含可读取的概念。</Empty>
      )}
    </>
  );
}
