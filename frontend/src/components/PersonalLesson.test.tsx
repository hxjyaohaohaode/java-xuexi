import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { api } from '../api';
import { TestWorkspace, testConcept, testWorkspace } from '../test/workspace';
import type { PersonalCourseware } from '../types';
import { PersonalLesson } from './PersonalLesson';

vi.mock('./MaicLessonPlayer', () => ({
  default: ({ personal }: { personal: PersonalCourseware }) => <p>{personal.id}</p>,
}));
const plan = {
  id: 'plan-v1',
  revision: 1,
  releaseId: testConcept.releaseId,
  workspaceId: 'workspace-1',
  conceptId: testConcept.id,
  requestedApproach: 'AUTO',
  strategy: 'FOUNDATION',
  pace: 'GUIDED',
  reasons: ['循序渐进'],
  scenes: [{ key: 'read', kind: 'READ', body: '已发布的讲解' }],
} as PersonalCourseware;
it('keeps the draft on a failed generation, counts Unicode characters, and clears only on explicit submission', async () => {
  const user = userEvent.setup();
  vi.spyOn(api, 'get').mockResolvedValue(plan);
  const generated = {
    ...plan,
    id: 'plan-v2',
    revision: 2,
    brief: { interest: '🎹'.repeat(160), requirements: '先推导再练习' },
  };
  const post = vi
    .spyOn(api, 'request')
    .mockRejectedValueOnce(Error('暂时离线'))
    .mockResolvedValueOnce(generated)
    .mockResolvedValueOnce({ ...plan, id: 'plan-v3', revision: 3 });
  render(
    <TestWorkspace value={testWorkspace()}>
      <PersonalLesson
        concept={testConcept}
        scope="local"
        onActivity={vi.fn()}
        onAttempt={vi.fn()}
        onDiscuss={vi.fn()}
        courseEvidence={testConcept.evidence}
      />
    </TestWorkspace>,
  );
  await user.click(await screen.findByRole('button', { name: '调整兴趣与要求' }));
  const interest = screen.getByRole('textbox', { name: '我的兴趣' });
  const requirements = screen.getByRole('textbox', { name: '本节学习要求' });
  expect(document.activeElement).toBe(interest);
  await user.paste('🎹'.repeat(161));
  const save = screen.getByRole('button', { name: '按我的要求生成课件' });
  expect(save).toBeDisabled();
  await user.clear(interest);
  await user.paste(generated.brief.interest);
  await user.type(requirements, generated.brief.requirements);
  expect(post).not.toHaveBeenCalled();
  await user.click(save);
  await screen.findByRole('alert');
  expect(screen.getByText('plan-v1')).toBeVisible();
  expect(interest).toHaveValue(generated.brief.interest);
  await user.click(save);
  await screen.findByText('plan-v2');
  expect(post).toHaveBeenNthCalledWith(
    2,
    '/workspaces/workspace-1/courseware/concept-1',
    'POST',
    { approach: 'AUTO', brief: generated.brief },
    { idempotent: true },
  );
  expect(document.activeElement).toBe(screen.getByRole('button', { name: '调整兴趣与要求' }));
  await user.click(screen.getByRole('button', { name: '调整兴趣与要求' }));
  await user.click(screen.getByRole('button', { name: '清除本节兴趣与要求' }));
  await screen.findByText('plan-v3');
  expect(post).toHaveBeenLastCalledWith(
    '/workspaces/workspace-1/courseware/concept-1',
    'POST',
    { approach: 'AUTO', brief: { interest: '', requirements: '' } },
    { idempotent: true },
  );
});
