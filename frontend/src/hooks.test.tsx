import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { useMutation, useResource } from './hooks';
describe('late responses never cross workspace scopes', () => {
  it('aborts the old read and ignores a transport that resolves it late', async () => {
    let resolveOld!: (value: unknown) => void;
    let oldSignal: AbortSignal | undefined;
    vi.spyOn(api, 'get').mockImplementation((path, signal) => {
      if (path === '/old') {
        oldSignal = signal;
        return new Promise((resolve) => {
          resolveOld = resolve;
        });
      }
      return Promise.resolve({ workspace: 'new' });
    });
    const { result, rerender } = renderHook(
      ({ path }) => useResource<{ workspace: string }>(path),
      { initialProps: { path: '/old' } },
    );
    rerender({ path: '/new' });
    await waitFor(() => expect(result.current.data?.workspace).toBe('new'));
    expect(oldSignal?.aborted).toBe(true);
    await act(async () => resolveOld({ workspace: 'old' }));
    expect(result.current.data?.workspace).toBe('new');
  });
  it('does not invoke a mutation commit after its workspace unmounts', async () => {
    let finish!: (value: string) => void;
    const commit = vi.fn();
    const { result, unmount } = renderHook(() => useMutation());
    let running: Promise<unknown> | undefined;
    act(() => {
      running = result.current.run(
        () =>
          new Promise<string>((resolve) => {
            finish = resolve;
          }),
        commit,
      );
    });
    unmount();
    await act(async () => {
      finish('old workspace artifact');
      await running;
    });
    expect(commit).not.toHaveBeenCalled();
  });
});
