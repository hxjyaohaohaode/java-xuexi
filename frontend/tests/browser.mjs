import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

// A real integration test. No intercepted fixture answers or mocked backend responses.
const base = (process.env.TYPEFORGE_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const out = path.resolve('test-results');
await mkdir(out, { recursive: true });
const executablePath = process.env.CHROMIUM_EXECUTABLE_PATH || undefined;
let browser;
try {
  browser = await chromium.launch({ headless: true, executablePath });
} catch (error) {
  await writeFile(
    path.join(out, 'browser-report.json'),
    JSON.stringify(
      {
        at: new Date().toISOString(),
        status: String(error).includes('Operation not permitted') ? 'ENVIRONMENT_BLOCKED' : 'FAIL',
        stage: 'browser-launch',
        error: String(error),
        results: [],
      },
      null,
      2,
    ) + '\n',
  );
  throw error;
}
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  baseURL: base,
  acceptDownloads: true,
  reducedMotion: 'reduce',
});
const page = await context.newPage();
page.setDefaultTimeout(25000);
const failures = [];
const pageErrors = [];
const consoleErrors = [];
const results = [];
const expectedConflict = { url: '', active: false, responses: 0, consoleMessages: 0 };
function monitor(p) {
  p.on('pageerror', (error) => pageErrors.push(error.message));
  p.on('response', (response) => {
    if (
      expectedConflict.active &&
      response.status() === 409 &&
      response.request().method() === 'POST' &&
      response.url() === expectedConflict.url
    )
      expectedConflict.responses += 1;
  });
  p.on('console', (entry) => {
    if (entry.type() !== 'error') return;
    const text = entry.text();
    const url = entry.location().url;
    if (/\b401\b/.test(text) && url === `${base}/api/v1/auth/session`) return;
    if (
      expectedConflict.active &&
      expectedConflict.consoleMessages === 0 &&
      url === expectedConflict.url &&
      /\b409\b/.test(text)
    ) {
      expectedConflict.consoleMessages += 1;
      return;
    }
    consoleErrors.push({ text, url });
  });
}
monitor(page);
const check = async (name, work) => {
  process.stdout.write(`${name} ... `);
  try {
    await work();
    results.push({ name, status: 'PASS' });
    console.log('PASS');
  } catch (error) {
    results.push({ name, status: 'FAIL', error: String(error) });
    failures.push(name);
    console.log('FAIL');
    throw error;
  }
};
const until = async (work, message, timeout = 25000) => {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (await work()) return;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(message);
};
let workspaceA, artifactId;
const username = `web_${Date.now().toString(36)}`;
const password = `BrowserStudy-${crypto.randomUUID()}`;
const docTitle = '浏览器验收 · 泛型学习讲义';
try {
  await check('register, login and create workspace through visible controls', async () => {
    await page.goto(base);
    await page.getByRole('button', { name: '注册', exact: true }).click();
    await page.getByLabel('用户名', { exact: false }).fill(username);
    await page.getByLabel('密码', { exact: false }).fill(password);
    await page.getByRole('button', { name: '注册并进入', exact: true }).click();
    await page.getByLabel('工作空间名称', { exact: true }).fill('浏览器验收 A');
    await page.getByRole('button', { name: '创建工作空间', exact: true }).click();
    await page.getByRole('heading', { name: '泛型与类型安全', exact: true }).waitFor();
    workspaceA = await page.locator('#workspace-select').inputValue();
    assert.match(workspaceA, /^[a-f0-9-]{36}$/);
    await page.getByRole('button', { name: '记录已读', exact: true }).click();
    await page.getByText('服务端已记录本次阅读。', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(out, '01-learning-desktop.png'), fullPage: true });
  });
  await check(
    'wrong answer creates evidence notebook and notebook submits a real new attempt',
    async () => {
      await page.getByRole('link', { name: '练习与反馈', exact: true }).click();
      await page.locator('.question-card .answer-option input').first().waitFor();
      await page
        .locator('.question-card .answer-option')
        .filter({ hasText: 'new List<String>[2]' })
        .locator('input')
        .check();
      await page.getByRole('button', { name: '提交作答', exact: true }).click();
      await page.getByRole('heading', { name: '服务端反馈', exact: true }).waitFor();
      await page.getByRole('button', { name: '加入可复练错题集', exact: true }).click();
      await page.getByRole('heading', { name: '重新检查你的判断', exact: true }).waitFor();
      await page
        .getByLabel('先写下这次的预测或理由', { exact: true })
        .fill('我先区分具体类型实参与通配符，再检查允许的读写方向。');
      await page.getByRole('button', { name: '记录预测', exact: true }).click();
      await page
        .getByText('已记录你的预测；预测不会被当作独立掌握证据。', { exact: true })
        .waitFor();
      await page.locator('.notebook-block .answer-option input').first().check();
      await page.getByRole('button', { name: '提交复练', exact: true }).click();
      await page.getByRole('heading', { name: /复练反馈/ }).waitFor();
      const response = await context.request.get(
        `${base}/api/v1/workspaces/${workspaceA}/attempts`,
      );
      assert.equal(response.status(), 200);
      const attempts = await response.json();
      assert.ok(attempts.length >= 2);
      assert.ok(attempts.some((a) => a.hintLevel > 0));
      assert.equal(new Set(attempts.map((a) => a.workspaceId)).size, 1);
      await page.getByRole('button', { name: '查看关联证据', exact: true }).click();
      await page.locator('.notebook-block .evidence-card').first().waitFor();
      await page.setViewportSize({ width: 390, height: 844 });
      assert.ok(await page.getByRole('button', { name: '提交复练', exact: true }).isVisible());
      assert.ok(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
      );
      await page.screenshot({ path: path.join(out, '02-notebook-mobile.png'), fullPage: true });
      await page.setViewportSize({ width: 1440, height: 1000 });
    },
  );
  await check(
    'document source saves, previews safely, and downloads actual Markdown bytes',
    async () => {
      await page.getByRole('button', { name: '新建作品', exact: true }).click();
      await page.getByLabel('作品名称', { exact: true }).fill(docTitle);
      await page.getByLabel('源稿类型', { exact: true }).selectOption('DOCUMENT');
      await page.getByRole('button', { name: '创建源稿', exact: true }).click();
      await page.getByRole('heading', { name: docTitle, exact: true }).waitFor();
      artifactId = decodeURIComponent(new URL(page.url()).hash.split('/')[1]);
      const text =
        '泛型是编译期类型约束。List<Integer> 不是 List<Number> 的子类型。\n<script>window.__unsafePreview=true</script>';
      await page.getByLabel('段落 1 内容', { exact: true }).fill(text);
      await page.getByRole('button', { name: '保存版本', exact: true }).click();
      await page.getByText('服务端已保存版本 1。', { exact: true }).waitFor();
      await page.getByRole('button', { name: '预览', exact: true }).click();
      await page.getByText(text, { exact: true }).waitFor();
      assert.equal(await page.evaluate(() => window.__unsafePreview), undefined);
      await page.getByRole('button', { name: '生成导出文件', exact: true }).click();
      await page.getByRole('link', { name: '下载 MARKDOWN', exact: true }).waitFor();
      const [download] = await Promise.all([
        page.waitForEvent('download'),
        page.getByRole('link', { name: '下载 MARKDOWN', exact: true }).click(),
      ]);
      const destination = path.join(out, 'exported-learning-note.md');
      await download.saveAs(destination);
      const exported = await readFile(destination, 'utf8');
      assert.ok(exported.includes('List&lt;Integer&gt;'));
      assert.ok(!exported.includes('<script>'));
      await page.screenshot({ path: path.join(out, '03-document-desktop.png'), fullPage: true });
    },
  );
  await check(
    'two-tab CAS conflict preserves the local draft until an explicit rebase',
    async () => {
      await page.getByRole('button', { name: '编辑源稿', exact: true }).click();
      await page.getByLabel('段落 1 内容', { exact: true }).fill('本地修改不可丢失');
      const other = await context.newPage();
      monitor(other);
      other.setDefaultTimeout(25000);
      await other.goto(`${base}/#studio/${artifactId}`);
      await other.getByLabel('段落 1 内容', { exact: true }).fill('另一个标签页保存的内容');
      await other.getByRole('button', { name: '保存版本', exact: true }).click();
      await other.getByText('服务端已保存版本 2。', { exact: true }).waitFor();
      // Verify the server fence as well as the UI's event-driven conflict state.
      // Exactly this endpoint and exactly one intentional stale POST may emit a 409.
      expectedConflict.url = `${base}/api/v1/artifacts/${artifactId}/revisions`;
      expectedConflict.active = true;
      const staleWrite = await page.evaluate(
        async ({ id, title }) => {
          const csrf = await (await fetch('/api/v1/auth/csrf')).json();
          const response = await fetch(`/api/v1/artifacts/${id}/revisions`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
            body: JSON.stringify({
              baseRevision: 1,
              content: {
                kind: 'DOCUMENT',
                schemaVersion: '1.0',
                title,
                blocks: [
                  {
                    id: 'stale-cas-proof',
                    kind: 'PARAGRAPH',
                    text: 'This stale write must be rejected.',
                    evidenceRefs: [],
                  },
                ],
              },
              inputRefs: [],
            }),
          });
          return { status: response.status, problem: await response.json() };
        },
        { id: artifactId, title: docTitle },
      );
      assert.equal(staleWrite.status, 409);
      assert.equal(staleWrite.problem.code, 'VERSION_CONFLICT');
      await page.bringToFront();
      await page.getByText('服务器有新版本，草稿仍保留在本标签页。', { exact: true }).waitFor();
      assert.equal(
        await page.getByLabel('段落 1 内容', { exact: true }).inputValue(),
        '本地修改不可丢失',
      );
      assert.equal(
        await page.getByRole('button', { name: '保存版本', exact: true }).isDisabled(),
        true,
      );
      await page
        .getByRole('button', { name: '已比较，保留本地稿并以版本 2 为基线', exact: true })
        .click();
      await page.getByRole('button', { name: '保存版本', exact: true }).click();
      await page.getByText('服务端已保存版本 3。', { exact: true }).waitFor();
      await other.close();
      expectedConflict.active = false;
      assert.equal(expectedConflict.responses, 1);
      assert.ok(expectedConflict.consoleMessages <= 1);
    },
  );
  await check('workspace switch has isolated artifacts, attempts and drafts', async () => {
    await page.getByRole('button', { name: '新建工作空间', exact: true }).click();
    await page.getByLabel('工作空间名称', { exact: true }).fill('浏览器验收 B');
    await page.getByRole('button', { name: '创建工作空间', exact: true }).click();
    await until(
      async () => (await page.locator('#workspace-select').inputValue()) !== workspaceA,
      'workspace did not switch',
    );
    await page.getByRole('heading', { name: '作品不在当前工作空间', exact: true }).waitFor();
    assert.equal(await page.locator('.editor-heading').count(), 0);
    await page.getByRole('link', { name: '练习与反馈', exact: true }).click();
    await page
      .getByText('尚未收到作答记录。完成一题后，反馈与错题集入口会出现在这里。', { exact: true })
      .waitFor();
    await page.locator('#workspace-select').selectOption(workspaceA);
    await until(
      async () => (await page.locator('.history-row').count()) >= 2,
      'saved attempts did not restore after switching back',
    );
  });
  await check(
    'finite rule result and persisted preferences respond through real services',
    async () => {
      await page.getByRole('link', { name: '类型实验', exact: true }).click();
      await page.getByRole('button', { name: '检验类型关系', exact: true }).click();
      await page.getByRole('heading', { name: '规则求值结果', exact: true }).waitFor();
      assert.ok(await page.locator('.lab-result').getByText('拒绝', { exact: true }).isVisible());
      await page.getByRole('link', { name: '偏好与能力', exact: true }).click();
      await page.getByLabel('主题', { exact: true }).selectOption('light');
      await page.getByLabel('减少界面动效', { exact: true }).check();
      await page.getByRole('button', { name: '保存显示偏好', exact: true }).click();
      await page.getByText('服务端已保存显示偏好。', { exact: true }).waitFor();
    },
  );
  await check(
    'evidence-only tutor is explicit, persistent and converts to a source document',
    async () => {
      await page.getByRole('link', { name: '导师与证据任务', exact: true }).click();
      await page.getByLabel('输入你的问题', { exact: true }).fill('PECS 读取与写入的规则是什么？');
      await page.getByRole('button', { name: '发送问题', exact: true }).click();
      await page
        .getByRole('button', { name: '保存为学习文档', exact: true })
        .waitFor({ timeout: 35000 });
      assert.ok(await page.getByText('本地证据整理', { exact: true }).isVisible());
      await page.getByRole('button', { name: '保存为学习文档', exact: true }).click();
      await page.locator('.editor-heading').waitFor();
      await page.locator('.source-manifest').waitFor();
    },
  );
  await check(
    'keyboard logout clears private client state and runtime has no browser exceptions',
    async () => {
      await page.getByRole('button', { name: '退出登录', exact: true }).focus();
      await page.keyboard.press('Enter');
      await page.getByRole('heading', { name: '回到你的工作台', exact: true }).waitFor();
      assert.equal(
        await page.evaluate(
          () => Object.keys(sessionStorage).filter((k) => k.startsWith('tf.')).length,
        ),
        0,
      );
      assert.deepEqual(pageErrors, []);
      assert.deepEqual(consoleErrors, []);
    },
  );
} catch (error) {
  console.error(String(error));
  await writeFile(path.join(out, 'failure-dom.txt'), await page.locator('body').innerText()).catch(
    () => {},
  );
} finally {
  const report = {
    status: failures.length ? 'FAIL' : 'PASS',
    baseUrl: base,
    at: new Date().toISOString(),
    results,
    pageErrors,
    consoleErrors,
    expectedConflict: {
      url: expectedConflict.url,
      responses: expectedConflict.responses,
      consoleMessages: expectedConflict.consoleMessages,
      expectedResponses: 1,
    },
    notes: [
      'Real demo backend and session/CSRF, no API response fixtures.',
      'Three screenshots inspect desktop reading, mobile notebook and saved document export.',
      'No model provider was invoked; tutor used EVIDENCE_ONLY.',
    ],
  };
  await writeFile(path.join(out, 'browser-report.json'), JSON.stringify(report, null, 2) + '\n');
  await browser.close();
}
if (failures.length) process.exitCode = 1;
