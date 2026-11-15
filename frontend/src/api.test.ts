import { webcrypto } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError, safeSourceUrl } from './api';

const response = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
describe('real request boundary', () => {
  it('attaches the server CSRF header and replaces it after session rotation', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ token: 'before', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response({ userId: 'u' }))
      .mockResolvedValueOnce(response({ token: 'after', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response({ ok: true }));
    const client = new ApiClient(fetcher, sessionStorage);
    await client.request(
      '/auth/session',
      'POST',
      { username: 'student', password: 'a-not-stored-password' },
      { authExpected: false },
    );
    client.resetCsrf();
    await client.request('/workspaces/x/context', 'POST');
    expect((fetcher.mock.calls[1][1]?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(
      'before',
    );
    expect((fetcher.mock.calls[3][1]?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(
      'after',
    );
    expect(sessionStorage.length).toBe(0);
  });
  it('reuses one idempotency key after an unknown network outcome without automatic resubmission', async () => {
    vi.stubGlobal('crypto', webcrypto);
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      .mockRejectedValueOnce(new TypeError('connection reset'))
      .mockResolvedValueOnce(response({ id: 'confirmed' }));
    const client = new ApiClient(fetcher, sessionStorage);
    client.scope = 'owner';
    const body = { summary: 'private student draft' };
    await expect(
      client.request('/workspaces/a/memories', 'POST', body, { idempotent: true }),
    ).rejects.toMatchObject({ code: 'NETWORK_UNCERTAIN' });
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(sessionStorage.length).toBe(1);
    expect(sessionStorage.key(0)).not.toContain(body.summary);
    await client.request('/workspaces/a/memories', 'POST', body, { idempotent: true });
    expect((fetcher.mock.calls[1][1]?.headers as Record<string, string>)['Idempotency-Key']).toBe(
      (fetcher.mock.calls[2][1]?.headers as Record<string, string>)['Idempotency-Key'],
    );
    expect(sessionStorage.length).toBe(0);
    vi.unstubAllGlobals();
  });
  it('preserves structured conflict errors and clears private state on demand', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(
        response({ code: 'VERSION_CONFLICT', detail: 'Changed', traceId: 'trace-1' }, 409),
      );
    const client = new ApiClient(fetcher, sessionStorage);
    sessionStorage.setItem('tf.draft.owner', 'private');
    sessionStorage.setItem('unrelated', 'keep');
    await expect(client.request('/artifacts/x/revisions', 'POST', {})).rejects.toEqual(
      new ApiError(409, 'VERSION_CONFLICT', 'Changed', 'trace-1'),
    );
    client.clearPrivateState();
    expect(sessionStorage.getItem('tf.draft.owner')).toBeNull();
    expect(sessionStorage.getItem('unrelated')).toBe('keep');
  });
  it('rejects executable and malformed source URLs', () => {
    expect(safeSourceUrl('javascript:alert(1)')).toBeUndefined();
    expect(safeSourceUrl('data:text/html,<script>')).toBeUndefined();
    expect(safeSourceUrl('https://docs.oracle.com/javase/specs/')).toMatch(/^https:/);
  });
  it('does not let a late old-session 401 log out a newly established session', async () => {
    let finish!: (response: Response) => void;
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      )
      .mockResolvedValueOnce(response({ code: 'UNAUTHENTICATED' }, 401));
    const client = new ApiClient(fetcher, sessionStorage);
    client.scope = 'old-owner';
    client.onUnauthenticated = vi.fn();
    const oldRead = client.get('/workspaces');
    client.resetCsrf();
    client.scope = 'new-owner';
    finish(response({ code: 'UNAUTHENTICATED' }, 401));
    await expect(oldRead).rejects.toMatchObject({ status: 401 });
    expect(client.onUnauthenticated).not.toHaveBeenCalled();
    expect(client.scope).toBe('new-owner');
    await expect(client.get('/workspaces')).rejects.toMatchObject({ status: 401 });
    expect(client.onUnauthenticated).toHaveBeenCalledOnce();
  });
  it('discards a late CSRF response after reset and retains the new session token', async () => {
    let finish!: (response: Response) => void;
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      )
      .mockResolvedValueOnce(response({ token: 'new-token', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(response({ ok: true }));
    const client = new ApiClient(fetcher, sessionStorage);
    const oldToken = client.warmCsrf();
    client.resetCsrf();
    await client.warmCsrf();
    finish(response({ token: 'old-token', headerName: 'X-CSRF-TOKEN' }));
    await expect(oldToken).rejects.toMatchObject({ name: 'AbortError' });
    await client.request('/workspaces/x/context', 'POST');
    expect((fetcher.mock.calls[2][1]?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(
      'new-token',
    );
  });
  it('never dispatches a waiting old-session mutation or recreates its private key after logout', async () => {
    let finish!: (response: Response) => void;
    const fetcher = vi.fn<typeof fetch>().mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const client = new ApiClient(fetcher, sessionStorage);
    client.scope = 'owner';
    const pending = client.request(
      '/workspaces/x/memories',
      'POST',
      { summary: 'private' },
      { idempotent: true },
    );
    client.clearPrivateState();
    finish(response({ token: 'old', headerName: 'X-CSRF-TOKEN' }));
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    expect(fetcher).toHaveBeenCalledOnce();
    expect(sessionStorage.length).toBe(0);
  });
  it('keeps the idempotency key after a truncated successful response', async () => {
    vi.stubGlobal('crypto', webcrypto);
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(new Response('{"id":', { status: 201 }))
      .mockResolvedValueOnce(response({ id: 'confirmed' }));
    const client = new ApiClient(fetcher, sessionStorage);
    client.scope = 'owner';
    await expect(
      client.request(
        '/workspaces/x/artifacts',
        'POST',
        { title: 'saved once' },
        { idempotent: true },
      ),
    ).rejects.toMatchObject({ code: 'NETWORK_UNCERTAIN' });
    expect(sessionStorage.length).toBe(1);
    await client.request(
      '/workspaces/x/artifacts',
      'POST',
      { title: 'saved once' },
      { idempotent: true },
    );
    expect((fetcher.mock.calls[1][1]?.headers as Record<string, string>)['Idempotency-Key']).toBe(
      (fetcher.mock.calls[2][1]?.headers as Record<string, string>)['Idempotency-Key'],
    );
    expect(sessionStorage.length).toBe(0);
    vi.unstubAllGlobals();
  });
  it('ignores a late successful private read after the account changed', async () => {
    let finish!: (value: Response) => void;
    const client = new ApiClient(
      vi.fn<typeof fetch>().mockImplementation(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      ),
      sessionStorage,
    );
    client.scope = 'old-owner';
    const pending = client.get('/me/profile');
    client.clearPrivateState();
    client.scope = 'new-owner';
    finish(response({ displayName: 'old private profile' }));
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });
  it('keeps retry keys in memory when browser storage is blocked and still allows logout', async () => {
    vi.stubGlobal('crypto', webcrypto);
    const blocked = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
      get length() {
        throw new Error('blocked');
      },
    } as unknown as Storage;
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      .mockRejectedValueOnce(new TypeError('connection reset'))
      .mockResolvedValueOnce(response({ id: 'confirmed' }));
    const client = new ApiClient(fetcher, blocked);
    await expect(client.request('/writes', 'POST', {}, { idempotent: true })).rejects.toMatchObject(
      { code: 'NETWORK_UNCERTAIN' },
    );
    await client.request('/writes', 'POST', {}, { idempotent: true });
    expect((fetcher.mock.calls[1][1]?.headers as Record<string, string>)['Idempotency-Key']).toBe(
      (fetcher.mock.calls[2][1]?.headers as Record<string, string>)['Idempotency-Key'],
    );
    expect(() => client.clearPrivateState()).not.toThrow();
    vi.unstubAllGlobals();
  });
  it('bounds a waiting request without automatically repeating a write', async () => {
    vi.useFakeTimers();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      .mockImplementationOnce(
        (_input, init) =>
          new Promise((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () =>
              reject(new DOMException('stopped', 'AbortError')),
            );
          }),
      );
    const client = new ApiClient(fetcher, sessionStorage);
    await client.warmCsrf();
    const result = client
      .request('/writes', 'POST', {}, { timeoutMs: 100 })
      .catch((error: unknown) => error);
    await vi.advanceTimersByTimeAsync(100);
    expect(await result).toMatchObject({ code: 'NETWORK_UNCERTAIN' });
    expect(fetcher).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });
  it('sends multipart bytes unchanged and distinguishes same-named files by their actual content', async () => {
    vi.stubGlobal('crypto', webcrypto);
    const form = (bytes: string) => {
      const file = new File([bytes], 'same.png', { type: 'image/png' });
      Object.defineProperty(file, 'arrayBuffer', {
        value: async () => new TextEncoder().encode(bytes).buffer,
      });
      const value = new FormData();
      value.append('file', file);
      return value;
    };
    const first = form('one'),
      second = form('two');
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      .mockRejectedValueOnce(new TypeError('reset'))
      .mockResolvedValueOnce(response({ id: 'first' }))
      .mockResolvedValueOnce(response({ id: 'second' }));
    const client = new ApiClient(fetcher, sessionStorage);
    await expect(
      client.request('/assets', 'POST', first, { idempotent: true }),
    ).rejects.toMatchObject({ code: 'NETWORK_UNCERTAIN' });
    await client.request('/assets', 'POST', first, { idempotent: true });
    await client.request('/assets', 'POST', second, { idempotent: true });
    const firstHeaders = fetcher.mock.calls[1][1]?.headers as Record<string, string>;
    expect(firstHeaders['Content-Type']).toBeUndefined();
    expect(fetcher.mock.calls[1][1]?.body).toBe(first);
    expect(firstHeaders['Idempotency-Key']).toBe(
      (fetcher.mock.calls[2][1]?.headers as Record<string, string>)['Idempotency-Key'],
    );
    expect(firstHeaders['Idempotency-Key']).not.toBe(
      (fetcher.mock.calls[3][1]?.headers as Record<string, string>)['Idempotency-Key'],
    );
    vi.unstubAllGlobals();
  });
});
