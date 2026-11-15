import { StrictMode } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { api } from '../api';
import App from '../App';
import { WorkspaceContext, type WorkspaceState } from '../context';
import type { Preference } from '../types';
import { applyPreference, setPreferenceOwner, SettingsPage } from './Personal';

beforeEach(() => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
  setPreferenceOwner('owner');
});
afterEach(() => {
  setPreferenceOwner(null);
  vi.unstubAllGlobals();
  delete document.documentElement.dataset.theme;
  delete document.documentElement.dataset.reducedMotion;
  document.documentElement.style.removeProperty('--code-font-size');
});

const context: WorkspaceState = {
  workspace: {
    id: 'workspace-1',
    releaseId: 'release-1',
    title: 'Test',
    guidanceMode: 'ON_DEMAND',
    revision: 1,
    eventSequence: 0,
    status: 'ACTIVE',
  },
  session: { userId: 'owner', username: 'student', roles: ['LEARNER'] },
  capabilities: {
    runtimeVersion: 'test',
    enabled: ['USER_PREFERENCES'],
    unavailable: [],
    businessReady: false,
  },
  tick: 0,
  refresh: vi.fn(),
  events: [],
  navigate: vi.fn(),
  selection: '',
  can: (capability) => capability === 'USER_PREFERENCES',
};

it('preserves two saved preference revisions despite the stale initial query', async () => {
  const original: Preference = {
    theme: 'system',
    reducedMotion: false,
    codeFontSize: 16,
    revision: 1,
  };
  const first: Preference = { ...original, theme: 'dark', reducedMotion: true, revision: 2 };
  const second: Preference = { ...original, theme: 'light', reducedMotion: false, revision: 3 };
  // The GET remains at revision 1 for the whole mounted page, as in the production hook.
  vi.spyOn(api, 'get').mockImplementation((path) =>
    Promise.resolve(path === '/me/preferences' ? original : context.workspace),
  );
  const pending: Array<(value: Preference) => void> = [];
  const request = vi
    .spyOn(api, 'request')
    .mockImplementation(() => new Promise<Preference>((resolve) => pending.push(resolve)));
  const user = userEvent.setup();
  render(
    <WorkspaceContext.Provider value={context}>
      <SettingsPage />
    </WorkspaceContext.Provider>,
  );
  const theme = await screen.findByLabelText('主题');
  const reducedMotion = screen.getByLabelText('减少界面动效');
  const save = screen.getByRole('button', { name: '保存显示偏好' });
  await user.selectOptions(theme, 'dark');
  await user.click(reducedMotion);
  await user.click(save);
  expect(theme).toBeDisabled();
  expect(reducedMotion).toBeDisabled();
  expect(screen.getByRole('slider')).toBeDisabled();
  await act(async () => pending.shift()!(first));
  await screen.findByText('服务端已保存显示偏好。');
  expect(theme).toHaveValue('dark');
  expect(reducedMotion).toBeChecked();
  expect(document.documentElement.dataset.theme).toBe('dark');
  expect(document.documentElement.dataset.reducedMotion).toBe('true');
  expect(request).toHaveBeenNthCalledWith(
    1,
    '/me/preferences',
    'PUT',
    { ...first, revision: 1 },
    { headers: { 'If-Match': '"1"' } },
  );

  await user.selectOptions(theme, 'light');
  await user.click(reducedMotion);
  await user.click(save);
  expect(theme).toBeDisabled();
  expect(reducedMotion).toBeDisabled();
  await act(async () => pending.shift()!(second));
  await screen.findByText('服务端已保存显示偏好。');
  expect(theme).toHaveValue('light');
  expect(reducedMotion).not.toBeChecked();
  expect(document.documentElement.dataset.theme).toBe('light');
  expect(document.documentElement.dataset.reducedMotion).toBe('false');
  expect(request).toHaveBeenNthCalledWith(
    2,
    '/me/preferences',
    'PUT',
    { ...second, revision: 2 },
    { headers: { 'If-Match': '"2"' } },
  );
});

it('ignores same-owner older and equal revisions from late preference reads', () => {
  const initial: Preference = {
    theme: 'system',
    reducedMotion: false,
    codeFontSize: 16,
    revision: 1,
  };
  applyPreference(initial, 'owner');
  applyPreference(
    { ...initial, theme: 'dark', reducedMotion: true, codeFontSize: 20, revision: 2 },
    'owner',
  );
  // The initial SignedIn GET may finish after the Settings save.
  applyPreference(initial, 'owner');
  applyPreference({ ...initial, theme: 'light', revision: 2 }, 'owner');
  expect(document.documentElement.dataset.theme).toBe('dark');
  expect(document.documentElement.dataset.reducedMotion).toBe('true');
  expect(document.documentElement.style.getPropertyValue('--code-font-size')).toBe('20px');
});

it('clears the old owner and media listener while accepting a new account with a lower revision', () => {
  const initial: Preference = {
    theme: 'system',
    reducedMotion: true,
    codeFontSize: 20,
    revision: 9,
  };
  applyPreference(initial, 'owner');
  const oldMedia = vi.mocked(matchMedia).mock.results[0].value;
  const queuedListener = vi.mocked(oldMedia.addEventListener).mock.calls[0][1] as () => void;
  setPreferenceOwner(null);
  expect(oldMedia.removeEventListener).toHaveBeenCalledWith('change', queuedListener);
  expect(document.documentElement.dataset.theme).toBeUndefined();
  expect(document.documentElement.dataset.reducedMotion).toBeUndefined();
  expect(document.documentElement.style.getPropertyValue('--code-font-size')).toBe('');
  applyPreference({ ...initial, theme: 'dark', revision: 10 }, 'owner');
  expect(document.documentElement.dataset.theme).toBeUndefined();
  setPreferenceOwner('another-owner');
  applyPreference(
    { theme: 'light', reducedMotion: false, codeFontSize: 16, revision: 1 },
    'another-owner',
  );
  applyPreference({ ...initial, theme: 'dark', revision: 10 }, 'owner');
  queuedListener();
  expect(document.documentElement.dataset.theme).toBe('light');
  expect(document.documentElement.dataset.reducedMotion).toBe('false');
  expect(document.documentElement.style.getPropertyValue('--code-font-size')).toBe('16px');
});

it('initializes the confirmed preference owner after StrictMode bootstrap replay and clears it on unmount', async () => {
  setPreferenceOwner(null);
  vi.spyOn(api, 'request').mockResolvedValue(context.session);
  vi.spyOn(api, 'get').mockImplementation((path) =>
    Promise.resolve(
      path === '/capabilities'
        ? context.capabilities
        : path === '/me/preferences'
          ? { theme: 'dark', reducedMotion: true, codeFontSize: 18, revision: 1 }
          : path === '/releases/active'
            ? { releaseId: 'release-1' }
            : [],
    ),
  );
  const { unmount } = render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
  await waitFor(() => expect(document.documentElement.dataset.theme).toBe('dark'));
  expect(document.documentElement.dataset.reducedMotion).toBe('true');
  unmount();
  applyPreference({ theme: 'dark', reducedMotion: true, codeFontSize: 18, revision: 2 }, 'owner');
  expect(document.documentElement.dataset.theme).toBeUndefined();
});
