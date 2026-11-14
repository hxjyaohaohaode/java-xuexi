import { webcrypto } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { afterAll, beforeAll, expect, it, vi } from 'vitest';
import { act, configure, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../src/App';

const base = process.env.TYPEFORGE_BASE_URL;
const nativeFetch = globalThis.fetch;
const cookies = new Map<string, string>();
const steps: { name: string; status: string }[] = [];
let failure: string | undefined;
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
    const result = await nativeFetch(url, { ...init, headers });
    for (const cookie of result.headers.getSetCookie()) {
      const pair = cookie.split(';', 1)[0];
      const i = pair.indexOf('=');
      cookies.set(pair.slice(0, i), pair.slice(i + 1));
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
        status: failure ? 'FAIL' : 'PASS',
        failure,
        limits: [
          'Does not verify layout, screenshots, native downloads or real keyboard/touch rendering.',
          'Cookie storage is a transport adapter for Node fetch; no server response is mocked.',
        ],
      },
      null,
      2,
    ) + '\n',
  );
  vi.unstubAllGlobals();
});
const step = async (name: string, work: () => Promise<void>) => {
  try {
    await work();
    steps.push({ name, status: 'PASS' });
  } catch (error) {
    failure = String(error);
    steps.push({ name, status: 'FAIL' });
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
      screen.getByLabelText('密码', { exact: false }),
      `BrowserStudy-${crypto.randomUUID()}`,
    );
    await user.click(screen.getByRole('button', { name: '注册并进入' }));
    const input = await screen.findByLabelText('工作空间名称', { exact: true }, { timeout: 25000 });
    await user.clear(input);
    await user.type(input, 'DOM 联调 A');
    await user.click(screen.getByRole('button', { name: '创建工作空间' }));
    await find('heading', '泛型与类型安全');
    workspaceA = (screen.getByLabelText('当前工作空间') as HTMLSelectElement).value;
    await user.click(await find('button', '记录已读'));
    await screen.findByText('服务端已记录本次阅读。', {}, { timeout: 25000 });
  });
  await step('wrong attempt -> notebook source -> real reattempt', async () => {
    await user.click(screen.getByRole('link', { name: '练习与反馈' }));
    await waitFor(() =>
      expect(
        document.querySelectorAll('.question-card .answer-option input').length,
      ).toBeGreaterThan(1),
    );
    await user.click(screen.getByLabelText(/new List<String>\[2\]/));
    expect(document.querySelectorAll('.question-card input:checked')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: '提交作答' }));
    await find('heading', '服务端反馈');
    await user.click(await find('button', '加入可复练错题集'));
    await find('heading', '重新检查你的判断');
    await user.type(
      screen.getByLabelText('先写下这次的预测或理由'),
      '先判断泛型不变性，再检查通配符。',
    );
    await user.click(screen.getByRole('button', { name: '记录预测' }));
    await screen.findByText('已记录你的预测；预测不会被当作独立掌握证据。', {}, { timeout: 25000 });
    await user.click(
      document.querySelector<HTMLInputElement>('.notebook-block .answer-option input')!,
    );
    await user.click(screen.getByRole('button', { name: '提交复练' }));
    await find('heading', /复练反馈/);
    const r = await fetch(`/api/v1/workspaces/${workspaceA}/attempts`);
    expect(r.status).toBe(200);
    const attempts = await r.json();
    expect(attempts.length).toBeGreaterThanOrEqual(2);
    expect(attempts.some((a: { hintLevel: number }) => a.hintLevel > 0)).toBe(true);
  });
  await step('source editing, safe preview and actual export bytes', async () => {
    await user.click(screen.getByRole('button', { name: '新建作品' }));
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
});
