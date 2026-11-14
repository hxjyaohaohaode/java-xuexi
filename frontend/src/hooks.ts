import { useCallback, useEffect, useRef, useState } from 'react';
import { api, isAbort } from './api';
import { mergeEvents, ScopeFence } from './state';
import type { WorkspaceEvent } from './types';

export function useResource<T>(path: string | null, version = 0) {
  const [state, setState] = useState<{
    data: T | null;
    error: unknown;
    loading: boolean;
    path: string | null;
  }>({ data: null, error: null, loading: !!path, path });
  const [nonce, reload] = useState(0);
  useEffect(() => {
    const control = new AbortController();
    let active = true;
    if (!path) {
      setState({ data: null, error: null, loading: false, path });
      return;
    }
    setState((p) => ({ data: p.path === path ? p.data : null, error: null, loading: true, path }));
    api
      .get<T>(path, control.signal)
      .then((data) => {
        if (active) setState({ data, error: null, loading: false, path });
      })
      .catch((error) => {
        if (active && !isAbort(error)) setState((p) => ({ ...p, error, loading: false, path }));
      });
    return () => {
      active = false;
      control.abort();
    };
  }, [path, version, nonce]);
  return {
    ...(state.path === path ? state : { data: null, error: null, loading: !!path, path }),
    reload: useCallback(() => reload((n) => n + 1), []),
  };
}
export function useMutation() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const fence = useRef(new ScopeFence());
  useEffect(() => {
    fence.current.advance();
    return () => {
      fence.current.advance();
    };
  }, []);
  const run = async <T>(work: () => Promise<T>, commit?: (value: T) => void) => {
    const ticket = fence.current.advance();
    setBusy(true);
    setError(null);
    try {
      const value = await work();
      if (fence.current.current(ticket)) {
        commit?.(value);
        setBusy(false);
      }
      return value;
    } catch (error) {
      if (fence.current.current(ticket)) {
        setError(error);
        setBusy(false);
      }
      return undefined;
    }
  };
  return { busy, error, run, clearError: () => setError(null) };
}
export function useWorkspaceEvents(workspaceId: string, onChange: () => void) {
  const changed = useRef(onChange);
  changed.current = onChange;
  const [events, setEvents] = useState<WorkspaceEvent[]>([]);
  const [cursor, setCursor] = useState(0);
  const [error, setError] = useState<unknown>(null);
  const [online, setOnline] = useState(navigator.onLine);
  useEffect(() => {
    let live = true;
    let after = 0;
    let timer: ReturnType<typeof setTimeout>;
    const control = new AbortController();
    setEvents([]);
    setCursor(0);
    setError(null);
    const poll = async () => {
      let delay = 2500;
      try {
        if (!document.hidden && navigator.onLine) {
          const batch = await api.get<WorkspaceEvent[]>(
            `/workspaces/${workspaceId}/events?after=${after}&limit=100`,
            control.signal,
          );
          if (!live) return;
          const merged = mergeEvents(after, batch, workspaceId);
          if (merged.gap) throw new Error('事件序号不连续，正在从已确认的游标重新读取。');
          if (merged.events.length) {
            after = merged.cursor;
            setCursor(after);
            setEvents((p) => [...p, ...merged.events].slice(-100));
            changed.current();
          }
          setError(null);
          if (batch.length === 100) delay = 50;
        }
      } catch (error) {
        if (live && !isAbort(error)) setError(error);
      } finally {
        if (live) timer = setTimeout(poll, delay);
      }
    };
    const connection = () => setOnline(navigator.onLine);
    window.addEventListener('online', connection);
    window.addEventListener('offline', connection);
    void poll();
    return () => {
      live = false;
      control.abort();
      clearTimeout(timer);
      window.removeEventListener('online', connection);
      window.removeEventListener('offline', connection);
    };
  }, [workspaceId]);
  return { events, cursor, error, online };
}
