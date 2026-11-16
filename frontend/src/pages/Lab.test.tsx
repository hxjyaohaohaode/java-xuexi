import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { api } from '../api';
import { TestWorkspace, testWorkspace } from '../test/workspace';
import { Lab } from './Lab';

it('submits lower-bound writes with the container and value in the actual evaluator order', async () => {
  vi.spyOn(api, 'get').mockResolvedValue([]);
  const request = vi.spyOn(api, 'request').mockResolvedValue({
    id: 'lab-write',
    verdict: 'ALLOWED',
    explanation: 'Integer 可写入声明下界为 Integer 的容器。',
    ruleCode: 'SUPER_WRITE',
    rulesVersion: 'finite-v1',
    jobId: null,
  });
  const user = userEvent.setup();
  render(
    <TestWorkspace value={testWorkspace(['FINITE_TYPE_LAB'])}>
      <Lab />
    </TestWorkspace>,
  );
  await user.click(screen.getByRole('button', { name: '下界写入' }));
  expect(screen.getByLabelText('容器类型')).toHaveValue('List<? super Integer>');
  expect(screen.getByLabelText('写入值的类型')).toHaveValue('Integer');
  await user.click(screen.getByRole('button', { name: '检验类型关系' }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith(
      '/labs/sessions',
      'POST',
      expect.objectContaining({
        labType: 'READ_WRITE',
        operation: 'WRITE',
        sourceType: 'List<? super Integer>',
        targetType: 'Integer',
      }),
      { idempotent: true },
    ),
  );
  await screen.findByText('Integer 可写入声明下界为 Integer 的容器。');
});
