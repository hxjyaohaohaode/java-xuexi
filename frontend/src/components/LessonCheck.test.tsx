import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { api } from '../api';
import { TestWorkspace, testConcept, testWorkspace } from '../test/workspace';
import type { CoursewareScene, Exercise } from '../types';
import { InlinePractice, SelfCheck } from './LessonCheck';

const scene: CoursewareScene = {
  key: 'practice',
  kind: 'PRACTICE',
  title: '试着判断',
  body: '练习',
  prompt: null,
  exerciseId: 'ex-3',
  exerciseRevision: 3,
  chunkId: null,
  chunkRevision: null,
  attemptId: null,
  exampleId: null,
  exampleSha256: null,
};
const exercise: Exercise = {
  id: 'ex-3',
  revision: 3,
  releaseId: testConcept.releaseId,
  conceptIds: [testConcept.id],
  kind: 'SINGLE',
  difficulty: 1,
  prompt: '哪个值能安全写入？',
  options: [
    { id: 'a', text: 'Number' },
    { id: 'b', text: 'Integer' },
  ],
};
it('reveals the self-check explanation only after an explicit choice and never submits an attempt', async () => {
  const user = userEvent.setup();
  const post = vi.spyOn(api, 'request');
  render(
    <SelfCheck
      check={{ options: ['超类', '子类'], answerIndex: 1, explanation: '验证赋值关系。' }}
    />,
  );
  const check = screen.getByRole('button', { name: '核对我的判断' });
  expect(check).toBeDisabled();
  await user.click(screen.getByRole('radio', { name: '超类' }));
  expect(screen.queryByText('验证赋值关系。')).toBeNull();
  await user.click(check);
  expect(screen.getByRole('status')).toHaveTextContent('再看这个条件');
  await user.click(screen.getByRole('radio', { name: '子类' }));
  expect(screen.queryByRole('status')).toBeNull();
  await user.click(check);
  expect(screen.getByRole('status')).toHaveTextContent('判断正确');
  expect(post).not.toHaveBeenCalled();
});
it('reads the pinned question revision, allows retry and brings only the graded receipt to the tutor', async () => {
  const user = userEvent.setup(),
    workspace = testWorkspace();
  const get = vi
    .spyOn(api, 'get')
    .mockRejectedValueOnce(Error('读取失败'))
    .mockResolvedValue(exercise);
  const answer = {
    id: 'attempt-3',
    workspaceId: workspace.workspace.id,
    releaseId: exercise.releaseId,
    exerciseId: exercise.id,
    exerciseRevision: 3,
    status: 'GRADED',
    hintLevel: 0,
    score: 0,
    maximum: 1,
    feedback: '重新检查赋值方向。',
    misconceptionCodes: [],
  };
  const post = vi.spyOn(api, 'request').mockResolvedValue(answer);
  const discuss = vi.fn(),
    update = vi.fn(),
    receipt = vi.fn();
  render(
    <TestWorkspace value={workspace}>
      <InlinePractice
        concept={testConcept}
        activity={scene}
        onAttempt={receipt}
        onUpdate={update}
        updating={false}
        onDiscuss={discuss}
      />
    </TestWorkspace>,
  );
  await user.click(await screen.findByRole('button', { name: '重新读取' }));
  await user.click(await screen.findByRole('radio', { name: /Number/ }));
  await user.click(screen.getByRole('button', { name: '提交作答' }));
  expect(get).toHaveBeenCalledWith(
    '/exercises/ex-3?releaseId=release-1&revision=3',
    expect.any(AbortSignal),
  );
  expect(post).toHaveBeenCalledWith(
    '/attempts',
    'POST',
    expect.objectContaining({
      exerciseId: 'ex-3',
      exerciseRevision: 3,
      answer: { kind: 'CHOICE', optionIds: ['a'] },
    }),
    { idempotent: true },
  );
  await screen.findByText(answer.feedback);
  expect(receipt).toHaveBeenCalledWith(answer);
  await user.click(screen.getByRole('button', { name: '请导师讲解这次作答' }));
  expect(discuss).toHaveBeenCalledWith(expect.stringContaining(testConcept.title), answer.id);
  await user.click(screen.getByRole('button', { name: '依据这次反馈更新课件' }));
  await waitFor(() => expect(update).toHaveBeenCalledOnce());
});
it('does not offer a gradeable form for an exercise from another revision', async () => {
  vi.spyOn(api, 'get').mockResolvedValue({ ...exercise, revision: 4 });
  render(
    <TestWorkspace value={testWorkspace()}>
      <InlinePractice
        concept={testConcept}
        activity={scene}
        updating={false}
        onUpdate={vi.fn()}
        onAttempt={vi.fn()}
      />
    </TestWorkspace>,
  );
  await screen.findByRole('alert');
  expect(screen.queryByText(exercise.prompt)).toBeNull();
});

it('brings the actual granted hint into the tutor draft before grading', async () => {
  const user = userEvent.setup(),
    discuss = vi.fn();
  vi.spyOn(api, 'get').mockResolvedValue(exercise);
  const post = vi.spyOn(api, 'request').mockResolvedValue({ level: 1, text: '比较类型参数。' });
  render(
    <TestWorkspace value={testWorkspace(['EXERCISE_HINTS'])}>
      <InlinePractice
        concept={testConcept}
        activity={scene}
        updating={false}
        onUpdate={vi.fn()}
        onAttempt={vi.fn()}
        onDiscuss={discuss}
      />
    </TestWorkspace>,
  );
  expect(screen.queryByRole('button', { name: '把这条提示带给导师' })).toBeNull();
  await user.click(await screen.findByRole('button', { name: '查看第 1 级提示' }));
  await user.click(await screen.findByRole('button', { name: '把这条提示带给导师' }));
  expect(discuss).toHaveBeenCalledWith(expect.stringContaining('第 1 级提示「比较类型参数。」'));
  expect(post.mock.calls.map(([url]) => url)).toEqual(['/exercises/ex-3/hints']);
});
