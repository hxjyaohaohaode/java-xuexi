import { createHash, webcrypto } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { mkdir, writeFile } from 'node:fs/promises';
import { afterAll, beforeAll, expect, it, vi } from 'vitest';
import { act, configure, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../src/App';
import type { Attempt, Progress } from '../src/types';
import { api } from '../src/api';
import { runWorkbenchJourney, type JourneyDriver } from './workbench-journey.mjs';

const base = process.env.TYPEFORGE_BASE_URL;
const nativeFetch = globalThis.fetch;
const cookies = new Map<string, string>();
const steps: { name: string; status: string }[] = [];
const receipts: {
  path: string;
  action?: string;
  status: number;
  body: Record<string, unknown>;
}[] = [];
let failure: string | undefined;
let completed = false;
beforeAll(() => {
  configure({
    asyncUtilTimeout: 25000,
    getElementError: (message, container) =>
      new Error(
        (message || 'DOM lookup failed') +
          '\n' +
          (container.querySelector('main')?.textContent || container.textContent || '').slice(
            0,
            6000,
          ),
      ),
  });
  if (!base) throw new Error('TYPEFORGE_BASE_URL must point to a real running demo backend.');
  vi.stubGlobal('crypto', webcrypto);
  // jsdom has no box layout or resize notifications. Chromium exercises native observer callbacks.
  if (typeof ResizeObserver === 'undefined')
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
  // jsdom has no viewport scroll; transport and business results remain real.
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
  // jsdom provides FileReader but omits Blob.arrayBuffer; preserve actual selected bytes.
  if (!Blob.prototype.arrayBuffer)
    Object.defineProperty(Blob.prototype, 'arrayBuffer', {
      configurable: true,
      value(this: Blob) {
        return new Promise<ArrayBuffer>((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(reader.result as ArrayBuffer);
          reader.onerror = () => reject(reader.error);
          reader.readAsArrayBuffer(this);
        });
      },
    });
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addEventListener() {},
    removeEventListener() {},
  }));
  // Transport adapter only: every response, status, cookie and body comes from the real backend.
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const headers = new Headers(init.headers);
    if (cookies.size)
      headers.set('Cookie', [...cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; '));
    const url = new URL(
      typeof input === 'string' ? input : input instanceof URL ? input.href : input.url,
      base,
    );
    let body = init.body;
    if (body instanceof FormData) {
      // Encode real DOM-selected bytes for Node fetch. jsdom FormData is not undici FormData.
      const boundary = `typeforge-dom-${crypto.randomUUID()}`;
      const parts: Buffer[] = [];
      const quoted = (value: string) => value.replace(/[\r\n"]/g, '_');
      for (const [key, value] of body.entries()) {
        parts.push(
          Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${quoted(key)}"`),
        );
        if (typeof value === 'string') parts.push(Buffer.from(`\r\n\r\n${value}\r\n`));
        else
          parts.push(
            Buffer.from(
              `; filename="${quoted(value.name)}"\r\nContent-Type: ${value.type || 'application/octet-stream'}\r\n\r\n`,
            ),
            Buffer.from(await value.arrayBuffer()),
            Buffer.from('\r\n'),
          );
      }
      parts.push(Buffer.from(`--${boundary}--\r\n`));
      headers.set('Content-Type', `multipart/form-data; boundary=${boundary}`);
      body = new Uint8Array(Buffer.concat(parts));
    }
    const result = await nativeFetch(url, { ...init, headers, body });
    for (const cookie of result.headers.getSetCookie()) {
      const pair = cookie.split(';', 1)[0];
      const i = pair.indexOf('=');
      cookies.set(pair.slice(0, i), pair.slice(i + 1));
    }
    if (
      init.method === 'POST' &&
      (/\/exercises\/[^/]+\/hints$/.test(url.pathname) ||
        /\/notebooks\/[^/]+\/actions$/.test(url.pathname) ||
        url.pathname === '/api/v1/attempts')
    ) {
      // Observe actual responses only; never replace the application's response or score.
      receipts.push({
        path: url.pathname,
        action: typeof init.body === 'string' ? JSON.parse(init.body).action : undefined,
        status: result.status,
        body: await result
          .clone()
          .json()
          .catch(() => ({})),
      });
    }
    return result;
  });
});
afterAll(async () => {
  await mkdir('test-results', { recursive: true });
  await writeFile(
    'test-results/live-dom-report.json',
    JSON.stringify(
      {
        at: new Date().toISOString(),
        driver: 'jsdom with real HTTP; not a graphical browser',
        baseUrl: base,
        steps,
        status: failure || !completed ? 'FAIL' : 'PASS',
        failure: failure || (!completed ? 'The complete scenario did not finish.' : undefined),
        limits: [
          'Does not verify layout, screenshots, native downloads or real keyboard/touch rendering.',
          'Cookie and multipart encoding adapt DOM requests to Node fetch; no server response is mocked.',
          'jsdom cannot validate its internal file list after user-event upload; this driver verifies the selected bytes and adapts only that required attribute. Chromium retains native file validation.',
        ],
      },
      null,
      2,
    ) + '\n',
  );
  vi.unstubAllGlobals();
});
const step = async (name: string, work: () => Promise<void>) => {
  process.stdout.write(`${name} ... `);
  try {
    await work();
    steps.push({ name, status: 'PASS' });
    process.stdout.write('PASS\n');
  } catch (error) {
    failure = String(error);
    steps.push({ name, status: 'FAIL' });
    process.stdout.write('FAIL\n');
    await mkdir('test-results', { recursive: true });
    await writeFile(
      'test-results/live-dom-failure.txt',
      document.querySelector('main')?.textContent || document.body.textContent || '',
    );
    throw error;
  }
};
const find = (role: Parameters<typeof screen.findByRole>[0], name: string | RegExp) =>
  screen.findByRole(role, { name }, { timeout: 25000 });
const settledProgress = async () => {
  let result: Progress | undefined;
  await waitFor(
    async () => {
      const response = await fetch('/api/v1/me/progress');
      expect(response.status).toBe(200);
      result = await response.json();
      expect(result?.rebuilding).toBe(false);
    },
    { interval: 200 },
  );
  return result!;
};
const independentSamples = (progress: Progress) =>
  progress.items.reduce((count, item) => count + item.independentSamples, 0);

function workbenchDriver(user: ReturnType<typeof userEvent.setup>): JourneyDriver {
  const scope = async (selector?: string) => {
    if (!selector) return document.body;
    let result: HTMLElement | null = null;
    await waitFor(() => {
      result = document.querySelector(selector);
      expect(result, `Required UI scope: ${selector}`).not.toBeNull();
    });
    return result!;
  };
  const role = async (kind: string, name: string | RegExp, selector?: string) =>
    within(await scope(selector)).findByRole(kind as Parameters<typeof screen.findByRole>[0], {
      name,
    });
  const label = async (name: string, selector?: string) =>
    within(await scope(selector)).findByLabelText(name, {
      exact: false,
      selector: 'input,textarea,select',
    });
  const readJson = async (path: string) => {
    const result = await fetch(`/api/v1${path}`);
    expect(result.status, path).toBe(200);
    return result.json();
  };
  return {
    async fill(name, value, selector) {
      const input = await label(name, selector);
      await waitFor(() => expect(input).toBeEnabled());
      await user.clear(input);
      await user.click(input);
      await user.paste(value);
    },
    async verifyPattern(name, accepted, rejected) {
      const input = (await label(name)) as HTMLInputElement;
      const before = input.value;
      expect(input.pattern).not.toBe('');
      const expression = new RegExp(`^(?:${input.pattern})$`, 'v');
      for (const [values, expected] of [
        [accepted, true],
        [rejected, false],
      ] as const)
        for (const value of values) {
          expect(expression.test(value), `${name}: ${value}`).toBe(expected);
          await user.clear(input);
          await user.click(input);
          await user.paste(value);
          expect(input.value).toBe(value);
          expect(input.checkValidity(), `${name}: native validity for ${value}`).toBe(expected);
        }
      await user.clear(input);
      if (before) await user.paste(before);
    },
    async route() {
      return location.hash;
    },
    async click(kind, name, selector) {
      const target = await role(kind, name, selector);
      if (target instanceof HTMLButtonElement) await waitFor(() => expect(target).toBeEnabled());
      await user.click(target);
    },
    async select(name, value, selector) {
      const input = await label(name, selector);
      await waitFor(() => {
        expect(input).toBeEnabled();
        expect(
          Array.from((input as HTMLSelectElement).options).some((item) => item.value === value),
        ).toBe(true);
      });
      await user.selectOptions(input, value);
    },
    async check(name, selector) {
      const input = await role('checkbox', name, selector);
      await waitFor(() => expect(input).toBeEnabled());
      if (!(input as HTMLInputElement).checked) await user.click(input);
    },
    async summary(name, selector) {
      const root = await scope(selector);
      let summary: HTMLElement | undefined;
      await waitFor(() => {
        summary = Array.from(root.querySelectorAll<HTMLElement>('summary')).find((item) =>
          typeof name === 'string'
            ? item.textContent?.includes(name)
            : name.test(item.textContent || ''),
        );
        expect(summary).toBeTruthy();
      });
      if (!summary!.parentElement?.hasAttribute('open')) await user.click(summary!);
    },
    async text(text, selector) {
      await within(await scope(selector)).findAllByText(text, { exact: false });
    },
    async heading(name, selector) {
      await role('heading', name, selector);
    },
    async link(name, selector) {
      await role('link', name, selector);
    },
    async disabled(kind, name, selector) {
      const target = await role(kind, name, selector);
      await waitFor(() => expect(target).toBeDisabled());
    },
    async enabled(kind, name, selector) {
      const target = await role(kind, name, selector);
      await waitFor(() => expect(target).toBeEnabled());
    },
    async value(name, selector) {
      return ((await label(name, selector)) as HTMLInputElement).value;
    },
    async absentLabel(name) {
      await waitFor(() => expect(screen.queryByLabelText(name, { exact: true })).toBeNull());
    },
    async clickRow(selector, text, name) {
      let row: HTMLElement | undefined;
      await waitFor(() => {
        const rows = Array.from(document.querySelectorAll<HTMLElement>(selector)).filter((item) =>
          item.textContent?.includes(text),
        );
        expect(rows).toHaveLength(1);
        row = rows[0];
      });
      await user.click(within(row!).getByRole('button', { name }));
    },
    async clickArtifact(id) {
      const artifact = await readJson(`/artifacts/${id}`);
      await user.click(
        await role(
          'button',
          new RegExp(artifact.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
          '.artifact-list',
        ),
      );
      await role('heading', artifact.title);
    },
    readJson,
    async mutate(path, method, body) {
      return api.request(path, method, body, { idempotent: true });
    },
    async upload(name, files) {
      const target = await label(name);
      await user.upload(
        target,
        files.map(
          (file) =>
            new File([new Uint8Array(file.buffer).buffer], file.name, { type: file.mimeType }),
        ),
      );
      // jsdom 31 reads an internal empty FileList for required validation although
      // user-event exposes the actual selected File objects. Do not suppress any
      // business validation or response: verify each selected byte before adapting
      // this DOM-only native validity limitation. Chromium uses the original input.
      const fileInput = target as HTMLInputElement;
      expect(fileInput.files?.length).toBe(files.length);
      for (let index = 0; index < files.length; index += 1)
        expect(Buffer.from(await fileInput.files![index].arrayBuffer())).toEqual(
          Buffer.from(files[index].buffer),
        );
      if (fileInput.required && fileInput.files!.length > 0 && fileInput.validity.valueMissing)
        fileInput.removeAttribute('required');
    },
    async report(name) {
      const anchor = await role('link', name);
      const response = await fetch(anchor.getAttribute('href')!);
      expect(response.status).toBe(200);
      const bytes = Buffer.from(await response.arrayBuffer());
      expect(createHash('sha256').update(bytes).digest('hex')).toBe(
        response.headers.get('X-Content-SHA256'),
      );
      return JSON.parse(bytes.toString());
    },
    async capture() {
      /* DOM transport checks do not claim screenshots or layout coverage. */
    },
    async captureResponsive() {
      /* Actual sizes and screenshots run only in the Chromium adapter. */
    },
    async eventually(work, message) {
      await waitFor(async () => expect(await work(), message).toBe(true), {
        interval: 150,
        timeout: 30000,
      });
    },
  };
}

function pngFixture() {
  const chunk = (name: string, data: Buffer) => {
    const content = Buffer.concat([Buffer.from(name), data]);
    let crc = 0xffffffff;
    for (const byte of content) {
      crc ^= byte;
      for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    }
    const length = Buffer.alloc(4),
      digest = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    digest.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
    return Buffer.concat([length, content, digest]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(2, 0);
  header.writeUInt32BE(2, 4);
  header[8] = 8;
  header[9] = 6;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header),
    chunk(
      'IDAT',
      deflateSync(
        Buffer.from([
          0, 20, 115, 103, 255, 20, 115, 103, 255, 0, 20, 115, 103, 255, 20, 115, 103, 255,
        ]),
      ),
    ),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

it('connects the rendered workspace learning, notebook, artifact, tutor and invalidation flows to real HTTP', async () => {
  const user = userEvent.setup({ delay: null });
  render(<App />);
  let workspaceA = '';
  let artifactId = '';
  let tutorArtifactId = '';
  await step('register/login/CSRF and workspace creation', async () => {
    await user.click(await find('button', '注册'));
    await user.type(
      screen.getByLabelText('用户名', { exact: false }),
      `dom_${Date.now().toString(36)}`,
    );
    await user.type(
      screen.getByLabelText('密码', { exact: true }),
      `BrowserStudy-${crypto.randomUUID()}`,
    );
    await user.click(screen.getByRole('button', { name: '注册并进入' }));
    const input = await screen.findByLabelText('工作空间名称', { exact: true }, { timeout: 25000 });
    await user.clear(input);
    await user.type(input, 'DOM 联调 A');
    await user.click(screen.getByRole('button', { name: '创建工作空间' }));
    await find('heading', '泛型与类型安全');
    workspaceA = (screen.getByLabelText('当前工作空间') as HTMLSelectElement).value;
    const capabilities = await (await fetch('/api/v1/capabilities')).json();
    expect(capabilities.enabled).toContain('EXERCISE_HINTS');
    await user.click(await find('button', '记录已读'));
    await screen.findByText('服务端已记录本次阅读。', {}, { timeout: 25000 });
  });
  await step('wrong attempt -> notebook source -> real hints and assisted reattempt', async () => {
    await user.click(screen.getByRole('link', { name: '练习与反馈' }));
    await user.click(await find('button', /Java17中哪种数组创建表达式合法/));
    await user.click(await screen.findByLabelText(/new List<String>\[2\]/));
    expect(document.querySelectorAll('.question-card input:checked')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: '提交作答' }));
    await find('heading', '服务端反馈');
    await user.click(await find('button', '加入可复练错题集'));
    await find('heading', '重新检查你的判断');
    await waitFor(() => expect(screen.getByLabelText('先写下这次的预测或理由')).toBeEnabled());
    await user.type(
      screen.getByLabelText('先写下这次的预测或理由'),
      '先判断泛型不变性，再检查通配符。',
    );
    await user.click(screen.getByRole('button', { name: '记录预测' }));
    await screen.findByText('已记录你的预测；预测不会被当作独立掌握证据。', {}, { timeout: 25000 });
    const beforeHints = await settledProgress();
    const hintEvents: string[] = [];
    for (const level of [1, 2, 3]) {
      const start = receipts.length;
      await user.click(await find('button', `查看第 ${level} 级提示`));
      await waitFor(() =>
        expect(receipts.slice(start).some((item) => item.action === 'requestHint')).toBe(true),
      );
      const receipt = receipts.slice(start).find((item) => item.action === 'requestHint')!;
      expect(receipt.status).toBe(200);
      expect(receipt.body.status).toBe('COMPLETED');
      expect(String(receipt.body.message).length).toBeGreaterThan(10);
      hintEvents.push(String(receipt.body.eventId));
      await screen.findByText(String(receipt.body.message), { exact: true });
      if (level < 3) expect(await find('button', `查看第 ${level + 1} 级提示`)).toBeEnabled();
    }
    expect(new Set(hintEvents).size).toBe(3);
    expect(await find('button', '已查看全部提示')).toBeDisabled();
    const events = await (await fetch(`/api/v1/workspaces/${workspaceA}/events?limit=100`)).json();
    expect(
      events
        .filter((event: { type: string }) => event.type === 'EXERCISE_HINT_REVEALED')
        .map((event: { data: { hintLevel: number } }) => event.data.hintLevel),
    ).toEqual([1, 2, 3]);
    await user.click(
      document.querySelector<HTMLInputElement>('.notebook-block .answer-option input')!,
    );
    await user.click(screen.getByRole('button', { name: '提交复练' }));
    await find('heading', /复练反馈/);
    const r = await fetch(`/api/v1/workspaces/${workspaceA}/attempts`);
    expect(r.status).toBe(200);
    const attempts = await r.json();
    expect(attempts.length).toBeGreaterThanOrEqual(2);
    expect(attempts.some((a: Attempt) => a.hintLevel === 4 && a.status === 'GRADED')).toBe(true);
    expect(independentSamples(await settledProgress())).toBe(independentSamples(beforeHints));
  });
  await step(
    'practice reveals three real hints and first assisted attempt does not increase independent mastery',
    async () => {
      const before = await settledProgress();
      const previous: Attempt[] = await (
        await fetch(`/api/v1/workspaces/${workspaceA}/attempts`)
      ).json();
      await user.click(screen.getByRole('link', { name: '练习与反馈' }));
      await user.click(await find('button', /关于@SafeVarargs，下列哪项正确/));
      const receivedHints: Record<string, unknown>[] = [];
      for (const level of [1, 2, 3]) {
        const start = receipts.length;
        await user.click(await find('button', `查看第 ${level} 级提示`));
        await waitFor(() =>
          expect(receipts.slice(start).some((item) => /\/hints$/.test(item.path))).toBe(true),
        );
        const receipt = receipts.slice(start).find((item) => /\/hints$/.test(item.path))!;
        expect(receipt.status).toBe(200);
        expect(receipt.body).toMatchObject({
          level,
          workspaceId: workspaceA,
          evidenceClass: 'ASSISTED_PRACTICE',
        });
        receivedHints.push(receipt.body);
        await screen.findByText(String(receipt.body.text), { exact: true });
      }
      expect(new Set(receivedHints.map((hint) => hint.id)).size).toBe(3);
      expect(await find('button', '已查看全部提示')).toBeDisabled();
      const exerciseId = String(receivedHints[0].exerciseId);
      expect(previous.some((attempt) => attempt.exerciseId === exerciseId)).toBe(false);
      await user.click(
        document.querySelector<HTMLInputElement>('.question-card .answer-option input')!,
      );
      const start = receipts.length;
      await user.click(screen.getByRole('button', { name: '提交作答' }));
      await find('heading', '服务端反馈');
      const receipt = receipts.slice(start).find((item) => item.path === '/api/v1/attempts')!;
      expect(receipt.status).toBe(201);
      expect(receipt.body).toMatchObject({ exerciseId, status: 'GRADED', hintLevel: 3 });
      expect(independentSamples(await settledProgress())).toBe(independentSamples(before));
      await user.click(screen.getByRole('link', { name: '作品与错题集' }));
      await find('button', '新建作品');
    },
  );
  await step('source editing, safe preview and actual export bytes', async () => {
    await user.click(await find('button', '新建作品'));
    const title = screen.getByLabelText('作品名称');
    await user.clear(title);
    await user.type(title, 'DOM 联调讲义');
    await user.click(screen.getByRole('button', { name: '创建源稿' }));
    await find('heading', 'DOM 联调讲义');
    artifactId = decodeURIComponent(location.hash.split('/')[1]);
    const content = 'List<Integer> 不等于 List<Number>。<script>window.stolen=true</script>';
    await user.type(screen.getByLabelText('段落 1 内容'), content);
    await user.click(screen.getByRole('button', { name: '保存版本' }));
    await screen.findByText('服务端已保存版本 1。', {}, { timeout: 25000 });
    await user.click(screen.getByRole('button', { name: '预览' }));
    expect(screen.getByText(content)).toBeInTheDocument();
    expect(document.querySelector('.document-preview script')).toBeNull();
    await user.click(screen.getByRole('button', { name: '生成导出文件' }));
    const download = await find('link', '下载 MARKDOWN');
    const response = await fetch(download.getAttribute('href')!);
    expect(response.status).toBe(200);
    const exported = await response.text();
    expect(exported).toContain('List&lt;Integer&gt;');
    expect(exported).not.toContain('<script>');
  });
  await step(
    'concurrent HTTP writer causes visible CAS conflict and preserves local text',
    async () => {
      await user.click(screen.getByRole('button', { name: '编辑源稿' }));
      const text = screen.getByLabelText('段落 1 内容');
      await user.clear(text);
      await user.type(text, '本地稿必须保留');
      const version = await (
        await fetch(`/api/v1/artifacts/${artifactId}/revisions?revision=1`)
      ).json();
      const csrf = await (await fetch('/api/v1/auth/csrf')).json();
      version.content.blocks[0].text = '另一个编辑端保存的内容';
      const saved = await fetch(`/api/v1/artifacts/${artifactId}/revisions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({
          baseRevision: 1,
          content: version.content,
          inputRefs: version.inputRefs,
        }),
      });
      expect(saved.status).toBe(201);
      await screen.findByText('服务器有新版本，草稿仍保留在本标签页。', {}, { timeout: 25000 });
      expect(screen.getByLabelText('段落 1 内容')).toHaveValue('本地稿必须保留');
      expect(screen.getByRole('button', { name: '保存版本' })).toBeDisabled();
      await user.click(screen.getByRole('button', { name: '已比较，保留本地稿并以版本 2 为基线' }));
      await user.click(screen.getByRole('button', { name: '保存版本' }));
      await screen.findByText('服务端已保存版本 3。', {}, { timeout: 25000 });
    },
  );
  await step(
    'explicit memory -> evidence-only tutor -> saved document with provenance',
    async () => {
      await user.click(screen.getByRole('link', { name: '笔记与记忆' }));
      await user.type(
        await screen.findByLabelText('你希望工作空间记住什么？'),
        '解释时请先区分读取与写入。',
      );
      await user.click(screen.getByRole('button', { name: '保存到工作空间' }));
      await screen.findByText('笔记已保存，可在后续上下文中使用。', {}, { timeout: 25000 });
      await user.click(screen.getByRole('link', { name: '导师与证据任务' }));
      await user.type(
        await screen.findByLabelText('输入你的问题'),
        'PECS 读取与写入的规则是什么？',
      );
      await user.click(screen.getByRole('button', { name: '发送问题' }));
      await user.click(await find('button', '保存为学习文档'));
      await waitFor(() => expect(document.querySelector('.editor-heading')).not.toBeNull(), {
        timeout: 25000,
      });
      tutorArtifactId = decodeURIComponent(location.hash.split('/')[1]);
      await waitFor(() => expect(document.querySelector('.source-manifest')).not.toBeNull(), {
        timeout: 25000,
      });
    },
  );
  await step(
    'revoking a source memory makes the dependent document unavailable for export',
    async () => {
      await user.click(screen.getByRole('link', { name: '笔记与记忆' }));
      await user.click(await find('button', '撤回这条记忆'));
      await waitFor(
        () => expect(screen.queryByRole('button', { name: '撤回这条记忆' })).toBeNull(),
        { timeout: 25000 },
      );
      await act(async () => {
        location.hash = `studio/${tutorArtifactId}`;
        window.dispatchEvent(new HashChangeEvent('hashchange'));
      });
      await screen.findByText(
        '此版本的来源已失效或被拒绝。请返回原始练习或证据重新构建；旧来源不能继续复练或导出。',
        {},
        { timeout: 25000 },
      );
      expect(screen.getByRole('button', { name: '生成导出文件' })).toBeDisabled();
    },
  );
  await step('finite rule evaluator and optimistic concurrency preferences', async () => {
    await user.click(screen.getByRole('link', { name: '类型实验' }));
    await user.click(await find('button', '检验类型关系'));
    await find('heading', '规则求值结果');
    expect(
      within(document.querySelector('.lab-result') as HTMLElement).getByText('拒绝', {
        exact: true,
      }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: '偏好与能力' }));
    const initial = await fetch('/api/v1/me/preferences');
    expect(initial.status).toBe(200);
    let revision = (await initial.json()).revision;
    const theme = await screen.findByLabelText('主题');
    const reducedMotion = screen.getByLabelText('减少界面动效') as HTMLInputElement;
    for (const desired of [
      { theme: 'dark', reducedMotion: true },
      { theme: 'light', reducedMotion: false },
    ]) {
      await user.selectOptions(theme, desired.theme);
      if (reducedMotion.checked !== desired.reducedMotion) await user.click(reducedMotion);
      await user.click(screen.getByRole('button', { name: '保存显示偏好' }));
      await screen.findByText('服务端已保存显示偏好。', {}, { timeout: 25000 });
      expect(theme).toHaveValue(desired.theme);
      expect(reducedMotion.checked).toBe(desired.reducedMotion);
      expect(document.documentElement.dataset.theme).toBe(desired.theme);
      expect(document.documentElement.dataset.reducedMotion).toBe(String(desired.reducedMotion));
      const response = await fetch('/api/v1/me/preferences');
      expect(response.status).toBe(200);
      const saved = await response.json();
      expect(saved).toMatchObject({ ...desired, revision: revision + 1 });
      revision = saved.revision;
    }
  });
  await step('account profile CAS and actual analytics totals', async () => {
    const displayName = await screen.findByLabelText(/显示名称可使用中文/);
    await user.clear(displayName);
    await user.type(displayName, '真实联调学习者');
    await user.click(screen.getByRole('button', { name: '保存个人资料' }));
    await screen.findByText('个人资料已保存。');
    const profile = await (await fetch('/api/v1/me/profile')).json();
    expect(profile.displayName).toBe('真实联调学习者');
    expect(profile.revision).toBeGreaterThan(0);
    await user.click(screen.getByRole('link', { name: '进度与复习' }));
    await find('heading', '学习活动趋势');
    await user.click(screen.getByRole('button', { name: '近 7 天' }));
    const analytics = await (await fetch('/api/v1/me/analytics?days=7')).json();
    expect(analytics.daily).toHaveLength(7);
    expect(analytics.totals.gradedAttempts).toBeGreaterThanOrEqual(2);
    expect(analytics.totals.completedReadings).toBeGreaterThanOrEqual(1);
    await waitFor(() =>
      expect(document.querySelector('.analytics-totals strong')?.textContent).toBe(
        String(analytics.totals.gradedAttempts),
      ),
    );
  });
  await step(
    'private image upload links an actual asset reference to editable slides and office export bytes',
    async () => {
      await user.click(screen.getByRole('link', { name: '作品与错题集' }));
      await user.click(await find('button', '新建作品'));
      const title = screen.getByLabelText('作品名称');
      await user.clear(title);
      await user.type(title, '带真实图片的幻灯片');
      await user.selectOptions(screen.getByLabelText('源稿类型'), 'SLIDES');
      await user.click(screen.getByRole('button', { name: '创建源稿' }));
      await find('heading', '带真实图片的幻灯片');
      const slideArtifact = decodeURIComponent(location.hash.split('/')[1]);
      await user.click(screen.getByRole('button', { name: '插入图片' }));
      const bytes = pngFixture();
      await user.upload(
        screen.getByLabelText('选择素材文件'),
        new File([new Uint8Array(bytes)], 'verify.png', { type: 'image/png' }),
      );
      await user.click(screen.getByRole('button', { name: '上传素材' }));
      await user.click(await find('button', '插入当前幻灯片'));
      await user.click(screen.getByRole('button', { name: '保存版本' }));
      await screen.findByText('服务端已保存版本 1。', {}, { timeout: 25000 });
      const version = await (
        await fetch(`/api/v1/artifacts/${slideArtifact}/revisions?revision=1`)
      ).json();
      const image = version.content.slides[0].elements.find(
        (item: { kind: string }) => item.kind === 'IMAGE',
      );
      expect(image.assetId).toBeTruthy();
      expect(version.inputRefs).toContainEqual(
        expect.objectContaining({ kind: 'ASSET', id: image.assetId, revision: 1 }),
      );
      const stored = await fetch(`/api/v1/assets/${image.assetId}/download`);
      expect(stored.status).toBe(200);
      expect(Buffer.from(await stored.arrayBuffer())).toEqual(bytes);
      await user.selectOptions(screen.getByLabelText('导出格式'), 'PPTX');
      await user.click(screen.getByRole('button', { name: '生成导出文件' }));
      const download = await find('link', '下载 PPTX');
      const exported = await fetch(download.getAttribute('href')!);
      expect(exported.status).toBe(200);
      expect(exported.headers.get('Content-Type')).toContain('presentationml');
      expect(
        Buffer.from(await exported.arrayBuffer())
          .subarray(0, 2)
          .toString(),
      ).toBe('PK');
    },
  );
  await step('document office and PDF exports are actual downloadable files', async () => {
    await act(async () => {
      location.hash = `studio/${artifactId}`;
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    await find('heading', 'DOM 联调讲义');
    for (const format of ['DOCX', 'PDF']) {
      await waitFor(() =>
        expect(screen.getByRole('button', { name: '生成导出文件' })).toBeEnabled(),
      );
      await user.selectOptions(screen.getByLabelText('导出格式'), format);
      await user.click(screen.getByRole('button', { name: '生成导出文件' }));
      const link = await find('link', `下载 ${format}`);
      const response = await fetch(link.getAttribute('href')!);
      expect(response.status).toBe(200);
      const bytes = Buffer.from(await response.arrayBuffer());
      expect(bytes.subarray(0, format === 'PDF' ? 5 : 2).toString()).toBe(
        format === 'PDF' ? '%PDF-' : 'PK',
      );
    }
  });
  await step(
    'evidence-only study workflow produces source-linked editable artifacts through visible controls',
    async () => {
      await user.click(screen.getByRole('link', { name: '学习创作任务' }));
      await user.type(
        await screen.findByLabelText('你想真正理解什么？'),
        '用正反例分析 PECS 的读取与写入边界',
      );
      await user.click(screen.getByRole('button', { name: '开始学习创作' }));
      await find('heading', '已经留下的作品');
      await within(document.querySelector('.study-detail') as HTMLElement).findByText(
        '已完成',
        { exact: true },
        { timeout: 35000 },
      );
      const runs = await (await fetch(`/api/v1/workspaces/${workspaceA}/agent-runs`)).json();
      const studyRun = runs.find((item: { steps: { key: string }[] }) =>
        item.steps.some((step) => step.key === 'planner'),
      );
      expect(studyRun).toBeTruthy();
      expect(studyRun.spentTokens).toBe(0);
      const result = await (await fetch(`/api/v1/agent-runs/${studyRun.id}/study`)).json();
      expect(result.mode).toBe('EVIDENCE_ONLY');
      expect(result.requestedOutputs).toEqual(['DOCUMENT', 'SLIDES']);
      expect(result.artifactIds.length).toBeGreaterThanOrEqual(2);
      await waitFor(() =>
        expect(document.querySelectorAll('.study-artifacts button').length).toBe(
          result.artifactIds.length,
        ),
      );
      const products = await Promise.all(
        result.artifactIds.map(async (id: string) =>
          (await fetch(`/api/v1/artifacts/${id}`)).json(),
        ),
      );
      expect(products.map((product) => product.kind)).toEqual(
        expect.arrayContaining(['DOCUMENT', 'SLIDES']),
      );
      expect(products.every((product) => product.currentRevision >= 1)).toBe(true);
    },
  );
  await step('second workspace isolates persisted attempts and source drafts', async () => {
    await user.click(screen.getByRole('button', { name: '新建工作空间' }));
    const name = await screen.findByLabelText('工作空间名称');
    await user.clear(name);
    await user.type(name, 'DOM 联调 B');
    await user.click(screen.getByRole('button', { name: '创建工作空间' }));
    await waitFor(
      () =>
        expect((screen.getByLabelText('当前工作空间') as HTMLSelectElement).value).not.toBe(
          workspaceA,
        ),
      { timeout: 25000 },
    );
    await user.click(screen.getByRole('link', { name: '练习与反馈' }));
    await screen.findByText('尚未收到作答记录。完成一题后，反馈与错题集入口会出现在这里。');
    expect(document.querySelector('.history-row')).toBeNull();
    await user.selectOptions(screen.getByLabelText('当前工作空间'), workspaceA);
    await waitFor(
      () => expect(document.querySelectorAll('.history-row').length).toBeGreaterThanOrEqual(2),
      { timeout: 25000 },
    );
  });
  await step('logout clears private client caches', async () => {
    await user.click(screen.getByRole('button', { name: '退出登录' }));
    await find('heading', '回到你的工作台');
    expect(Object.keys(sessionStorage).filter((k) => k.startsWith('tf.'))).toHaveLength(0);
  });
  await runWorkbenchJourney(workbenchDriver(user), step);
  completed = true;
});
