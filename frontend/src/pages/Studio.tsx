import { useEffect, useState } from 'react';
import {
  Download,
  FileText,
  MonitorPlay,
  Plus,
  Save,
  Trash2,
  ArrowDown,
  ArrowUp,
  LibraryBig,
} from 'lucide-react';
import { ApiError, api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource } from '../hooks';
import { readSaved, reconcileDraft, writeSaved } from '../state';
import {
  Badge,
  Confirmed,
  Empty,
  ErrorNotice,
  Field,
  Loading,
  SectionTitle,
  Status,
  EvidenceList,
} from '../components/ui';
import { SourcePreview } from '../components/SourcePreview';
import type {
  Artifact,
  ArtifactContent,
  ArtifactVersion,
  Attempt,
  Concept,
  Exercise,
  ExportJob,
  InteractionBlock,
  Reference,
  Slide,
  TextBlock,
} from '../types';

type Draft = {
  baseRevision: number;
  content: ArtifactContent;
  inputRefs: Reference[];
  dirty: boolean;
};
const freshBlock = (): TextBlock => ({
  id: crypto.randomUUID(),
  kind: 'PARAGRAPH',
  text: '',
  evidenceRefs: [],
});
const freshSlide = (): Slide => ({
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
      text: '写下你要解释的规则与例子。',
      assetId: null,
      alt: '',
    },
  ],
  notes: '',
  evidenceRefs: [],
});
function initialContent(artifact: Artifact): ArtifactContent {
  return artifact.kind === 'SLIDES'
    ? { kind: 'SLIDES', schemaVersion: '1.0', aspect: '16:9', slides: [freshSlide()] }
    : { kind: 'DOCUMENT', schemaVersion: '1.0', title: artifact.title, blocks: [freshBlock()] };
}

export function Studio() {
  const { workspace, tick, selection, navigate, refresh } = useWorkspace();
  const artifacts = useResource<Artifact[]>(`/workspaces/${workspace.id}/artifacts`, tick);
  const [creating, setCreating] = useState(false);
  const [title, setTitle] = useState('泛型学习讲义');
  const [kind, setKind] = useState('DOCUMENT');
  const mutation = useMutation();
  const selected =
    artifacts.data?.find((a) => a.id === selection) ||
    (selection ? undefined : artifacts.data?.[0]);
  const create = (e: React.SubmitEvent<HTMLFormElement>) => {
    e.preventDefault();
    void mutation.run(
      () =>
        api.request<Artifact>(
          `/workspaces/${workspace.id}/artifacts`,
          'POST',
          { kind, title, goalId: null },
          { idempotent: true },
        ),
      (artifact) => {
        refresh();
        setCreating(false);
        navigate('studio', artifact.id);
      },
    );
  };
  return (
    <>
      <SectionTitle
        eyebrow="把理解写成可以继续使用的成果"
        title="作品与错题集"
        description="讲义、幻灯片和错题源稿共享版本与证据引用。预览展示当前源稿；只有保存成功的版本可以导出。"
        action={
          <button className="primary" onClick={() => setCreating((v) => !v)}>
            <Plus size={17} />
            新建作品
          </button>
        }
      />
      {creating && (
        <form className="inline-create panel" onSubmit={create}>
          <Field label="作品名称">
            <input
              value={title}
              maxLength={255}
              required
              onChange={(e) => setTitle(e.target.value)}
            />
          </Field>
          <Field label="源稿类型">
            <select value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="DOCUMENT">文档讲义</option>
              <option value="SLIDES">幻灯片</option>
            </select>
          </Field>
          <button className="primary" disabled={mutation.busy}>
            {mutation.busy ? '正在创建…' : '创建源稿'}
          </button>
          <button type="button" onClick={() => setCreating(false)}>
            取消
          </button>
          <ErrorNotice error={mutation.error} />
        </form>
      )}
      <ErrorNotice error={artifacts.error} retry={artifacts.reload} />
      {artifacts.loading && !artifacts.data ? (
        <Loading />
      ) : (
        <div className="studio-layout">
          <aside className="artifact-list" aria-label="作品列表">
            {artifacts.data?.map((artifact) => (
              <button
                className={selected?.id === artifact.id ? 'selected' : ''}
                key={artifact.id}
                onClick={() => navigate('studio', artifact.id)}
              >
                {artifact.kind === 'SLIDES' ? (
                  <MonitorPlay size={19} />
                ) : artifact.kind === 'NOTEBOOK' ? (
                  <LibraryBig size={19} />
                ) : (
                  <FileText size={19} />
                )}
                <span>
                  <strong>{artifact.title}</strong>
                  <small>
                    {artifact.kind === 'DOCUMENT'
                      ? '文档'
                      : artifact.kind === 'SLIDES'
                        ? '幻灯片'
                        : artifact.kind === 'NOTEBOOK'
                          ? '错题集'
                          : artifact.kind}{' '}
                    ·{' '}
                    {artifact.currentRevision > 0
                      ? `版本 ${artifact.currentRevision}`
                      : '未保存源稿'}
                  </small>
                </span>
              </button>
            ))}
            <p className="muted small">错题集从真实作答构建。图片、音频和视频生成尚未接入。</p>
          </aside>
          <section className="studio-main">
            {selected ? (
              <ArtifactEditor key={selected.id} artifact={selected} />
            ) : (
              <Empty
                title={selection ? '作品不在当前工作空间' : '这里等待你的第一份作品'}
                action={<button onClick={() => setCreating(true)}>新建文档或幻灯片</button>}
              >
                你也可以先完成练习，再把需要回看的作答构建为可复练错题集。
              </Empty>
            )}
          </section>
        </div>
      )}
    </>
  );
}
function ArtifactEditor({ artifact }: { artifact: Artifact }) {
  const { workspace, session, tick, refresh, can } = useWorkspace();
  const storageKey = `tf.draft.${session.userId}.${workspace.id}.${artifact.id}`;
  const remote = useResource<ArtifactVersion>(
    artifact.currentRevision > 0
      ? `/artifacts/${artifact.id}/revisions?revision=${artifact.currentRevision}`
      : null,
    tick,
  );
  const [draft, setDraft] = useState<Draft>(() =>
    readSaved(storageKey, {
      baseRevision: 0,
      content: initialContent(artifact),
      inputRefs: [],
      dirty: false,
    }),
  );
  const [tab, setTab] = useState<'edit' | 'preview'>('edit');
  const [saved, setSaved] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [sourcePicker, setSourcePicker] = useState(false);
  const [sourceId, setSourceId] = useState('');
  const mutation = useMutation();
  const concepts = useResource<{ items: Concept[] }>(
    sourcePicker ? `/concepts?releaseId=${workspace.releaseId}` : null,
  );
  useEffect(() => {
    if (remote.data) {
      const version = remote.data;
      setDraft((d) => {
        // Ignore the whole obsolete snapshot, including references and conflict state.
        if (version.revision < d.baseRevision) return d;
        const result = reconcileDraft(d, version);
        setConflict(result.conflict);
        return { ...result.draft, inputRefs: d.dirty ? d.inputRefs : version.inputRefs };
      });
    }
  }, [remote.data]);
  useEffect(() => {
    if (draft.dirty) writeSaved(storageKey, draft);
    else sessionStorage.removeItem(storageKey);
  }, [draft, storageKey]);
  const update = (content: ArtifactContent) => {
    setDraft((d) => ({ ...d, content, dirty: true }));
    setSaved(false);
  };
  const save = () =>
    mutation.run(
      () =>
        api.request<ArtifactVersion>(`/artifacts/${artifact.id}/revisions`, 'POST', {
          baseRevision: draft.baseRevision,
          content: draft.content,
          inputRefs: draft.inputRefs,
        }),
      (version) => {
        setDraft({
          baseRevision: version.revision,
          content: version.content,
          inputRefs: version.inputRefs,
          dirty: false,
        });
        setSaved(true);
        setConflict(false);
        refresh();
      },
    );
  const addSource = () => {
    const concept = concepts.data?.items.find((c) => c.id === sourceId);
    if (!concept) return;
    const refs = concept.evidence.map((e) => ({
      kind: 'KNOWLEDGE_CHUNK' as const,
      id: e.chunkId,
      revision: e.revision,
      contentHash: e.contentHash,
    }));
    setDraft((d) => {
      let content = d.content;
      if (content.kind === 'DOCUMENT')
        content = {
          ...content,
          blocks: [
            ...content.blocks,
            { id: crypto.randomUUID(), kind: 'HEADING', text: concept.title, evidenceRefs: [] },
            ...concept.evidence.map((e) => ({
              id: crypto.randomUUID(),
              kind: 'QUOTE' as const,
              text: e.excerpt,
              evidenceRefs: [{ id: e.chunkId, revision: e.revision }],
            })),
          ],
        };
      if (content.kind === 'SLIDES')
        content = {
          ...content,
          slides: [
            ...content.slides,
            {
              ...freshSlide(),
              title: concept.title,
              elements: [
                {
                  ...freshSlide().elements[0],
                  text: concept.evidence[0]?.excerpt || concept.title,
                },
              ],
              evidenceRefs: concept.evidence.map((e) => ({ id: e.chunkId, revision: e.revision })),
            },
          ],
        };
      const dedup = new Map([...d.inputRefs, ...refs].map((r) => [`${r.kind}:${r.id}`, r]));
      return { ...d, content, inputRefs: [...dedup.values()], dirty: true };
    });
    setSourcePicker(false);
    setSaved(false);
  };
  const currentRemote =
    remote.data && remote.data.revision >= draft.baseRevision ? remote.data : null;
  const blocked =
    currentRemote?.reviewStatus === 'STALE' || currentRemote?.reviewStatus === 'REJECTED';
  return (
    <fieldset disabled={mutation.busy} aria-label="作品源稿编辑" style={{ minWidth: 0 }}>
      <header className="editor-heading">
        <div>
          <h2>{artifact.title}</h2>
          <div className="row wrap">
            <Badge>基线版本 {draft.baseRevision}</Badge>
            {currentRemote && <Status value={currentRemote.reviewStatus} />}
            <span className="muted small">
              {remote.loading && draft.baseRevision === 0
                ? '正在读取固定源版本'
                : draft.dirty
                  ? '有未保存修改'
                  : draft.baseRevision === 0
                    ? '尚未保存源稿'
                    : '已与所读版本同步'}
            </span>
          </div>
        </div>
        {['DOCUMENT', 'SLIDES'].includes(artifact.kind) && (
          <button
            className="primary"
            disabled={
              mutation.busy || conflict || blocked || (!draft.dirty && draft.baseRevision > 0)
            }
            onClick={() => void save()}
          >
            <Save size={16} />
            {mutation.busy ? '保存中…' : '保存版本'}
          </button>
        )}
      </header>
      <ErrorNotice error={remote.error} retry={remote.reload} />
      <ErrorNotice error={mutation.error} />
      {(conflict || (mutation.error instanceof ApiError && mutation.error.status === 409)) && (
        <div className="notice warning">
          <div>
            <strong>服务器有新版本，草稿仍保留在本标签页。</strong>
            <p>先读取最新内容并比较，再决定是否将本地稿件保存为后续版本。不会自动覆盖。</p>
            <div className="row wrap">
              <button
                onClick={() => {
                  remote.reload();
                  refresh();
                }}
              >
                读取最新版本
              </button>
              {currentRemote && (
                <>
                  <button
                    onClick={() => {
                      setDraft((d) => ({ ...d, baseRevision: currentRemote.revision }));
                      setConflict(false);
                      mutation.clearError();
                    }}
                  >
                    已比较，保留本地稿并以版本 {currentRemote.revision} 为基线
                  </button>
                  <button
                    className="text-button"
                    onClick={() => {
                      setDraft({
                        baseRevision: currentRemote.revision,
                        content: currentRemote.content,
                        inputRefs: currentRemote.inputRefs,
                        dirty: false,
                      });
                      setConflict(false);
                      mutation.clearError();
                    }}
                  >
                    放弃本地修改，采用最新内容
                  </button>
                  <details>
                    <summary>查看服务器最新内容</summary>
                    <SourcePreview content={currentRemote.content} />
                  </details>
                </>
              )}
            </div>
          </div>
        </div>
      )}
      {blocked && (
        <div className="notice warning">
          此版本的来源已失效或被拒绝。请返回原始练习或证据重新构建；旧来源不能继续复练或导出。
        </div>
      )}
      {saved && <Confirmed>服务端已保存版本 {draft.baseRevision}。</Confirmed>}
      {remote.loading && artifact.currentRevision > 0 && draft.baseRevision === 0 ? (
        <Loading />
      ) : artifact.kind === 'NOTEBOOK' || artifact.kind === 'INTERACTION' ? (
        <>
          {(draft.content.kind === 'NOTEBOOK' || draft.content.kind === 'INTERACTION') &&
            draft.content.blocks.map((block) => (
              <NotebookBlock
                key={block.id}
                artifact={artifact}
                version={currentRemote}
                block={block}
                disabled={blocked || !can('NOTEBOOK_ACTIONS')}
              />
            ))}
        </>
      ) : (
        <>
          <div className="editor-toolbar">
            <div className="segments" aria-label="源稿视图">
              <button className={tab === 'edit' ? 'selected' : ''} onClick={() => setTab('edit')}>
                编辑源稿
              </button>
              <button
                className={tab === 'preview' ? 'selected' : ''}
                onClick={() => setTab('preview')}
              >
                预览
              </button>
            </div>
            {['DOCUMENT', 'SLIDES'].includes(artifact.kind) && (
              <button onClick={() => setSourcePicker((v) => !v)}>
                <Plus size={15} />
                引用课程知识
              </button>
            )}
          </div>
          {sourcePicker && (
            <section className="source-picker">
              <Field label="选择要引用的知识卡">
                <select value={sourceId} onChange={(e) => setSourceId(e.target.value)}>
                  <option value="">请选择</option>
                  {concepts.data?.items.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.code} · {c.title}
                    </option>
                  ))}
                </select>
              </Field>
              <ErrorNotice error={concepts.error} retry={concepts.reload} />
              <button disabled={!sourceId} onClick={addSource}>
                插入内容并保留证据引用
              </button>
            </section>
          )}
          {tab === 'preview' ? (
            <SourcePreview content={draft.content} />
          ) : (
            <StructuredEditor content={draft.content} onChange={update} />
          )}
        </>
      )}
      {draft.inputRefs.length > 0 && (
        <details className="source-manifest">
          <summary>{draft.inputRefs.length} 条已固定版本的输入引用</summary>
          <ul>
            {draft.inputRefs.map((r) => (
              <li key={`${r.kind}:${r.id}`}>
                <code>{r.kind}</code> · 版本 {r.revision}
                <br />
                <small>{r.id}</small>
              </li>
            ))}
          </ul>
        </details>
      )}
      {can('STATIC_EXPORT') && (
        <ExportPanel
          artifact={artifact}
          revision={draft.baseRevision}
          dirty={draft.dirty}
          blocked={blocked}
        />
      )}
    </fieldset>
  );
}
function StructuredEditor({
  content,
  onChange,
}: {
  content: ArtifactContent;
  onChange: (content: ArtifactContent) => void;
}) {
  if (content.kind === 'DOCUMENT')
    return (
      <div className="block-editor">
        <Field label="讲义标题">
          <input
            value={content.title}
            maxLength={255}
            onChange={(e) => onChange({ ...content, title: e.target.value })}
          />
        </Field>
        {content.blocks.map((block, index) => (
          <section className="edit-block" key={block.id}>
            <div className="row spread">
              <label className="inline-field">
                <span className="muted small">段落 {index + 1}</span>
                <select
                  aria-label={`段落 ${index + 1} 类型`}
                  value={block.kind}
                  onChange={(e) =>
                    onChange({
                      ...content,
                      blocks: content.blocks.map((b) =>
                        b.id === block.id ? { ...b, kind: e.target.value as TextBlock['kind'] } : b,
                      ),
                    })
                  }
                >
                  <option value="PARAGRAPH">正文</option>
                  <option value="HEADING">小标题</option>
                  <option value="CODE">代码</option>
                  <option value="QUOTE">引用</option>
                </select>
              </label>
              <div className="row">
                <button
                  className="icon-button"
                  aria-label={`上移段落 ${index + 1}`}
                  disabled={index === 0}
                  onClick={() => {
                    const blocks = [...content.blocks];
                    [blocks[index - 1], blocks[index]] = [blocks[index], blocks[index - 1]];
                    onChange({ ...content, blocks });
                  }}
                >
                  <ArrowUp size={15} />
                </button>
                <button
                  className="icon-button"
                  aria-label={`下移段落 ${index + 1}`}
                  disabled={index === content.blocks.length - 1}
                  onClick={() => {
                    const blocks = [...content.blocks];
                    [blocks[index + 1], blocks[index]] = [blocks[index], blocks[index + 1]];
                    onChange({ ...content, blocks });
                  }}
                >
                  <ArrowDown size={15} />
                </button>
                <button
                  className="icon-button"
                  aria-label={`删除段落 ${index + 1}`}
                  disabled={content.blocks.length <= 1}
                  onClick={() =>
                    onChange({
                      ...content,
                      blocks: content.blocks.filter((b) => b.id !== block.id),
                    })
                  }
                >
                  <Trash2 size={15} />
                </button>
              </div>
            </div>
            <textarea
              aria-label={`段落 ${index + 1} 内容`}
              className={block.kind === 'CODE' ? 'code-input' : ''}
              rows={block.kind === 'CODE' ? 7 : 4}
              value={block.text}
              maxLength={16000}
              onChange={(e) =>
                onChange({
                  ...content,
                  blocks: content.blocks.map((b) =>
                    b.id === block.id ? { ...b, text: e.target.value } : b,
                  ),
                })
              }
            />
            {block.evidenceRefs.length > 0 && (
              <small className="muted">引用了 {block.evidenceRefs.length} 条知识证据</small>
            )}
          </section>
        ))}
        <button
          disabled={content.blocks.length >= 200}
          onClick={() => onChange({ ...content, blocks: [...content.blocks, freshBlock()] })}
        >
          <Plus size={16} />
          添加段落
        </button>
      </div>
    );
  if (content.kind === 'SLIDES')
    return (
      <div className="block-editor">
        <Field label="画幅">
          <select
            value={content.aspect}
            onChange={(e) => onChange({ ...content, aspect: e.target.value as '16:9' | '4:3' })}
          >
            <option value="16:9">16:9</option>
            <option value="4:3">4:3</option>
          </select>
        </Field>
        {content.slides.map((slide, index) => (
          <section className="edit-block" key={slide.id}>
            <div className="row spread">
              <h3>第 {index + 1} 页</h3>
              <button
                className="icon-button"
                aria-label={`删除第 ${index + 1} 页`}
                disabled={content.slides.length <= 1}
                onClick={() =>
                  onChange({ ...content, slides: content.slides.filter((s) => s.id !== slide.id) })
                }
              >
                <Trash2 size={15} />
              </button>
            </div>
            <Field label={`第 ${index + 1} 页标题`}>
              <input
                value={slide.title}
                maxLength={200}
                onChange={(e) =>
                  onChange({
                    ...content,
                    slides: content.slides.map((s) =>
                      s.id === slide.id ? { ...s, title: e.target.value } : s,
                    ),
                  })
                }
              />
            </Field>
            {slide.elements.map((element, elementIndex) => (
              <div key={element.id}>
                <Field label={`第 ${index + 1} 页元素 ${elementIndex + 1}`}>
                  <textarea
                    rows={5}
                    maxLength={8000}
                    value={element.text}
                    onChange={(e) =>
                      onChange({
                        ...content,
                        slides: content.slides.map((s) =>
                          s.id === slide.id
                            ? {
                                ...s,
                                elements: s.elements.map((el) =>
                                  el.id === element.id ? { ...el, text: e.target.value } : el,
                                ),
                              }
                            : s,
                        ),
                      })
                    }
                  />
                </Field>
                <label className="checkbox-field">
                  <input
                    type="checkbox"
                    checked={element.kind === 'CODE'}
                    onChange={(e) =>
                      onChange({
                        ...content,
                        slides: content.slides.map((s) =>
                          s.id === slide.id
                            ? {
                                ...s,
                                elements: s.elements.map((el) =>
                                  el.id === element.id
                                    ? { ...el, kind: e.target.checked ? 'CODE' : 'TEXT' }
                                    : el,
                                ),
                              }
                            : s,
                        ),
                      })
                    }
                  />
                  作为代码显示
                </label>
              </div>
            ))}
            <Field label={`第 ${index + 1} 页讲解备注`}>
              <textarea
                rows={2}
                maxLength={8000}
                value={slide.notes}
                onChange={(e) =>
                  onChange({
                    ...content,
                    slides: content.slides.map((s) =>
                      s.id === slide.id ? { ...s, notes: e.target.value } : s,
                    ),
                  })
                }
              />
            </Field>
          </section>
        ))}
        <button
          disabled={content.slides.length >= 80}
          onClick={() => onChange({ ...content, slides: [...content.slides, freshSlide()] })}
        >
          <Plus size={16} />
          添加一页
        </button>
      </div>
    );
  return <SourcePreview content={content} />;
}
function ExportPanel({
  artifact,
  revision,
  dirty,
  blocked,
}: {
  artifact: Artifact;
  revision: number;
  dirty: boolean;
  blocked: boolean;
}) {
  const { tick } = useWorkspace();
  const [format, setFormat] = useState(
    artifact.kind === 'DOCUMENT' ? 'MARKDOWN' : artifact.kind === 'DIAGRAM' ? 'SVG' : 'HTML',
  );
  const [job, setJob] = useState<ExportJob | null>(null);
  const [poll, setPoll] = useState(0);
  const mutation = useMutation();
  const current = useResource<ExportJob>(
    job ? `/export-jobs/${job.id}` : null,
    poll + tick + revision,
  );
  useEffect(() => {
    if (current.data) setJob(current.data);
  }, [current.data]);
  useEffect(() => {
    if (job && ['QUEUED', 'RUNNING'].includes(job.status)) {
      const timer = setTimeout(() => setPoll((n) => n + 1), 1200);
      return () => clearTimeout(timer);
    }
  }, [job, poll]);
  const download = current.data || job;
  const path = download?.downloadPath;
  const safePath = path?.startsWith(`/api/v1/export-jobs/${download?.id}/download`) ? path : null;
  return (
    <section className="export-panel">
      <div>
        <h3>导出已保存的源稿</h3>
        <p className="muted small">
          {artifact.kind === 'NOTEBOOK' || artifact.kind === 'INTERACTION'
            ? '离线 HTML 只包含静态阅读内容；重新作答需回到在线工作空间。'
            : '生成可下载的实际文件，保留当前版本与来源信息。'}
        </p>
      </div>
      <div className="row wrap">
        <select aria-label="导出格式" value={format} onChange={(e) => setFormat(e.target.value)}>
          {artifact.kind === 'DOCUMENT' && <option value="MARKDOWN">Markdown 文档</option>}
          {artifact.kind !== 'DIAGRAM' && (
            <option value="HTML">HTML {artifact.kind === 'SLIDES' ? '幻灯片' : '文档'}</option>
          )}
          {artifact.kind === 'DIAGRAM' && <option value="SVG">SVG 图</option>}
        </select>
        <button
          disabled={mutation.busy || dirty || blocked || revision === 0}
          onClick={() =>
            void mutation.run(
              () =>
                api.request<ExportJob>(
                  `/artifacts/${artifact.id}/exports`,
                  'POST',
                  { artifactRevision: revision, format },
                  { idempotent: true },
                ),
              setJob,
            )
          }
        >
          <Download size={16} />
          生成导出文件
        </button>
      </div>
      {dirty && <small className="muted">请先保存当前修改。</small>}
      <ErrorNotice
        error={mutation.error || current.error}
        retry={current.error ? current.reload : undefined}
      />
      {download && (
        <div className="row wrap">
          <Status value={download.status} />
          <span className="small">源版本 {download.artifactRevision}</span>
          {download.status === 'SUCCEEDED' &&
          download.sourceState === 'CURRENT' &&
          download.artifactRevision === revision &&
          download.artifactRevision === artifact.currentRevision &&
          safePath ? (
            <a className="button primary" href={safePath} download>
              <Download size={16} />
              下载 {download.format}
            </a>
          ) : (
            download.status === 'SUCCEEDED' && (
              <Badge tone="warn">来源状态：{download.sourceState}，不可下载</Badge>
            )
          )}
          {download.limitations.map((l) => (
            <p className="muted small" key={l}>
              {l}
            </p>
          ))}
        </div>
      )}
    </section>
  );
}
function NotebookBlock({
  artifact,
  version,
  block,
  disabled,
}: {
  artifact: Artifact;
  version: ArtifactVersion | null;
  block: InteractionBlock;
  disabled: boolean;
}) {
  const { workspace, refresh } = useWorkspace();
  const exercise = useResource<Exercise>(
    block.exerciseRef
      ? `/exercises/${block.exerciseRef.id}?releaseId=${workspace.releaseId}&revision=${block.exerciseRef.revision}`
      : null,
  );
  const concepts = useResource<{ items: Concept[] }>(`/concepts?releaseId=${workspace.releaseId}`);
  const [prediction, setPrediction] = useState('');
  const [options, setOptions] = useState<string[]>([]);
  const [feedback, setFeedback] = useState<Attempt | null>(null);
  const [notice, setNotice] = useState('');
  const [showEvidence, setShowEvidence] = useState(false);
  const mutation = useMutation();
  const action = (action: string) =>
    version &&
    mutation.run(
      async () => {
        const result = await api.request<{
          attemptId: string | null;
          message: string;
          evidenceRefs: { id: string; revision: number }[];
        }>(
          `/notebooks/${artifact.id}/actions`,
          'POST',
          {
            artifactRevision: version.revision,
            blockId: block.id,
            action,
            expectedBlockRevision: block.revision,
            answer: action === 'submitAnswer' ? { kind: 'CHOICE', optionIds: options } : null,
            selection: action === 'predict' ? prediction : '',
          },
          { idempotent: true },
        );
        return {
          result,
          attempt: result.attemptId
            ? await api.get<Attempt>(`/attempts/${result.attemptId}`)
            : null,
        };
      },
      ({ result, attempt }) => {
        setNotice(result.message);
        if (attempt) setFeedback(attempt);
        if (action === 'openEvidence') setShowEvidence(true);
        refresh();
      },
    );
  const evidence =
    concepts.data?.items
      .flatMap((c) => c.evidence)
      .filter((e) =>
        block.evidenceRefs.some((r) => r.id === e.chunkId && r.revision === e.revision),
      ) || [];
  return (
    <section className="notebook-block">
      <div className="row spread">
        <h3>重新检查你的判断</h3>
        <Badge>关联真实练习</Badge>
      </div>
      <p className="question-prompt">{block.prompt}</p>
      {typeof block.config.studentReason === 'string' && (
        <details>
          <summary>上次作答上下文</summary>
          <p className="preserve-text">{block.config.studentReason}</p>
        </details>
      )}
      <Field label="先写下这次的预测或理由">
        <textarea
          rows={3}
          maxLength={1000}
          value={prediction}
          onChange={(e) => setPrediction(e.target.value)}
          disabled={disabled}
        />
      </Field>
      <button
        disabled={
          disabled ||
          mutation.busy ||
          !prediction.trim() ||
          !block.allowedActions.includes('predict')
        }
        onClick={() => void action('predict')}
      >
        记录预测
      </button>
      <ErrorNotice error={exercise.error} retry={exercise.reload} />
      {exercise.data && (
        <fieldset className="answer-options" disabled={disabled || mutation.busy}>
          <legend>重新选择答案</legend>
          {exercise.data.options.map((option) => (
            <label
              className={`answer-option ${options.includes(option.id) ? 'selected' : ''}`}
              key={option.id}
            >
              <input
                type={exercise.data?.kind === 'SINGLE' ? 'radio' : 'checkbox'}
                name={`notebook-${block.id}`}
                checked={options.includes(option.id)}
                onChange={(e) => {
                  setOptions((p) =>
                    exercise.data?.kind === 'SINGLE'
                      ? [option.id]
                      : e.target.checked
                        ? [...p, option.id]
                        : p.filter((id) => id !== option.id),
                  );
                  setFeedback(null);
                }}
              />
              <span className="option-label">{option.id}</span>
              <span>{option.text}</span>
            </label>
          ))}
        </fieldset>
      )}
      <div className="row wrap">
        <button
          className="primary"
          disabled={
            disabled ||
            mutation.busy ||
            options.length === 0 ||
            !block.allowedActions.includes('submitAnswer')
          }
          onClick={() => void action('submitAnswer')}
        >
          提交复练
        </button>
        <button
          disabled={disabled || mutation.busy || !block.allowedActions.includes('openEvidence')}
          onClick={() => void action('openEvidence')}
        >
          查看关联证据
        </button>
      </div>
      <ErrorNotice error={mutation.error} />
      {notice && <Confirmed>{notice}</Confirmed>}
      {feedback && (
        <section className="feedback" aria-live="polite">
          <h3>
            复练反馈 · {feedback.score}/{feedback.maximum}
          </h3>
          <p className="preserve-text">{feedback.feedback}</p>
          <p className="muted small">重复题目家族不会增加独立掌握样本。</p>
        </section>
      )}
      {showEvidence &&
        (evidence.length ? (
          <EvidenceList items={evidence} />
        ) : (
          <Empty title="未读取到匹配的知识引用">刷新课程后重试，或检查源稿是否已经失效。</Empty>
        ))}
    </section>
  );
}
