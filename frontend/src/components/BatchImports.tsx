import { useEffect, useRef, useState } from 'react';
import { Check, FileUp, RefreshCw, Save, Square } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource, useSessionDraft, useUnsavedChanges } from '../hooks';
import { Badge, Confirmed, Dialog, Empty, ErrorNotice, Field, Loading } from './ui';

interface BatchItem {
  id: string;
  fileName: string;
  mediaType: string;
  byteSize: number;
  fileHash: string;
  originalHash: string;
  parser: string;
  status: 'READY' | 'FAILED' | 'COMMITTED' | 'SKIPPED';
  errorCode: string | null;
  message: string;
  originalMarkdown: string;
  cleanedMarkdown: string;
  captureId: string | null;
}
interface Batch {
  id: string;
  sourceId: string;
  ownerId: string;
  revision: number;
  status: 'PREVIEW' | 'COMMITTED' | 'CANCELLED' | 'EXPIRED';
  processingNote: string;
  createdAt: string;
  expiresAt: string;
  items: BatchItem[];
}
interface BatchSummary {
  id: string;
  sourceId: string;
  revision: number;
  status: Batch['status'];
  processingNote: string;
  createdAt: string;
  expiresAt: string;
  itemCount: number;
  readyCount: number;
  failedCount: number;
  committedCount: number;
}
interface ImportDraft {
  baseRevision: number;
  selected: string[];
  items: Record<string, { cleanedMarkdown: string; processingNote: string }>;
  dirty: boolean;
}
const batchLabels = {
  PREVIEW: '待确认预览',
  COMMITTED: '已保存采集',
  CANCELLED: '已取消',
  EXPIRED: '预览已过期',
};
const itemLabels = { READY: '可预览', FAILED: '解析失败', COMMITTED: '已保存', SKIPPED: '未选择' };
const byteSize = (value: number) =>
  value < 1048576 ? `${(value / 1024).toFixed(1)} KiB` : `${(value / 1048576).toFixed(2)} MiB`;

export function BatchImports({
  sources,
  onChange,
  onCapture,
}: {
  sources: { id: string; title: string }[];
  onChange: () => void;
  onCapture: (sourceId: string) => void;
}) {
  const { session, can } = useWorkspace();
  const [sourceId, setSourceId] = useSessionDraft(`tf.import.source.${session.userId}`, '');
  const [processingNote, setProcessingNote] = useSessionDraft(
    `tf.import.note.${session.userId}`,
    '',
  );
  const [selected, setSelected] = useSessionDraft(`tf.import.selected.${session.userId}`, '');
  const [files, setFiles] = useState<File[]>([]);
  const [version, setVersion] = useState(0);
  const [notice, setNotice] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const mutation = useMutation();
  const enabled = can('BATCH_INGESTION');
  const list = useResource<BatchSummary[]>(
    enabled ? '/admin/import-batches?limit=20' : null,
    version,
  );
  const batch = useResource<Batch>(
    enabled && selected ? `/admin/import-batches/${selected}` : null,
    version,
  );
  useUnsavedChanges(files.length > 0);
  const total = files.reduce((sum, file) => sum + file.size, 0);
  const invalid =
    files.length > 8 ||
    files.some(
      (file) => file.size > 1048576 || file.size === 0 || !/\.(txt|md|docx)$/i.test(file.name),
    ) ||
    total > 4194304;
  if (!enabled)
    return (
      <Empty title="当前环境尚未开启批量加工">
        手工采集仍可在资料来源页完成。批量文件需要实际解析服务。
      </Empty>
    );
  return (
    <div className="batch-imports">
      <section className="panel batch-upload">
        <h2>
          <FileUp size={20} /> 从实际文件开始加工
        </h2>
        <p className="muted">
          先上传并逐项预览，再保存你选择的清洗稿。保存只产生来源采集记录，知识卡审核与课程发布仍是后续独立步骤。
        </p>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (invalid || !files.length) return;
            void mutation.run(
              () => {
                const form = new FormData();
                form.set('sourceId', sourceId);
                form.set('processingNote', processingNote);
                files.forEach((file) => form.append('files', file));
                return api.request<Batch>('/admin/import-batches', 'POST', form, {
                  idempotent: true,
                  timeoutMs: 60000,
                });
              },
              (value) => {
                setSelected(value.id);
                setFiles([]);
                if (input.current) input.current.value = '';
                setNotice('服务端已生成实际解析预览，请检查每个文件的结果。');
                setVersion((v) => v + 1);
              },
            );
          }}
        >
          <fieldset className="editor-fieldset" disabled={mutation.busy}>
            <div className="form-grid">
              <Field label="这批文件的资料来源">
                <select
                  required
                  value={sourceId}
                  onChange={(event) => setSourceId(event.target.value)}
                >
                  <option value="">选择已登记来源</option>
                  {sources.map((source) => (
                    <option key={source.id} value={source.id}>
                      {source.title}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="选择 TXT、Markdown 或 DOCX 文件">
                <input
                  ref={input}
                  required
                  type="file"
                  multiple
                  accept=".txt,.md,.docx"
                  onChange={(event) => {
                    setFiles(Array.from(event.target.files || []));
                    setNotice('');
                    mutation.clearError();
                  }}
                />
              </Field>
            </div>
            <Field label="本批处理目的">
              <textarea
                required
                maxLength={2000}
                rows={2}
                value={processingNote}
                onChange={(event) => setProcessingNote(event.target.value)}
                placeholder="说明文件来源、许可范围和准备清理的内容。"
              />
            </Field>
            <p className="muted small">
              最多 8 个文件；每个 1 MiB，合计 4 MiB。PDF 暂不支持。预览保留 24 小时，最多同时保留 3
              批；每账号每天最多 100 批。
            </p>
            {files.length > 0 && (
              <ul className="batch-files-selected">
                {files.map((file, index) => (
                  <li key={`${file.name}:${index}`}>
                    <span>{file.name}</span>
                    <span>{byteSize(file.size)}</span>
                  </li>
                ))}
              </ul>
            )}
            {invalid && (
              <p className="field-error" role="alert">
                请移除不支持、空白或超出容量的文件，再重新选择。文件不会被静默截断。
              </p>
            )}
            <div className="row spread wrap">
              <span className="muted small">
                已选择 {files.length} 个文件 · {byteSize(total)}
              </span>
              <button
                className="primary"
                disabled={
                  mutation.busy || invalid || !files.length || !sourceId || !processingNote.trim()
                }
              >
                <FileUp size={16} /> {mutation.busy ? '正在上传与解析…' : '上传并预览'}
              </button>
            </div>
          </fieldset>
        </form>
        <ErrorNotice error={mutation.error} />
        {notice && <Confirmed>{notice}</Confirmed>}
      </section>
      <div className="batch-workspace">
        <aside className="panel">
          <div className="row spread">
            <h2>最近的加工批次</h2>
            <button
              className="icon-button"
              aria-label="刷新加工批次"
              onClick={() => setVersion((v) => v + 1)}
            >
              <RefreshCw size={17} />
            </button>
          </div>
          <ErrorNotice error={list.error} retry={list.reload} />
          {list.loading && !list.data ? (
            <Loading />
          ) : list.data?.length ? (
            <div className="content-list">
              {list.data.map((item) => (
                <button
                  key={item.id}
                  className={`content-row ${selected === item.id ? 'selected' : ''}`}
                  onClick={() => setSelected(item.id)}
                >
                  <strong>
                    {sources.find((source) => source.id === item.sourceId)?.title || '已登记来源'}
                  </strong>
                  <span>
                    {batchLabels[item.status]} · {item.itemCount} 个文件
                  </span>
                  <small>{new Date(item.createdAt).toLocaleString('zh-CN')}</small>
                </button>
              ))}
            </div>
          ) : (
            !list.error && <p className="muted">还没有加工批次。</p>
          )}
          <p className="muted small">显示最近 20 批。已提交原文与清洗稿保存在来源采集记录中。</p>
        </aside>
        <div>
          <ErrorNotice error={batch.error} retry={batch.reload} />
          {batch.loading && !batch.data ? (
            <Loading />
          ) : batch.data ? (
            <BatchPreview
              key={batch.data.id}
              batch={batch.data}
              sourceTitle={
                sources.find((source) => source.id === batch.data!.sourceId)?.title || '已登记来源'
              }
              onSaved={() => {
                setVersion((v) => v + 1);
                onChange();
              }}
              onReload={batch.reload}
              onCapture={() => onCapture(batch.data!.sourceId)}
            />
          ) : (
            !batch.error && (
              <Empty title="选择一批解析结果">
                检查原文、调整清洗内容，然后一次保存选中的健康文件。
              </Empty>
            )
          )}
        </div>
      </div>
    </div>
  );
}

function BatchPreview({
  batch: serverBatch,
  sourceTitle,
  onSaved,
  onReload,
  onCapture,
}: {
  batch: Batch;
  sourceTitle: string;
  onSaved: () => void;
  onReload: () => void;
  onCapture: () => void;
}) {
  const { session } = useWorkspace();
  const [confirmedBatch, setConfirmedBatch] = useState<Batch | null>(null);
  const batch =
    confirmedBatch && confirmedBatch.revision >= serverBatch.revision
      ? confirmedBatch
      : serverBatch;
  const [draft, setDraft] = useSessionDraft<ImportDraft>(
    `tf.import.draft.${session.userId}.${batch.id}`,
    {
      baseRevision: batch.revision,
      selected: batch.items.filter((item) => item.status === 'READY').map((item) => item.id),
      items: Object.fromEntries(
        batch.items
          .filter((item) => item.status === 'READY')
          .map((item) => [
            item.id,
            { cleanedMarkdown: item.cleanedMarkdown || '', processingNote: batch.processingNote },
          ]),
      ),
      dirty: false,
    },
  );
  const [selectedId, setSelectedId] = useState(batch.items[0]?.id || '');
  const [dialog, setDialog] = useState<'commit' | 'cancel' | null>(null);
  const [notice, setNotice] = useState('');
  const mutation = useMutation();
  const preview = batch.status === 'PREVIEW';
  useUnsavedChanges(preview && draft.dirty);
  const chosen = batch.items.find((item) => item.id === selectedId) || batch.items[0];
  const selected = batch.items.filter(
    (item) => item.status === 'READY' && draft.selected.includes(item.id),
  );
  const update = (itemId: string, value: Partial<ImportDraft['items'][string]>) =>
    setDraft((current) => ({
      ...current,
      dirty: true,
      items: { ...current.items, [itemId]: { ...current.items[itemId], ...value } },
    }));
  const invalid =
    !selected.length ||
    selected.some(
      (item) =>
        !draft.items[item.id]?.cleanedMarkdown.trim() ||
        !draft.items[item.id]?.processingNote.trim(),
    );
  const sameRevision = batch.revision === draft.baseRevision;
  return (
    <section className="panel batch-preview">
      <div className="row spread wrap">
        <h2>{sourceTitle}</h2>
        <Badge
          tone={
            batch.status === 'COMMITTED' ? 'good' : batch.status === 'EXPIRED' ? 'warn' : 'neutral'
          }
        >
          {batchLabels[batch.status]}
        </Badge>
      </div>
      <p className="muted small">
        {preview
          ? `预览有效至 ${new Date(batch.expiresAt).toLocaleString('zh-CN')}`
          : '预览原文已从临时批次清除，已保存内容可在来源采集记录中查看。'}{' '}
        · 批次版本 {batch.revision}
      </p>
      <div className="batch-item-list" aria-label="批次文件">
        {batch.items.map((item) => (
          <div className={`batch-item ${chosen?.id === item.id ? 'selected' : ''}`} key={item.id}>
            {preview && (
              <label className="check-row">
                <input
                  type="checkbox"
                  aria-label={`保存 ${item.fileName}`}
                  checked={draft.selected.includes(item.id) && item.status === 'READY'}
                  disabled={mutation.busy || item.status !== 'READY'}
                  onChange={(event) =>
                    setDraft((current) => ({
                      ...current,
                      dirty: true,
                      selected: event.target.checked
                        ? [...current.selected, item.id]
                        : current.selected.filter((id) => id !== item.id),
                    }))
                  }
                />
              </label>
            )}
            <button onClick={() => setSelectedId(item.id)} aria-pressed={chosen?.id === item.id}>
              <strong>{item.fileName}</strong>
              <span>
                {itemLabels[item.status]} · {byteSize(item.byteSize)}
              </span>
            </button>
          </div>
        ))}
      </div>
      {chosen && (
        <div className="batch-item-detail">
          <div className="row spread wrap">
            <h3>{chosen.fileName}</h3>
            <Badge
              tone={
                chosen.status === 'FAILED'
                  ? 'bad'
                  : chosen.status === 'COMMITTED'
                    ? 'good'
                    : 'neutral'
              }
            >
              {itemLabels[chosen.status]}
            </Badge>
          </div>
          {chosen.message && (
            <p className={chosen.status === 'FAILED' ? 'notice error' : 'muted small'}>
              {chosen.message}
              {chosen.errorCode && <small> · {chosen.errorCode}</small>}
            </p>
          )}
          {preview && chosen.status === 'READY' ? (
            <>
              <div className="batch-texts">
                <section>
                  <h4>实际解析原文</h4>
                  <pre
                    className="batch-original"
                    tabIndex={0}
                    aria-label={`${chosen.fileName} 原文`}
                  >
                    {chosen.originalMarkdown}
                  </pre>
                </section>
                <Field label="清洗后的 Markdown">
                  <textarea
                    rows={14}
                    maxLength={16000}
                    value={draft.items[chosen.id]?.cleanedMarkdown || ''}
                    disabled={mutation.busy}
                    onChange={(event) => update(chosen.id, { cleanedMarkdown: event.target.value })}
                  />
                </Field>
              </div>
              <Field label="这份清洗稿的处理说明">
                <textarea
                  rows={2}
                  maxLength={4000}
                  value={draft.items[chosen.id]?.processingNote || ''}
                  disabled={mutation.busy}
                  onChange={(event) => update(chosen.id, { processingNote: event.target.value })}
                />
              </Field>
            </>
          ) : chosen.captureId ? (
            <div className="notice">
              <div>
                <strong>
                  <Check size={15} /> 已生成不可变采集记录
                </strong>
                <p>
                  <code>{chosen.captureId}</code>
                </p>
                <button onClick={onCapture}>到来源查看原稿与清洗记录</button>
              </div>
            </div>
          ) : (
            <p className="muted">
              {chosen.status === 'FAILED'
                ? '该文件未形成可保存的清洗稿。修正源文件后可另建批次。'
                : '此项没有生成采集记录。需要继续加工时，请重新上传源文件。'}
            </p>
          )}
          <details className="batch-metadata">
            <summary>解析器与内容摘要</summary>
            <dl>
              <dt>解析器</dt>
              <dd>{chosen.parser}</dd>
              <dt>原始文件 SHA-256</dt>
              <dd>
                <code>{chosen.fileHash}</code>
              </dd>
              <dt>解析原文摘要</dt>
              <dd>
                <code>{chosen.originalHash || '未生成'}</code>
              </dd>
            </dl>
          </details>
        </div>
      )}
      {preview && (
        <div className="batch-commit-row">
          <p className="muted small">
            将保存 {selected.length} / {batch.items.length}{' '}
            份采集记录。未选择的项会保留“未选择”状态，不会偷偷写入知识库。
          </p>
          {!sameRevision && (
            <p className="notice warning">
              服务端批次版本已变化，请重新读取状态。当前清洗草稿仍在本地保留。
            </p>
          )}
          <div className="row wrap">
            <button
              className="primary"
              disabled={mutation.busy || invalid || !sameRevision}
              onClick={() => setDialog('commit')}
            >
              <Save size={16} /> 保存所选采集记录
            </button>
            <button disabled={mutation.busy} onClick={() => setDialog('cancel')}>
              <Square size={14} /> 取消这个预览
            </button>
          </div>
        </div>
      )}
      <ErrorNotice error={mutation.error} retry={onReload} />
      {notice && <Confirmed>{notice}</Confirmed>}
      {!preview && draft.dirty && (
        <details>
          <summary>查看仍保留的本地清洗草稿</summary>
          <p className="muted small">
            此批次已结束，以下只是尚未写入的本地输入，不是服务器采集记录。
          </p>
          {Object.entries(draft.items).map(([id, item]) => (
            <pre key={id} tabIndex={0}>
              {item.cleanedMarkdown}
            </pre>
          ))}
        </details>
      )}
      {dialog && (
        <Dialog
          title={dialog === 'commit' ? '确认保存选中的采集记录' : '取消批次预览'}
          onClose={() => {
            if (!mutation.busy) setDialog(null);
          }}
        >
          <h2>{dialog === 'commit' ? `保存 ${selected.length} 份采集记录？` : '取消这个预览？'}</h2>
          <p>
            {dialog === 'commit'
              ? `所有选中内容都归于来源“${sourceTitle}”。服务端会一次校验并保存；任何一项失败时不会部分提交。`
              : '取消将清除服务端临时预览，不会生成任何采集记录。已输入的清洗稿只有保存到采集记录后才是持久内容。'}
          </p>
          {dialog === 'commit' && (
            <ul>
              {selected.map((item) => (
                <li key={item.id}>{item.fileName}</li>
              ))}
            </ul>
          )}
          <ErrorNotice error={mutation.error} />
          <div className="row wrap">
            <button disabled={mutation.busy} onClick={() => setDialog(null)}>
              返回检查
            </button>
            <button
              className="primary"
              disabled={mutation.busy}
              onClick={() =>
                void mutation.run(
                  () =>
                    api.request<Batch>(
                      `/admin/import-batches/${batch.id}/${dialog}`,
                      'POST',
                      dialog === 'commit'
                        ? {
                            expectedRevision: draft.baseRevision,
                            items: selected.map((item) => ({
                              itemId: item.id,
                              ...draft.items[item.id],
                            })),
                          }
                        : { expectedRevision: batch.revision },
                      { idempotent: true },
                    ),
                  (value) => {
                    setConfirmedBatch(value);
                    setDraft({
                      baseRevision: value.revision,
                      dirty: false,
                      selected: [],
                      items: {},
                    });
                    setDialog(null);
                    setNotice(
                      value.status === 'COMMITTED'
                        ? '所选采集记录已由服务端完整保存。'
                        : '服务端已取消这个预览。',
                    );
                    onSaved();
                  },
                )
              }
            >
              {mutation.busy
                ? '正在处理…'
                : dialog === 'commit'
                  ? '确认保存采集记录'
                  : '确认取消预览'}
            </button>
          </div>
        </Dialog>
      )}
    </section>
  );
}
