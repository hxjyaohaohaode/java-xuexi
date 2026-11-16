import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { ApiError, api } from '../api';
import { TestWorkspace, testWorkspace } from '../test/workspace';
import { Operations, type OperationsStatus } from './Operations';

const snapshot: OperationsStatus = {
  generatedAt: '2026-11-16T02:00:00Z',
  requestMetrics: {
    since: '2026-11-16T01:00:00Z',
    completed: 0,
    inFlight: 1,
    serverErrors: 0,
    rateLimited: 0,
    p50UpperBoundMillis: null,
    p95UpperBoundMillis: null,
    maximumMillis: 0,
  },
  queues: {
    pendingEvents: 0,
    deadLetters: 0,
    pendingTutor: 0,
    pendingExports: 0,
    pendingCompilation: 0,
    pendingEvaluations: 0,
    failedEvaluations: 0,
    activeImportPreviews: 0,
    expiredImportPreviews: 0,
    storedEvaluationBytes: 1048576,
    importPreviewBytes: 2048,
  },
  measurementScope: 'Actual process histogram and SQL payload bytes',
};
function view(admin = true) {
  const context = testWorkspace(['OPERATIONS_METRICS']);
  if (admin) context.session.roles.push('ADMIN');
  return render(
    <TestWorkspace value={context}>
      <Operations />
    </TestWorkspace>,
  );
}

it('never requests administrator snapshots for an ordinary learner', () => {
  const get = vi.spyOn(api, 'get');
  view(false);
  expect(screen.getByText('运行状态仅向管理员开放')).toBeInTheDocument();
  expect(get).not.toHaveBeenCalled();
});

it('keeps unavailable latency samples distinct from zero and describes actual stored bytes', async () => {
  vi.spyOn(api, 'get').mockResolvedValue(snapshot);
  view();
  await screen.findByText('后台队列');
  expect(screen.getAllByText('暂无样本')).toHaveLength(3);
  expect(screen.queryByText('0 ms')).not.toBeInTheDocument();
  expect(screen.getByText('1.00 MiB')).toBeInTheDocument();
  expect(screen.getByText('2.0 KiB')).toBeInTheDocument();
});

it('labels a retained snapshot after a network failure and removes it after privilege revocation', async () => {
  const get = vi.spyOn(api, 'get').mockResolvedValue({
    ...snapshot,
    requestMetrics: { ...snapshot.requestMetrics, completed: 777 },
  });
  const user = userEvent.setup();
  view();
  await screen.findByText('777');
  await user.click(screen.getByRole('checkbox', { name: '每 5 秒刷新' }));
  get.mockRejectedValueOnce(new ApiError(503, 'UNAVAILABLE', '服务暂时不可用'));
  await user.click(screen.getByRole('button', { name: '刷新运行状态' }));
  await screen.findByText('上次成功读取的数据');
  expect(screen.getByText('777')).toBeInTheDocument();
  get.mockRejectedValueOnce(new ApiError(403, 'ADMIN_REQUIRED', '需要当前管理员权限'));
  await user.click(screen.getByRole('button', { name: '刷新运行状态' }));
  await screen.findByText('管理权限已变化');
  await waitFor(() => expect(screen.queryByText('777')).not.toBeInTheDocument());
  expect(screen.queryByText('后台队列')).not.toBeInTheDocument();
  expect(screen.getByRole('checkbox', { name: '每 5 秒刷新' })).toBeDisabled();
});
