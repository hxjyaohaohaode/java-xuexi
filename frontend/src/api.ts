export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    detail: string,
    public readonly traceId?: string,
  ) {
    super(detail);
  }
}
type RequestOptions = {
  signal?: AbortSignal;
  idempotent?: boolean;
  headers?: Record<string, string>;
  authExpected?: boolean;
};
export class ApiClient {
  private csrf: { token: string; headerName: string } | null = null;
  private csrfPromise: Promise<void> | null = null;
  private sessionEpoch = 0;
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
    const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (mutation && this.csrf) headers[this.csrf.headerName] = this.csrf.token;
    let storageKey: string | undefined;
    if (options.idempotent) {
      const bytes = new TextEncoder().encode(`${method}:${path}:${JSON.stringify(body)}`);
      const digest = await crypto.subtle.digest('SHA-256', bytes);
      ensureCurrent();
      storageKey = `tf.idem.${requestScope}.${Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')}`;
      const key = this.storage.getItem(storageKey) || crypto.randomUUID();
      this.storage.setItem(storageKey, key);
      headers['Idempotency-Key'] = key;
    }
    let response: Response;
    try {
      ensureCurrent();
      response = await this.fetcher(`/api/v1${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
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
      );
    }
    if (storageKey) this.storage.removeItem(storageKey);
    return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  }
  get<T>(path: string, signal?: AbortSignal) {
    return this.request<T>(path, 'GET', undefined, { signal });
  }
  clearPrivateState() {
    for (let i = this.storage.length - 1; i >= 0; i--) {
      const key = this.storage.key(i);
      if (key?.startsWith('tf.')) this.storage.removeItem(key);
    }
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
