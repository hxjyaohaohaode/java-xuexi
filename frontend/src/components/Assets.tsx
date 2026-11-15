import { useEffect, useRef, useState } from 'react';
import { Download, Image, RefreshCw, Upload, X } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../context';
import { useMutation, useResource } from '../hooks';
import { Badge, Confirmed, Dialog, Empty, ErrorNotice, Loading } from './ui';
import type { Asset, AssetPage } from '../types';

const acceptedTypes = [
  'image/png',
  'image/jpeg',
  'image/jpg',
  'audio/wav',
  'audio/x-wav',
  'audio/wave',
  'audio/vnd.wave',
  'audio/mpeg',
  'audio/mp3',
  'video/mp4',
  'application/octet-stream',
];
export function assetPath(asset: Asset) {
  const expected = `/api/v1/assets/${asset.id}/download`;
  return asset.status === 'READY' && asset.downloadPath === expected ? expected : undefined;
}
const size = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(bytes < 1048576 ? 2 : 1)} MiB`;

export function AssetsPanel({ onInsert }: { onInsert?: (asset: Asset) => void }) {
  const { workspace, tick, refresh, events } = useWorkspace();
  const [cursor, setCursor] = useState('');
  const resource = useResource<AssetPage>(
    `/workspaces/${workspace.id}/assets?limit=30${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
    tick,
  );
  const [items, setItems] = useState<Asset[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [notice, setNotice] = useState('');
  const [validationError, setValidationError] = useState('');
  const [removing, setRemoving] = useState<Asset | null>(null);
  const [preview, setPreview] = useState<Asset | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const mutation = useMutation();
  const revoked = new Set(
    events
      .filter((event) => event.type === 'ASSET_REVOKED')
      .map((event) => String(event.data.assetId)),
  );
  const visibleItems = items.filter((item) => !revoked.has(item.id));
  useEffect(() => {
    if (resource.data)
      setItems((current) =>
        cursor
          ? [
              ...new Map(
                [...current, ...resource.data!.items].map((item) => [item.id, item]),
              ).values(),
            ]
          : resource.data!.items,
      );
  }, [resource.data, cursor]);
  const reload = () => {
    setCursor('');
    resource.reload();
    refresh();
  };
  const select = (value: File | undefined) => {
    setNotice('');
    setValidationError('');
    setFile(null);
    if (!value) return;
    if (value.size > (resource.data?.usage.maxFileBytes || 8 * 1024 * 1024)) {
      setValidationError('单个素材最多 8 MiB，请压缩后重试。');
      return;
    }
    if (value.size === 0) {
      setValidationError('这个文件是空的，请选择有内容的素材。');
      return;
    }
    if (value.type && !acceptedTypes.includes(value.type)) {
      setValidationError('支持 PNG、JPEG、WAV、MP3 和 MP4；不接受 SVG 或可执行文件。');
      return;
    }
    setFile(value);
  };
  return (
    <section className="assets-panel" aria-label="工作空间私有素材">
      <div className="row spread wrap">
        <h3>
          <Image size={19} />
          工作空间素材
        </h3>
        <div className="row">
          <Badge>当前账号私有</Badge>
          <button
            className="icon-button"
            aria-label="刷新素材列表"
            disabled={resource.loading}
            onClick={reload}
          >
            <RefreshCw size={16} />
          </button>
        </div>
      </div>
      <p className="muted small">
        上传自己的图片、音频或视频，用于学习作品。支持 PNG、JPEG、PCM WAV、MP3、MP4，单文件最多 8
        MiB。
      </p>
      {resource.data && (
        <div className="asset-quota">
          <meter
            value={resource.data.usage.usedBytes}
            min={0}
            max={resource.data.usage.limitBytes}
            aria-label="素材存储用量"
          />
          <span>
            {size(resource.data.usage.usedBytes)} / {size(resource.data.usage.limitBytes)} ·{' '}
            {resource.data.usage.activeFiles} 个文件
          </span>
        </div>
      )}
      <form
        className="asset-upload"
        onSubmit={(e) => {
          e.preventDefault();
          if (!file) return;
          const form = new FormData();
          form.append('file', file);
          void mutation.run(
            () =>
              api.request<Asset>(`/workspaces/${workspace.id}/assets`, 'POST', form, {
                idempotent: true,
                timeoutMs: 60000,
              }),
            (value) => {
              setFile(null);
              if (input.current) input.current.value = '';
              setNotice(`“${value.filename}”已上传并保存。`);
              reload();
            },
          );
        }}
      >
        <label className="asset-file-picker">
          <Upload size={18} />
          <span>{file ? file.name : '选择一个素材文件'}</span>
          <input
            ref={input}
            type="file"
            accept=".png,.jpg,.jpeg,.wav,.mp3,.mp4"
            disabled={mutation.busy}
            aria-label="选择素材文件"
            onChange={(e) => select(e.target.files?.[0])}
          />
        </label>
        <button disabled={mutation.busy || !file} className="primary">
          {mutation.busy ? '正在上传与检查…' : '上传素材'}
        </button>
      </form>
      {validationError && (
        <p className="notice error" role="alert">
          {validationError}
        </p>
      )}
      <ErrorNotice error={mutation.error} />
      <ErrorNotice error={resource.error} retry={resource.reload} />
      {notice && <Confirmed>{notice}</Confirmed>}
      {resource.loading && !visibleItems.length ? (
        <Loading />
      ) : visibleItems.length ? (
        <div className="asset-grid">
          {visibleItems.map((asset) => (
            <article key={asset.id} className="asset-card">
              {asset.mimeType.startsWith('image/') && assetPath(asset) ? (
                <button
                  className="asset-thumbnail"
                  aria-label={`预览 ${asset.filename}`}
                  onClick={() => setPreview(asset)}
                >
                  <img src={assetPath(asset)} alt={asset.filename} loading="lazy" />
                </button>
              ) : (
                <button className="asset-media-label" onClick={() => setPreview(asset)}>
                  {asset.mimeType.startsWith('video/') ? '视频' : '音频'} · 预览
                </button>
              )}
              <strong>{asset.filename}</strong>
              <small className="muted">
                {size(asset.byteSize)}
                {asset.width && asset.height ? ` · ${asset.width} × ${asset.height}` : ''}
              </small>
              <div className="row wrap">
                {onInsert && asset.mimeType.startsWith('image/') && (
                  <button
                    className="text-button"
                    disabled={mutation.busy || !assetPath(asset)}
                    onClick={() => onInsert(asset)}
                  >
                    插入当前幻灯片
                  </button>
                )}
                {assetPath(asset) && (
                  <a className="button text-button" href={assetPath(asset)} download>
                    <Download size={15} />
                    下载
                  </a>
                )}
                <button
                  className="text-button danger-text"
                  disabled={mutation.busy}
                  onClick={() => setRemoving(asset)}
                >
                  移除
                </button>
              </div>
            </article>
          ))}
        </div>
      ) : (
        !resource.error && <Empty title="还没有素材">选择一个自己的图片或音视频文件开始。</Empty>
      )}
      {resource.data?.nextCursor && (
        <button disabled={resource.loading} onClick={() => setCursor(resource.data!.nextCursor!)}>
          继续加载素材
        </button>
      )}
      <p className="muted small">
        上传会检查文件格式结构，不代表病毒扫描或模型生成。这些文件只通过当前账号授权访问。
      </p>
      {preview && (
        <Dialog title={`预览 ${preview.filename}`} onClose={() => setPreview(null)}>
          <div className="row spread">
            <h2>{preview.filename}</h2>
            <button
              className="icon-button"
              aria-label="关闭素材预览"
              onClick={() => setPreview(null)}
            >
              <X size={18} />
            </button>
          </div>
          {revoked.has(preview.id) ? (
            <p className="notice warning">这个素材已在另一处移除，预览已停止。</p>
          ) : (
            assetPath(preview) &&
            (preview.mimeType.startsWith('image/') ? (
              <img className="asset-preview" src={assetPath(preview)} alt={preview.filename} />
            ) : preview.mimeType.startsWith('audio/') ? (
              <audio
                className="asset-preview"
                controls
                preload="metadata"
                src={assetPath(preview)}
              />
            ) : (
              <video
                className="asset-preview"
                controls
                preload="metadata"
                playsInline
                src={assetPath(preview)}
              />
            ))
          )}
          <small className="muted">内容摘要 {preview.contentHash}</small>
        </Dialog>
      )}
      {removing && (
        <Dialog
          title="移除素材"
          onClose={() => {
            if (!mutation.busy) setRemoving(null);
          }}
        >
          <h2>移除“{removing.filename}”？</h2>
          <p>文件内容会删除，依赖此素材的作品将标记为来源失效。需要保留时请先下载。</p>
          <div className="row wrap">
            <button disabled={mutation.busy} onClick={() => setRemoving(null)}>
              保留素材
            </button>
            <button
              className="danger-button"
              disabled={mutation.busy}
              onClick={() =>
                void mutation.run(
                  () => api.request(`/assets/${removing.id}`, 'DELETE'),
                  () => {
                    setRemoving(null);
                    setItems((current) => current.filter((item) => item.id !== removing.id));
                    setNotice('素材已移除，关联作品会重新检查来源。');
                    reload();
                  },
                )
              }
            >
              {mutation.busy ? '正在移除…' : '确认移除'}
            </button>
          </div>
          <ErrorNotice error={mutation.error} />
        </Dialog>
      )}
    </section>
  );
}
