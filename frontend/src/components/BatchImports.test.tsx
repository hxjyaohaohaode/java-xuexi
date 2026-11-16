import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { ApiError, api } from '../api';
import { TestWorkspace, deferred, testWorkspace } from '../test/workspace';
import { BatchImports } from './BatchImports';

const batch = {
  id: 'batch-1',
  sourceId: 'source-1',
  ownerId: 'test-owner',
  revision: 1,
  status: 'PREVIEW',
  processingNote: '按原始记录清理',
  createdAt: '2026-01-01T00:00:00Z',
  expiresAt: '2026-01-02T00:00:00Z',
  items: [
    {
      id: 'ready-1',
      fileName: '允许的材料.md',
      mediaType: 'text/markdown',
      byteSize: 100,
      fileHash: 'a'.repeat(64),
      originalHash: 'b'.repeat(64),
      parser: 'markdown-v1',
      status: 'READY',
      errorCode: null,
      message: '成功读取',
      originalMarkdown: '# 原始可核对内容',
      cleanedMarkdown: '原清洗稿',
      captureId: null,
    },
    {
      id: 'failed-1',
      fileName: '损坏.docx',
      mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      byteSize: 40,
      fileHash: 'c'.repeat(64),
      originalHash: '',
      parser: 'docx-v1',
      status: 'FAILED',
      errorCode: 'INVALID_DOCX',
      message: '未形成有效文档结构',
      originalMarkdown: '',
      cleanedMarkdown: '',
      captureId: null,
    },
  ],
};
const terminal = {
  ...batch,
  revision: 2,
  status: 'COMMITTED',
  items: batch.items.map((item) => ({
    ...item,
    status: item.status === 'READY' ? 'COMMITTED' : item.status,
    captureId: item.status === 'READY' ? 'capture-1' : null,
    originalMarkdown: '',
    cleanedMarkdown: '',
  })),
};
function view() {
  const onCapture = vi.fn();
  render(
    <TestWorkspace value={testWorkspace(['BATCH_INGESTION'])}>
      <BatchImports
        sources={[{ id: 'source-1', title: '课程资料' }]}
        onCapture={onCapture}
        onChange={vi.fn()}
      />
    </TestWorkspace>,
  );
  return onCapture;
}
const serve = () =>
  vi
    .spyOn(api, 'get')
    .mockImplementation((path) =>
      Promise.resolve(
        path.includes('?')
          ? [{ ...batch, itemCount: 2, readyCount: 1, failedCount: 1, committedCount: 0 }]
          : batch,
      ),
    );

it('commits only selected successfully parsed files and replaces terminal preview editors with retained capture links', async () => {
  serve();
  const pending = deferred<typeof terminal>();
  const request = vi.spyOn(api, 'request').mockReturnValue(pending.promise);
  const user = userEvent.setup();
  const onCapture = view();
  await user.click(await screen.findByRole('button', { name: /课程资料.*待确认预览/ }));
  const text = await screen.findByLabelText('清洗后的 Markdown');
  await user.clear(text);
  await user.type(text, '认真核对后的清洗内容');
  expect(screen.getByRole('checkbox', { name: '保存 损坏.docx' })).toBeDisabled();
  await user.click(screen.getByRole('button', { name: '保存所选采集记录' }));
  await user.click(
    within(screen.getByRole('dialog')).getByRole('button', { name: '确认保存采集记录' }),
  );
  expect(text).toBeDisabled();
  expect(request).toHaveBeenCalledWith(
    '/admin/import-batches/batch-1/commit',
    'POST',
    {
      expectedRevision: 1,
      items: [
        {
          itemId: 'ready-1',
          cleanedMarkdown: '认真核对后的清洗内容',
          processingNote: batch.processingNote,
        },
      ],
    },
    { idempotent: true },
  );
  await act(async () => pending.resolve(terminal));
  await screen.findByText('所选采集记录已由服务端完整保存。');
  expect(screen.queryByLabelText('清洗后的 Markdown')).not.toBeInTheDocument();
  expect(screen.queryByText('# 原始可核对内容')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '到来源查看原稿与清洗记录' }));
  expect(onCapture).toHaveBeenCalledWith('source-1');
});

it('retains separate cleaning text and notes after a real conflict or failed cancellation', async () => {
  serve();
  const request = vi
    .spyOn(api, 'request')
    .mockRejectedValue(new ApiError(409, 'CONFLICT', '批次版本已改变'));
  const user = userEvent.setup();
  view();
  await user.click(await screen.findByRole('button', { name: /课程资料.*待确认预览/ }));
  await user.type(await screen.findByLabelText('清洗后的 Markdown'), ' · 我的编辑');
  await user.clear(screen.getByLabelText('这份清洗稿的处理说明'));
  await user.type(screen.getByLabelText('这份清洗稿的处理说明'), '逐段核对来源边界');
  await user.click(screen.getByRole('button', { name: '保存所选采集记录' }));
  await user.click(
    within(screen.getByRole('dialog')).getByRole('button', { name: '确认保存采集记录' }),
  );
  await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
  await within(screen.getByRole('dialog')).findByText('批次版本已改变');
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '返回检查' }));
  expect(screen.getByLabelText('清洗后的 Markdown')).toHaveValue('原清洗稿 · 我的编辑');
  expect(screen.getByLabelText('这份清洗稿的处理说明')).toHaveValue('逐段核对来源边界');
  await user.click(screen.getByRole('button', { name: '取消这个预览' }));
  await user.click(
    within(screen.getByRole('dialog')).getByRole('button', { name: '确认取消预览' }),
  );
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  await within(screen.getByRole('dialog')).findByText('批次版本已改变');
  expect(screen.queryByText('服务端已取消这个预览。')).not.toBeInTheDocument();
});
