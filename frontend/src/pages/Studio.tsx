import { useEffect, useRef, useState } from 'react';
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
  Undo2,
  Redo2,
  Image,
} from 'lucide-react';
import { ApiError, api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, usePolling, useResource, useSessionDraft, useUnsavedChanges } from '../hooks';
import { readSaved, removeSaved, reconcileDraft, writeSaved } from '../state';
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
import { SlideEditor } from '../components/SlideEditor';
import { AssetsPanel } from '../components/Assets';
import { CompileDiagnostics, jobPending, starterSource } from '../components/Compiler';
import { SourcePreview } from '../components/SourcePreview';
import { NotebookWorkspace } from '../components/Notebook';
import type {
  Artifact,
  Asset,
  ArtifactContent,
  ArtifactVersion,
  Attempt,
  Concept,
  CompileJob,
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
  const { workspace, session, tick, selection, navigate, refresh, can } = useWorkspace();
  const artifacts = useResource<Artifact[]>(`/workspaces/${workspace.id}/artifacts`, tick);
  const [creating, setCreating] = useState(false);
  const [mediaOpen, setMediaOpen] = useState(false);
  const [title, setTitle] = useSessionDraft(
    `tf.artifact-title.${session.userId}.${workspace.id}`,
    '泛型学习讲义',
  );
  const [kind, setKind] = useSessionDraft(
    `tf.artifact-kind.${session.userId}.${workspace.id}`,
    'DOCUMENT',
  );
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
          <div className="row wrap">
            {can('PRIVATE_ASSETS') && (
              <button aria-expanded={mediaOpen} onClick={() => setMediaOpen(!mediaOpen)}>
                <Image size={17} />
                素材库
              </button>
            )}
            <button
              className="primary"
              disabled={mutation.busy}
              onClick={() => setCreating((v) => !v)}
            >
              <Plus size={17} />
              新建作品
            </button>
          </div>
        }
      />
      {mediaOpen && can('PRIVATE_ASSETS') && (
        <div className="panel">
          <AssetsPanel />
        </div>
      )}
      {creating && (
        <form className="inline-create panel" onSubmit={create}>
          <Field label="作品名称">
            <input
              value={title}
              disabled={mutation.busy}
              maxLength={255}
              required
              onChange={(e) => setTitle(e.target.value)}
            />
          </Field>
          <Field label="源稿类型">
            <select value={kind} disabled={mutation.busy} onChange={(e) => setKind(e.target.value)}>
              <option value="DOCUMENT">文档讲义</option>
              <option value="SLIDES">幻灯片</option>
            </select>
          </Field>
          <button className="primary" disabled={mutation.busy}>
            {mutation.busy ? '正在创建…' : '创建源稿'}
          </button>
          <button type="button" disabled={mutation.busy} onClick={() => setCreating(false)}>
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
            <p className="muted small">
              错题集从真实作答构建。自己的素材与自动生成内容会分别标明来源。
            </p>
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
  const [confirmedVersion, setConfirmedVersion] = useState<ArtifactVersion | null>(null);
  const [focusNotebookBlock, setFocusNotebookBlock] = useState('');
  const [conflict, setConflict] = useState(false);
  const [sourcePicker, setSourcePicker] = useState(false);
  const [sourceId, setSourceId] = useState('');
  const [assetPicker, setAssetPicker] = useState(false);
  const [assetSlideId, setAssetSlideId] = useState('');
  const [undo, setUndo] = useState<Draft[]>([]);
  const [redo, setRedo] = useState<Draft[]>([]);
  const lastEdit = useRef(0);
  useUnsavedChanges(draft.dirty);
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
    else removeSaved(storageKey);
  }, [draft, storageKey]);
  const update = (content: ArtifactContent) => {
    if (mutation.busy) return;
    rememberEdit();
    setDraft((d) => ({ ...d, content, dirty: true }));
    setSaved(false);
  };
  const rememberEdit = (force = false) => {
    const now = Date.now();
    if (force || now - lastEdit.current > 700) {
      setUndo((previous) => {
        const bounded = [...previous, draft].slice(-20);
        // Undo remains bounded even for the maximum document size.
        while (bounded.length > 1 && JSON.stringify(bounded).length > 1_000_000) bounded.shift();
        return bounded;
      });
    }
    lastEdit.current = now;
    setRedo([]);
  };
  const restoreEdit = (direction: 'undo' | 'redo') => {
    const stack = direction === 'undo' ? undo : redo;
    const previous = stack.at(-1);
    if (!previous || mutation.busy) return;
    if (direction === 'undo') {
      setUndo(stack.slice(0, -1));
      setRedo((p) => [...p, draft]);
    } else {
      setRedo(stack.slice(0, -1));
      setUndo((p) => [...p, draft]);
    }
    setDraft((d) => ({
      ...d,
      content: previous.content,
      inputRefs: previous.inputRefs,
      dirty: true,
    }));
    lastEdit.current = 0;
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
        setUndo([]);
        setRedo([]);
        setConflict(false);
        refresh();
      },
    );
  const addSource = () => {
    const concept = concepts.data?.items.find((c) => c.id === sourceId);
    if (!concept) return;
    rememberEdit(true);
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
  const insertImage = (asset: Asset) => {
    if (draft.content.kind !== 'SLIDES') return;
    const target =
      draft.content.slides.find((slide) => slide.id === assetSlideId) || draft.content.slides[0];
    if (!target || target.elements.length >= 100) return;
    rememberEdit(true);
    const reference: Reference = {
      kind: 'ASSET',
      id: asset.id,
      revision: asset.revision,
      contentHash: asset.contentHash,
    };
    setDraft((current) => {
      if (current.content.kind !== 'SLIDES') return current;
      return {
        ...current,
        dirty: true,
        inputRefs: [
          ...new Map(
            [...current.inputRefs, reference].map((item) => [`${item.kind}:${item.id}`, item]),
          ).values(),
        ],
        content: {
          ...current.content,
          slides: current.content.slides.map((slide) =>
            slide.id === target.id
              ? {
                  ...slide,
                  elements: [
                    ...slide.elements,
                    {
                      id: crypto.randomUUID(),
                      kind: 'IMAGE',
                      x: 0.56,
                      y: 0.28,
                      width: 0.36,
                      height: 0.52,
                      text: '',
                      assetId: asset.id,
                      alt: asset.filename,
                    },
                  ],
                }
              : slide,
          ),
        },
      };
    });
    setSaved(false);
    setAssetPicker(false);
    setTab('preview');
  };
  const currentRemote =
    remote.data && remote.data.revision >= draft.baseRevision
      ? remote.data
      : confirmedVersion && confirmedVersion.revision >= draft.baseRevision
        ? confirmedVersion
        : null;
  const blocked =
    currentRemote?.reviewStatus === 'STALE' || currentRemote?.reviewStatus === 'REJECTED';
  return (
    <fieldset
      disabled={mutation.busy}
      aria-label="作品源稿编辑"
      aria-busy={mutation.busy}
      style={{ minWidth: 0 }}
      onKeyDown={(event) => {
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
          event.preventDefault();
          if (!mutation.busy && !blocked && !conflict && (draft.dirty || draft.baseRevision === 0))
            void save();
        }
      }}
    >
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
        currentRemote && (
          <NotebookWorkspace
            key={`${artifact.id}:${currentRemote.revision}`}
            artifact={artifact}
            version={currentRemote}
            disabled={blocked || draft.dirty || !can('NOTEBOOK_ACTIONS')}
            focusBlockId={focusNotebookBlock}
            onVersion={(version) => {
              setConfirmedVersion(version);
              setDraft({
                baseRevision: version.revision,
                content: version.content,
                inputRefs: version.inputRefs,
                dirty: false,
              });
              setSaved(true);
              setConflict(false);
              if (version.content.kind === 'NOTEBOOK' || version.content.kind === 'INTERACTION')
                setFocusNotebookBlock(version.content.blocks.at(-1)?.id || '');
              refresh();
            }}
          />
        )
      ) : (
        <>
          <div className="editor-toolbar">
            <div className="segments" aria-label="源稿视图">
              <button
                aria-pressed={tab === 'edit'}
                className={tab === 'edit' ? 'selected' : ''}
                onClick={() => setTab('edit')}
              >
                编辑源稿
              </button>
              <button
                className={tab === 'preview' ? 'selected' : ''}
                aria-pressed={tab === 'preview'}
                onClick={() => setTab('preview')}
              >
                预览
              </button>
            </div>
            {['DOCUMENT', 'SLIDES'].includes(artifact.kind) && (
              <div className="row wrap editor-actions">
                <button
                  className="icon-button"
                  disabled={!undo.length}
                  aria-label="撤销修改"
                  onClick={() => restoreEdit('undo')}
                >
                  <Undo2 size={17} />
                </button>
                <button
                  className="icon-button"
                  disabled={!redo.length}
                  aria-label="重做修改"
                  onClick={() => restoreEdit('redo')}
                >
                  <Redo2 size={17} />
                </button>
                {artifact.kind === 'SLIDES' && can('PRIVATE_ASSETS') && (
                  <button onClick={() => setAssetPicker(!assetPicker)} aria-expanded={assetPicker}>
                    <Image size={15} />
                    插入图片
                  </button>
                )}
                <button onClick={() => setSourcePicker((v) => !v)} aria-expanded={sourcePicker}>
                  <Plus size={15} />
                  引用课程知识
                </button>
              </div>
            )}
          </div>
          {assetPicker && draft.content.kind === 'SLIDES' && (
            <section className="source-picker">
              <Field label="图片插入到">
                <select
                  value={assetSlideId || draft.content.slides[0]?.id || ''}
                  onChange={(e) => setAssetSlideId(e.target.value)}
                >
                  {draft.content.slides.map((slide, index) => (
                    <option value={slide.id} key={slide.id}>
                      第 {index + 1} 页 · {slide.title}
                    </option>
                  ))}
                </select>
              </Field>
              <AssetsPanel onInsert={insertImage} />
            </section>
          )}
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
  if (content.kind === 'SLIDES') return <SlideEditor content={content} onChange={onChange} />;
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
  const { tick, can } = useWorkspace();
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
  usePolling(
    !!job && ['QUEUED', 'RUNNING'].includes(job.status),
    () => setPoll((value) => value + 1),
    current.loading,
    !!current.error,
    1200,
  );
  const download = current.data || job;
  const path = download?.downloadPath;
  const safePath = path === `/api/v1/export-jobs/${download?.id}/download` ? path : null;
  const pending = !!download && ['QUEUED', 'RUNNING'].includes(download.status);
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
        <select
          aria-label="导出格式"
          disabled={mutation.busy || pending}
          value={format}
          onChange={(e) => setFormat(e.target.value)}
        >
          {artifact.kind === 'DOCUMENT' && <option value="MARKDOWN">Markdown 文档</option>}
          {artifact.kind !== 'DIAGRAM' && (
            <option value="HTML">HTML {artifact.kind === 'SLIDES' ? '幻灯片' : '文档'}</option>
          )}
          {artifact.kind === 'DIAGRAM' && <option value="SVG">SVG 图</option>}
          {can('OFFICE_PDF_EXPORT') && artifact.kind === 'DOCUMENT' && (
            <option value="DOCX">Word 文档（.docx）</option>
          )}
          {can('OFFICE_PDF_EXPORT') && artifact.kind === 'SLIDES' && (
            <option value="PPTX">PowerPoint（.pptx）</option>
          )}
          {can('OFFICE_PDF_EXPORT') && ['DOCUMENT', 'SLIDES'].includes(artifact.kind) && (
            <option value="PDF">PDF 文档</option>
          )}
        </select>
        <button
          disabled={mutation.busy || pending || dirty || blocked || revision === 0}
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
          {mutation.busy ? '正在提交…' : pending ? '正在生成文件…' : '生成导出文件'}
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
          {pending && (
            <button
              disabled={mutation.busy}
              onClick={() =>
                void mutation.run(
                  () =>
                    api.request<ExportJob>(
                      `/export-jobs/${download.id}/cancel`,
                      'POST',
                      undefined,
                      { idempotent: true },
                    ),
                  setJob,
                )
              }
            >
              取消导出
            </button>
          )}
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
