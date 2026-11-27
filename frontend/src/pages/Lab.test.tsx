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

it('submits the Python draft and stdin while retaining the Java lesson buffer', async () => {
  vi.spyOn(api, 'get').mockImplementation(async (path) =>
    path === '/compile-jobs/new-run'
      ? {
          id: 'new-run',
          status: 'SUCCEEDED',
          language: 'PYTHON',
          mode: 'COMPILE_AND_RUN',
          output: '42',
          diagnostics: [],
        }
      : [],
  );
  const request = vi.spyOn(api, 'request').mockResolvedValue({ id: 'new-run' });
  const user = userEvent.setup();
  render(
    <TestWorkspace value={testWorkspace(['COMPILE_ONLY', 'UNTRUSTED_CODE_EXECUTION'])}>
      <Lab initialConceptCode="G10" />
    </TestWorkspace>,
  );
  const java = (screen.getByLabelText(/Main.java/) as HTMLTextAreaElement).value;
  await user.selectOptions(screen.getByLabelText('语言和任务'), 'RUN_PYTHON');
  const editor = screen.getByLabelText(/main.py/);
  await user.clear(editor);
  await user.type(editor, 'print(input())');
  await user.type(screen.getByLabelText(/标准输入/), '42');
  await user.click(screen.getByRole('button', { name: '运行代码' }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith(
      '/compile-jobs',
      'POST',
      expect.objectContaining({
        mode: 'COMPILE_AND_RUN',
        language: 'PYTHON',
        source: 'print(input())',
        stdin: '42',
      }),
      { idempotent: true },
    ),
  );
  await user.selectOptions(screen.getByLabelText('语言和任务'), 'RUN_JAVA');
  expect(screen.getByLabelText(/Main.java/)).toHaveValue(java);
});
