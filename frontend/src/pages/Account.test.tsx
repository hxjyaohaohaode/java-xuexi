import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { ApiError, api } from '../api';
import { WorkspaceContext, type WorkspaceState } from '../context';
import { AccountPanel } from './Account';

it('retains password input and session after wrong current password, and ends the session only after confirmed success', async () => {
  const endSession = vi.fn();
  const context: WorkspaceState = {
    session: { userId: 'owner', username: 'student', roles: ['LEARNER'] },
    workspace: {
      id: 'space',
      title: '课程',
      releaseId: 'release',
      revision: 1,
      eventSequence: 0,
      guidanceMode: 'ON_DEMAND',
      status: 'ACTIVE',
    },
    tick: 0,
    events: [],
    refresh: vi.fn(),
    navigate: vi.fn(),
    selection: '',
    can: () => true,
    endSession,
    capabilities: {
      enabled: ['ACCOUNT_LIFECYCLE'],
      unavailable: [],
      businessReady: false,
      runtimeVersion: 'test',
    },
  };
  vi.spyOn(api, 'get').mockImplementation((path) =>
    Promise.resolve(
      path === '/me/profile'
        ? {
            userId: 'owner',
            username: 'student',
            displayName: '学习者',
            roles: ['LEARNER'],
            revision: 1,
            createdAt: '2026-01-01T00:00:00Z',
          }
        : { items: [] },
    ),
  );
  const request = vi
    .spyOn(api, 'request')
    .mockRejectedValueOnce(new ApiError(400, 'CURRENT_PASSWORD_INVALID', '当前密码不正确'))
    .mockResolvedValue(undefined);
  const user = userEvent.setup();
  render(
    <WorkspaceContext.Provider value={context}>
      <AccountPanel />
    </WorkspaceContext.Provider>,
  );
  await user.type(screen.getByLabelText('当前密码'), 'old-password-value');
  await user.type(screen.getByLabelText(/新密码至少/), 'new-password-value');
  await user.type(screen.getByLabelText('再次输入新密码'), 'new-password-value');
  await user.click(screen.getByRole('button', { name: '修改密码并退出旧会话' }));
  expect(await screen.findByText('当前密码不正确')).toBeInTheDocument();
  expect(endSession).not.toHaveBeenCalled();
  expect(screen.getByLabelText('当前密码')).toHaveValue('old-password-value');
  expect(JSON.stringify(sessionStorage)).not.toContain('password-value');
  await user.click(screen.getByRole('button', { name: '修改密码并退出旧会话' }));
  await waitFor(() =>
    expect(endSession).toHaveBeenCalledWith(expect.stringContaining('密码已修改')),
  );
  expect(request).toHaveBeenLastCalledWith('/me/password', 'PUT', {
    currentPassword: 'old-password-value',
    newPassword: 'new-password-value',
  });
});
