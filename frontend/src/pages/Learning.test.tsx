import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { api } from '../api';
import { TestWorkspace, testConcept, testWorkspace } from '../test/workspace';
import { Learning } from './Learning';
import type { Concept } from '../types';

const nextConcept: Concept = {
  ...testConcept,
  id: 'c-2',
  code: 'C02',
  title: '类型推断',
  prerequisiteIds: [testConcept.id],
};
const concepts = [testConcept, nextConcept];
const repair = {
  conceptId: testConcept.id,
  code: testConcept.code,
  title: testConcept.title,
  action: 'REPAIR',
  reason: '最近一次作答有误',
  exerciseId: 'repair-2',
  attemptId: 'attempt-2',
  readCompleted: true,
  gradedAttempts: 1,
  independentCorrectFamilies: 0,
};

function resources() {
  return vi.spyOn(api, 'get').mockImplementation(async (url) => {
    if (url.startsWith('/concepts?')) return { items: concepts };
    if (url.endsWith('/learning-path'))
      return { workspaceId: 'workspace-1', releaseId: 'release-1', steps: [repair] };
    if (url.startsWith('/exercises?'))
      return {
        items: [1, 2].map((n) => ({
          id: 'repair-' + n,
          revision: 1,
          releaseId: 'release-1',
          difficulty: 1,
          kind: 'READING',
          answerKind: 'CHOICE',
          prompt: '练习 ' + n,
          options: [{ id: 'A', text: '选项 A' }],
          conceptIds: [testConcept.id],
        })),
        nextCursor: null,
      };
    if (url.startsWith('/tutor/conversations/')) return { messages: [], nextCursor: null };
    if (url === '/me/progress') return { items: [], rebuilding: false };
    if (url === '/me/reviews')
      return [
        {
          id: 'review-other',
          conceptId: nextConcept.id,
          reason: '另一课的复习',
          status: 'PENDING',
          dueAt: '2026-01-01',
        },
      ];
    throw Error(url);
  });
}

it('opens the recommended concept and exact exercise; independent deep links restore the requested exercise', async () => {
  resources();
  const value = { ...testWorkspace(['LEARNING_PATH']), openClassroom: vi.fn() };
  const user = userEvent.setup();
  const view = render(
    <TestWorkspace value={value}>
      <Learning />
    </TestWorkspace>,
  );
  await user.click(await screen.findByRole('button', { name: '复练这个概念' }));
  expect(value.openClassroom).toHaveBeenCalledWith(testConcept.id, 'practice', 'repair-2');
  view.rerender(
    <TestWorkspace
      value={{
        ...value,
        selection: testConcept.id,
        classroomScene: 'practice',
        classroomExercise: 'repair-2',
      }}
    >
      <Learning />
    </TestWorkspace>,
  );
  expect(await screen.findByLabelText('本节练习')).toHaveValue('repair-2');
  await user.click(screen.getByRole('button', { name: '本节复盘' }));
  expect(value.openClassroom).toHaveBeenLastCalledWith(testConcept.id, 'review', undefined);
  view.rerender(
    <TestWorkspace value={{ ...value, selection: testConcept.id, classroomScene: 'review' }}>
      <Learning />
    </TestWorkspace>,
  );
  await screen.findByRole('heading', { name: '还没有独立作答证据' });
  expect(screen.queryByText('另一课的复习')).not.toBeInTheDocument();
});

it('keeps tutor drafts per lesson and submits the explicitly attached attempt with its context snapshot', async () => {
  resources();
  const write = vi.spyOn(api, 'request').mockImplementation(async (url) => {
    if (url === '/tutor/conversations') return { id: 'chat-1' };
    if (url.endsWith('/context')) return { id: 'snap-1' };
    if (url === '/tutor/requests') return { id: 'msg-1' };
    throw Error(url);
  });
  const user = userEvent.setup();
  const value = {
    ...testWorkspace(['LEARNING_PATH', 'TUTOR_EVIDENCE_ONLY']),
    selection: testConcept.id,
    openClassroom: vi.fn(),
  };
  const view = render(
    <TestWorkspace value={value}>
      <Learning />
    </TestWorkspace>,
  );
  await user.click(await screen.findByRole('button', { name: '课堂导师' }));
  const input = await screen.findByLabelText('输入你的问题');
  await user.type(input, '请核对我的判断');
  view.rerender(
    <TestWorkspace value={{ ...value, selection: nextConcept.id }}>
      <Learning />
    </TestWorkspace>,
  );
  expect(await screen.findByLabelText('输入你的问题')).toHaveValue('');
  view.rerender(
    <TestWorkspace value={value}>
      <Learning />
    </TestWorkspace>,
  );
  expect(await screen.findByLabelText('输入你的问题')).toHaveValue('请核对我的判断');
  await user.click(await screen.findByLabelText('附上本节最近一次关联作答'));
  await user.click(screen.getByRole('button', { name: '发送问题' }));
  await waitFor(() =>
    expect(write).toHaveBeenCalledWith(
      '/tutor/requests',
      'POST',
      expect.objectContaining({
        attemptId: 'attempt-2',
        snapshotId: 'snap-1',
        question: '【PECS · 生产者与消费者】\n请核对我的判断',
      }),
      { idempotent: true },
    ),
  );
  expect(write).toHaveBeenCalledWith('/workspaces/workspace-1/context', 'POST', {
    attemptIds: ['attempt-2'],
  });
  const pane = screen.getByRole('complementary', { name: '课堂导师' });
  await user.click(within(pane).getByRole('button', { name: '关闭课堂导师' }));
  expect(screen.getByRole('button', { name: '课堂导师' })).toHaveFocus();
});

it('reads the complete selected lesson when the directory ends at the excerpt boundary', async () => {
  const excerpt = '文'.repeat(2999);
  const fullText = excerpt + '🌿这是目录外的课程原文。';
  const card = { ...testConcept.evidence[0], excerpt };
  const brief = { ...testConcept, evidence: [card] };
  const get = vi.spyOn(api, 'get').mockImplementation(async (url) => {
    if (url.startsWith('/concepts?')) return { items: [brief] };
    if (url === '/concepts/concept-1?releaseId=release-1')
      return { ...brief, evidence: [{ ...card, fullText }] };
    throw Error(url);
  });
  render(
    <TestWorkspace value={testWorkspace()}>
      <Learning />
    </TestWorkspace>,
  );
  const paragraph = await screen.findByText(/这是目录外的课程原文/);
  expect(paragraph.textContent).toBe(fullText);
  expect(get).toHaveBeenCalledWith('/concepts/concept-1?releaseId=release-1', expect.anything());
});
