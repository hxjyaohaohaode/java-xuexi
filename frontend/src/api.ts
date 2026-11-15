export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    detail: string,
    public readonly traceId?: string,
    public readonly retryAfterSeconds?: number,
  ) {
    super(detail);
  }
}
type RequestOptions = {
  signal?: AbortSignal;
  idempotent?: boolean;
  headers?: Record<string, string>;
  authExpected?: boolean;
  timeoutMs?: number;
};
export class ApiClient {
  private csrf: { token: string; headerName: string } | null = null;
  private csrfPromise: Promise<void> | null = null;
  private sessionEpoch = 0;
  private pendingKeys = new Map<string, string>();
  public scope = 'anonymous';
  public onUnauthenticated: (() => void) | null = null;
  constructor(
    private readonly fetcher: typeof fetch = (...args) => fetch(...args),
    private readonly storage: Storage = sessionStorage,
  ) {}
  async warmCsrf(signal?: AbortSignal) {
    if (!this.csrfPromise) {
      const epoch = this.sessionEpoch;
      const pending = this.fetcher('/api/v1/auth/csrf', { credentials: 'same-origin', signal })
        .then(async (r) => {
          if (!r.ok) throw new ApiError(r.status, 'CSRF_UNAVAILABLE', '无法建立安全会话，请重试。');
          const token = await r.json();
          if (epoch !== this.sessionEpoch)
            throw new DOMException('会话已切换，旧令牌已丢弃。', 'AbortError');
          this.csrf = token;
        })
        .finally(() => {
          if (this.csrfPromise === pending) this.csrfPromise = null;
        });
      this.csrfPromise = pending;
    }
    return this.csrfPromise;
  }
  resetCsrf() {
    this.sessionEpoch += 1;
    this.csrf = null;
    this.csrfPromise = null;
  }
  async request<T>(
    path: string,
    method = 'GET',
    body?: unknown,
    options: RequestOptions = {},
  ): Promise<T> {
    const mutation = !['GET', 'HEAD'].includes(method);
    if (mutation && typeof navigator !== 'undefined' && !navigator.onLine)
      throw new ApiError(
        0,
        'OFFLINE',
        '当前处于离线状态。内容已留在当前页面，恢复连接后可继续提交。',
      );
    const controller = new AbortController();
    let timedOut = false;
    const abort = () => controller.abort(options.signal?.reason);
    if (options.signal?.aborted) abort();
    else options.signal?.addEventListener('abort', abort, { once: true });
    const timeout = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, options.timeoutMs ?? 30000);
    try {
      return await this.dispatch<T>(path, method, body, { ...options, signal: controller.signal });
    } catch (error) {
      if (timedOut)
        throw new ApiError(
          0,
          mutation ? 'NETWORK_UNCERTAIN' : 'REQUEST_TIMEOUT',
          mutation
            ? `等待响应超时，服务端结果尚未确认。当前内容已保留；${options.idempotent ? '重试会复用本次请求标识。' : '请先核对保存状态后再重试。'}`
            : '服务端暂未响应。已显示的内容会保留，你可以稍后重新读取。',
        );
      throw error;
    } finally {
      clearTimeout(timeout);
      options.signal?.removeEventListener('abort', abort);
    }
  }
  private async dispatch<T>(
    path: string,
    method: string,
    body: unknown,
    options: RequestOptions,
  ): Promise<T> {
    const requestScope = this.scope;
    const requestEpoch = this.sessionEpoch;
    const currentSession = () => this.scope === requestScope && this.sessionEpoch === requestEpoch;
    const ensureCurrent = () => {
      if (!currentSession())
        throw new DOMException('会话已切换，旧请求不会继续发送。', 'AbortError');
    };
    const mutation = !['GET', 'HEAD'].includes(method);
    if (mutation && !this.csrf) await this.warmCsrf(options.signal);
    ensureCurrent();
    const multipart = typeof FormData !== 'undefined' && body instanceof FormData;
    const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
    if (body !== undefined && !multipart) headers['Content-Type'] = 'application/json';
    if (mutation && this.csrf) headers[this.csrf.headerName] = this.csrf.token;
    let storageKey: string | undefined;
    if (options.idempotent) {
      const fingerprint = multipart
        ? await Promise.all(
            Array.from(body.entries()).map(async ([key, value]) => {
              if (typeof value === 'string') return [key, value];
              const hash = await crypto.subtle.digest('SHA-256', await value.arrayBuffer());
              return [
                key,
                value.name,
                value.type,
                value.size,
                Array.from(new Uint8Array(hash), (byte) => byte.toString(16).padStart(2, '0')).join(
                  '',
                ),
              ];
            }),
          )
        : body;
      const bytes = new TextEncoder().encode(`${method}:${path}:${JSON.stringify(fingerprint)}`);
      const digest = await crypto.subtle.digest('SHA-256', bytes);
      ensureCurrent();
      storageKey = `tf.idem.${requestScope}.${Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')}`;
      let stored: string | null = null;
      try {
        stored = this.storage.getItem(storageKey);
      } catch {
        /* Storage can be unavailable in private mode. */
      }
      const key = stored || this.pendingKeys.get(storageKey) || crypto.randomUUID();
      this.pendingKeys.set(storageKey, key);
      try {
        this.storage.setItem(storageKey, key);
      } catch {
        /* Preserve the key in memory for this tab. */
      }
      headers['Idempotency-Key'] = key;
    }
    let response: Response;
    try {
      ensureCurrent();
      response = await this.fetcher(`/api/v1${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
        credentials: 'same-origin',
        signal: options.signal,
      });
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') throw error;
      throw new ApiError(
        0,
        'NETWORK_UNCERTAIN',
        mutation
          ? '连接中断，服务端结果尚未确认。保留当前内容后重试，将复用本次请求标识。'
          : '无法连接服务端，请检查网络并重试。',
      );
    }
    if (!response.ok) {
      const problem = await response.json().catch(() => ({}));
      if (response.status === 401 && options.authExpected !== false && currentSession())
        this.onUnauthenticated?.();
      if (response.status === 403 && problem.code === 'CSRF_INVALID' && currentSession())
        this.resetCsrf();
      throw new ApiError(
        response.status,
        problem.code || `HTTP_${response.status}`,
        problem.detail || '请求未完成，请重试。',
        problem.traceId,
        response.headers.has('Retry-After')
          ? Number(response.headers.get('Retry-After')) || undefined
          : undefined,
      );
    }
    // Retain the operation key until the complete success body has been received.
    // A truncated 2xx body is still an unknown outcome for the caller.
    let value: T;
    try {
      value = response.status === 204 ? (undefined as T) : ((await response.json()) as T);
    } catch (error) {
      if (isAbort(error)) throw error;
      throw new ApiError(
        0,
        mutation ? 'NETWORK_UNCERTAIN' : 'INVALID_RESPONSE',
        mutation
          ? '服务端响应未完整接收。内容已保留，请重试确认本次操作。'
          : '服务端响应未完整接收，请重新读取。',
      );
    }
    ensureCurrent();
    if (storageKey) {
      this.pendingKeys.delete(storageKey);
      try {
        this.storage.removeItem(storageKey);
      } catch {
        /* In-memory key has been cleared. */
      }
    }
    return value;
  }
  get<T>(path: string, signal?: AbortSignal) {
    return this.request<T>(path, 'GET', undefined, { signal });
  }
  clearPrivateState() {
    try {
      for (let i = this.storage.length - 1; i >= 0; i--) {
        const key = this.storage.key(i);
        if (key?.startsWith('tf.')) this.storage.removeItem(key);
      }
    } catch {
      /* A blocked browser store must never prevent logout. */
    }
    this.pendingKeys.clear();
    this.scope = 'anonymous';
    this.resetCsrf();
  }
}
export const api = new ApiClient();
export function message(error: unknown) {
  return error instanceof Error ? error.message : '操作未完成，请重试。';
}
export function isAbort(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError';
}
export function safeSourceUrl(url: string): string | undefined {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : undefined;
  } catch {
    return undefined;
  }
}
