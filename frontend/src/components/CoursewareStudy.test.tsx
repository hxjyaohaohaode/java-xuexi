import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { it, vi, expect } from 'vitest';
import { TestWorkspace, testWorkspace } from '../test/workspace';
import { api } from '../api';
import type { PersonalCourseware } from '../types';
import { CoursewareStudy } from './CoursewareStudy';

it('retries a failed model lecture in place, switches to evidence mode, and restores the corresponding server run', async () => {
  const workspace = testWorkspace(['STUDY_WORKFLOW', 'LLM_PLANNING']);
  const plan = {
    id: 'review-plan',
    workspaceId: workspace.workspace.id,
    conceptId: 'lesson-id',
  } as PersonalCourseware;
  const base = '/workspaces/' + plan.workspaceId + '/courseware/' + plan.conceptId + '/study';
  const history: Record<string, string> = { MODEL: 'failed-model' };
  function run(id: string) {
    return {
      id,
      workspaceId: plan.workspaceId,
      status: id === 'failed-model' ? 'FAILED' : 'COMPLETED',
    };
  }
  const get = vi.spyOn(api, 'get').mockImplementation(async (path) => {
    if (path.startsWith(base + '?')) {
      const mode = path.split('mode=')[1];
      return history[mode] ? run(history[mode]) : undefined;
    }
    if (path.endsWith('/study')) return { mode: 'MODEL', artifactIds: [], limitations: [] };
    if (path.startsWith('/agent-runs/')) return run(path.split('/').pop()!);
    throw Error(path);
  });
  const post = vi.spyOn(api, 'request').mockImplementation(async (path, _verb, body) => {
    expect(path).toBe(base);
    const { mode, planId } = body as { mode: string; planId: string };
    expect(planId).toBe(plan.id);
    history[mode] = 'next-' + mode;
    return run(history[mode]);
  });
  const view = () =>
    render(
      <TestWorkspace value={workspace}>
        <CoursewareStudy scope="qa" plan={plan} />
      </TestWorkspace>,
    );
  const user = userEvent.setup();
  const first = view();
  await user.click(await screen.findByRole('button', { name: '重新生成讲解课件' }));
  await screen.findByText('已完成');
  expect(post).toHaveBeenCalledTimes(1);
  await user.selectOptions(screen.getByLabelText('生成方式'), 'EVIDENCE_ONLY');
  await user.click(await screen.findByRole('button', { name: '生成讲解课件' }));
  await screen.findByText('已完成');
  expect(post).toHaveBeenCalledTimes(2);
  first.unmount();
  view();
  await screen.findByText('已完成');
  expect(get).toHaveBeenCalledWith(
    base + '?planId=review-plan&mode=EVIDENCE_ONLY',
    expect.anything(),
  );
  expect(post).toHaveBeenCalledTimes(2);
});
