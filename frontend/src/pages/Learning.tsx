import { useState } from 'react';
import { ArrowRight, Check, Search } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource } from '../hooks';
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

export function Learning() {
  const { workspace, navigate, selection, can } = useWorkspace();
  const concepts = useResource<{ releaseId: string; items: Concept[] }>(
    `/concepts?releaseId=${workspace.releaseId}`,
  );
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [read, setRead] = useState<string[]>([]);
  const mutation = useMutation();
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
      () => setRead((p) => [...p, chosen.id]),
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
        <button type="submit">检索</button>
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
          </article>
        </div>
      ) : (
        <Empty title="课程目录暂时为空">发布的课程尚未包含可读取的概念。</Empty>
      )}
    </>
  );
}
