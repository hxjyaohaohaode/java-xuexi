import { useState } from 'react';
import { GitCompareArrows } from 'lucide-react';
import { useResource } from '../hooks';
import { Badge, Empty, ErrorNotice, Field, Loading } from './ui';

interface Comparison {
  kind: 'CHUNK' | 'EXERCISE';
  id: string;
  fromRevision: number;
  toRevision: number;
  fromReviewHash: string;
  toReviewHash: string;
  fromStatus: string;
  toStatus: string;
  changes: {
    path: string;
    kind: 'ADDED' | 'REMOVED' | 'CHANGED';
    before: unknown;
    after: unknown;
  }[];
}
const valueText = (value: unknown) =>
  typeof value === 'string'
    ? value
    : value === undefined
      ? '该版本没有此字段'
      : JSON.stringify(value, null, 2);
const pathLabel = (path: string) =>
  (
    ({
      '/title': '标题',
      '/body': '知识正文',
      '/prompt': '题目内容',
      '/answerSpec': '答案与私有判定规则',
      '/sources': '来源引用',
      '/knowledgeRefs': '知识引用',
      '/conceptIds': '关联概念',
      '/conceptId': '归属概念',
      '/kind': '题型',
      '/difficulty': '难度',
      '/familyId': '题目家族',
    }) as Record<string, string>
  )[path] || path;

export function ContentDiff({
  kind,
  id,
  revision,
}: {
  kind: 'CHUNK' | 'EXERCISE';
  id: string;
  revision: number;
}) {
  const [from, setFrom] = useState(String(Math.max(1, revision - 1)));
  const [to, setTo] = useState(String(revision));
  const [query, setQuery] = useState('');
  const comparison = useResource<Comparison>(query || null);
  const valid =
    [from, to].every(
      (v) => Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= revision,
    ) && Number(from) !== Number(to);
  return (
    <details className="content-diff">
      <summary>
        <GitCompareArrows size={16} /> 比较已保存修订
      </summary>
      {revision < 2 ? (
        <p className="muted small">这是首个已保存版本，尚无更早修订可比较。</p>
      ) : (
        <>
          <div className="content-diff-controls">
            <Field label="对比起始版本">
              <input
                type="number"
                min={1}
                max={revision}
                value={from}
                onChange={(event) => setFrom(event.target.value)}
              />
            </Field>
            <Field label="对比目标版本">
              <input
                type="number"
                min={1}
                max={revision}
                value={to}
                onChange={(event) => setTo(event.target.value)}
              />
            </Field>
            <button
              type="button"
              disabled={!valid || comparison.loading}
              onClick={() =>
                setQuery(
                  `/admin/content-diffs?kind=${kind}&id=${id}&fromRevision=${Number(from)}&toRevision=${Number(to)}`,
                )
              }
            >
              读取实际差异
            </button>
          </div>
          <p className="muted small">
            比较固定的服务端内容，未保存草稿不参与；练习私有答案仅在编辑权限下显示。
          </p>
          <ErrorNotice error={comparison.error} retry={comparison.reload} />
          {comparison.loading ? (
            <Loading />
          ) : (
            comparison.data && (
              <>
                <div className="row spread wrap">
                  <strong>
                    版本 {comparison.data.fromRevision} → {comparison.data.toRevision}
                  </strong>
                  <Badge>{comparison.data.changes.length} 个字段变化</Badge>
                </div>
                <p className="muted small">
                  状态：{comparison.data.fromStatus} → {comparison.data.toStatus}
                </p>
                {comparison.data.changes.length ? (
                  <div className="content-diff-changes">
                    {comparison.data.changes.map((change) => (
                      <section key={change.path}>
                        <div className="row spread wrap">
                          <h4>{pathLabel(change.path)}</h4>
                          <Badge>
                            {change.kind === 'ADDED'
                              ? '新增字段'
                              : change.kind === 'REMOVED'
                                ? '移除字段'
                                : '字段改变'}
                          </Badge>
                        </div>
                        <code className="muted small">{change.path}</code>
                        <div className="content-diff-values">
                          <div>
                            <span>版本 {comparison.data!.fromRevision}</span>
                            <pre tabIndex={0} aria-label={`${pathLabel(change.path)} 修改前`}>
                              {valueText(change.before)}
                            </pre>
                          </div>
                          <div>
                            <span>版本 {comparison.data!.toRevision}</span>
                            <pre tabIndex={0} aria-label={`${pathLabel(change.path)} 修改后`}>
                              {valueText(change.after)}
                            </pre>
                          </div>
                        </div>
                      </section>
                    ))}
                  </div>
                ) : (
                  <Empty title="这些版本的内容字段一致">
                    审核状态与审核哈希独立列示，不会被误算成知识正文修改。
                  </Empty>
                )}
                <details>
                  <summary>核对两版审核摘要</summary>
                  <dl>
                    <dt>起始版本</dt>
                    <dd>
                      <code>{comparison.data.fromReviewHash}</code>
                    </dd>
                    <dt>目标版本</dt>
                    <dd>
                      <code>{comparison.data.toReviewHash}</code>
                    </dd>
                  </dl>
                </details>
              </>
            )
          )}
        </>
      )}
    </details>
  );
}
