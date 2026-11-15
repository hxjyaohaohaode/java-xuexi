import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { useMutation, usePolling, useResource } from './hooks';
import { useState } from 'react';
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
  it('accepts one write when two input events arrive before React disables the button', async () => {
    let finish!: (value: string) => void;
    const work = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          finish = resolve;
        }),
    );
    const commit = vi.fn();
    const { result } = renderHook(() => useMutation());
    await act(async () => {
      void result.current.run(work, commit);
      void result.current.run(work, commit);
    });
    expect(work).toHaveBeenCalledOnce();
    expect(result.current.busy).toBe(true);
    await act(async () => finish('one receipt'));
    expect(commit).toHaveBeenCalledExactlyOnceWith('one receipt');
    expect(result.current.busy).toBe(false);
  });
});

it('does not poll over a slow read and resumes only after that read settles', async () => {
  vi.useFakeTimers();
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
  const pending: { resolve: (value: unknown) => void; signal?: AbortSignal }[] = [];
  const get = vi.spyOn(api, 'get').mockImplementation(
    (_path, signal) =>
      new Promise((resolve) => {
        pending.push({ resolve, signal });
      }),
  );
  const { unmount } = renderHook(() => {
    const [version, update] = useState(0);
    const resource = useResource('/job', version);
    usePolling(true, () => update((value) => value + 1), resource.loading, !!resource.error, 100);
    return resource;
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1000);
  });
  expect(get).toHaveBeenCalledOnce();
  expect(pending[0].signal?.aborted).toBe(false);
  await act(async () => pending[0].resolve({ status: 'RUNNING' }));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(100);
  });
  expect(get).toHaveBeenCalledTimes(2);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1000);
  });
  expect(get).toHaveBeenCalledTimes(2);
  expect(pending[1].signal?.aborted).toBe(false);
  unmount();
  vi.useRealTimers();
});

it('rearms polling for immediate reads and stops while the tab is hidden', async () => {
  vi.useFakeTimers();
  const visibility = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
  const poll = vi.fn();
  const { unmount } = renderHook(() => usePolling(true, poll, false, false, 100));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(100);
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(100);
  });
  expect(poll).toHaveBeenCalledTimes(2);
  visibility.mockReturnValue(true);
  await act(async () => {
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(500);
  });
  expect(poll).toHaveBeenCalledTimes(2);
  visibility.mockReturnValue(false);
  await act(async () => document.dispatchEvent(new Event('visibilitychange')));
  expect(poll).toHaveBeenCalledTimes(3);
  unmount();
  vi.useRealTimers();
});
