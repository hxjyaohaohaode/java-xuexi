import { useCallback, useEffect, useRef, useState } from 'react';
import { api, isAbort } from './api';
import { mergeEvents, readSaved, ScopeFence, writeSaved } from './state';
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
  const active = useRef(false);
  useEffect(() => {
    fence.current.advance();
    return () => {
      fence.current.advance();
    };
  }, []);
  const run = async <T>(work: () => Promise<T>, commit?: (value: T) => void) => {
    // React may not paint disabled controls before a second click/Enter arrives.
    if (active.current) return undefined;
    active.current = true;
    const ticket = fence.current.advance();
    setBusy(true);
    setError(null);
    try {
      const value = await work();
      if (fence.current.current(ticket)) {
        commit?.(value);
      }
      return value;
    } catch (error) {
      if (fence.current.current(ticket)) {
        if (!isAbort(error)) setError(error);
      }
      return undefined;
    } finally {
      active.current = false;
      if (fence.current.current(ticket)) setBusy(false);
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
  const [lastSyncedAt, setLastSyncedAt] = useState<Date | null>(null);
  useEffect(() => {
    let live = true;
    let after = 0;
    let timer: ReturnType<typeof setTimeout>;
    let inFlight = false;
    let failures = 0;
    const control = new AbortController();
    setEvents([]);
    setCursor(0);
    setError(null);
    setLastSyncedAt(null);
    const poll = async () => {
      if (!live || inFlight) return;
      inFlight = true;
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
          failures = 0;
          setLastSyncedAt(new Date());
          if (batch.length === 100) delay = 50;
        }
      } catch (error) {
        if (live && !isAbort(error)) {
          setError(error);
          failures += 1;
          delay = Math.min(30000, 2500 * 2 ** Math.min(failures, 4));
        }
      } finally {
        inFlight = false;
        if (live) timer = setTimeout(poll, delay);
      }
    };
    const connection = () => {
      setOnline(navigator.onLine);
      if (navigator.onLine && !document.hidden && !inFlight) {
        clearTimeout(timer);
        void poll();
      }
    };
    window.addEventListener('online', connection);
    window.addEventListener('offline', connection);
    document.addEventListener('visibilitychange', connection);
    void poll();
    return () => {
      live = false;
      control.abort();
      clearTimeout(timer);
      window.removeEventListener('online', connection);
      window.removeEventListener('offline', connection);
      document.removeEventListener('visibilitychange', connection);
    };
  }, [workspaceId]);
  return { events, cursor, error, online, lastSyncedAt };
}

/** A per-account, per-workspace draft; never a replacement for a confirmed server write. */
export function useSessionDraft<T>(key: string, initial: T) {
  const [value, setValue] = useState<T>(() => readSaved(key, initial));
  const set = (next: T | ((previous: T) => T)) => {
    setValue((previous) => {
      const value = typeof next === 'function' ? (next as (previous: T) => T)(previous) : next;
      writeSaved(key, value);
      return value;
    });
  };
  return [value, set] as const;
}

export function useUnsavedChanges(dirty: boolean) {
  useEffect(() => {
    if (!dirty) return;
    const guard = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [dirty]);
}

/** Schedule the next read only after the previous one settles; pause hidden/offline tabs. */
export function usePolling(
  active: boolean,
  onPoll: () => void,
  loading: boolean,
  failed = false,
  interval = 1500,
) {
  const action = useRef(onPoll);
  action.current = onPoll;
  const failures = useRef(0);
  const [cycle, setCycle] = useState(0);
  useEffect(() => {
    if (!active) {
      failures.current = 0;
      return;
    }
    let timer: ReturnType<typeof setTimeout> | undefined;
    const advance = () => {
      if (!loading && !document.hidden && navigator.onLine) {
        action.current();
        setCycle((value) => value + 1);
      }
    };
    if (!loading && !document.hidden && navigator.onLine) {
      failures.current = failed ? failures.current + 1 : 0;
      timer = setTimeout(advance, Math.min(30000, interval * 2 ** Math.min(failures.current, 4)));
    }
    document.addEventListener('visibilitychange', advance);
    window.addEventListener('online', advance);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', advance);
      window.removeEventListener('online', advance);
    };
  }, [active, loading, failed, interval, cycle]);
}
