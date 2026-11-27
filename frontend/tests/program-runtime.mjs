import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { chromium } from 'playwright';

// Exercises the packaged UI, real authenticated API, queue, and isolated worker together.
const root = fileURLToPath(new URL('../../', import.meta.url));
const jar = path.resolve(
  process.argv[2] || path.join(root, 'backend/target/typeforge-backend-0.6.0-SNAPSHOT.jar'),
);
const temp = await mkdtemp(path.join(os.tmpdir(), 'typeforge-code-browser-'));
const base = 'http://127.0.0.1:18080';
const service = spawn(
  'python3',
  [
    path.join(root, 'workers/compiler/start_workbench.py'),
    jar,
    '--port',
    '18080',
    '--worker-port',
    '18091',
  ],
  {
    cwd: temp,
    stdio: ['ignore', 'inherit', 'inherit'],
    env: {
      ...process.env,
      TYPEFORGE_DB_URL:
        'jdbc:h2:file:' +
        path.join(temp, 'demo') +
        ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;DEFAULT_NULL_ORDERING=HIGH',
      TYPEFORGE_DB_USER: 'sa',
      TYPEFORGE_DB_PASSWORD: '',
    },
  },
);
const stopped = new Promise((resolve) => service.once('close', resolve));
let browser;
const report = { result: 'FAIL', cases: [] };
try {
  let ready = false;
  const deadline = Date.now() + 240_000;
  while (Date.now() < deadline && service.exitCode === null) {
    const health = await fetch(base + '/api/v1/health')
      .then((r) => r.json())
      .catch(() => null);
    if (health?.status === 'UP') {
      ready = true;
      break;
    }
    await delay(500);
  }
  assert.ok(ready, 'packaged learning service must be ready');
  browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_EXECUTABLE_PATH || undefined,
  });
  const page = await browser.newPage({ baseURL: base, viewport: { width: 1440, height: 960 } });
  page.setDefaultTimeout(30_000);
  await page.goto('/');
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await page.getByLabel('用户名').fill('program-' + randomUUID().slice(0, 15));
  await page.getByLabel('密码', { exact: true }).fill(randomUUID() + 'Aa7!');
  await page.getByRole('button', { name: '注册并进入' }).click();
  await page.getByLabel('工作空间名称').fill('程序运行验收');
  await page.getByRole('button', { name: '创建工作空间' }).click();
  const capabilities = await page.request.get(base + '/api/v1/capabilities');
  assert.equal(capabilities.status(), 200);
  assert.ok((await capabilities.json()).enabled.includes('UNTRUSTED_CODE_EXECUTION'));
  await page.locator('.classroom-stage').getByRole('button', { name: '代码实验', exact: true }).click();
  assert.match(page.url(), /scene=lab/);
  const pane = page.getByRole('region', { name: '代码工作台' });
  const mode = pane.getByLabel('语言和任务');
  const course = JSON.parse(
    await readFile(path.join(root, 'knowledge/peer-course-20261118.json'), 'utf8'),
  );
  const raw = course.examples.find((example) => example.id === '16-raw-pollution');
  assert.equal(raw.warning, 'compiler.warn.unchecked.call.mbr.of.raw.type');
  const cases = [
    [
      'RUN_JAVA',
      'Main.java',
      'class Demo { public static void main(String[] args) { System.out.println(2 * new java.util.Scanner(System.in).nextInt()); } }',
      '21',
      '42\n',
    ],
    ['RUN_JAVA', 'Main.java', raw.code, '', raw.stdout + '\n', raw.warning],
    ['RUN_PYTHON', 'main.py', 'print(input().upper())', 'a学习', 'A学习\n'],
  ];
  for (const [language, label, source, stdin, expected, warning] of cases) {
    await mode.selectOption(language);
    await pane.getByLabel(label).fill(source);
    await pane.getByLabel('标准输入').fill(stdin);
    const submitted = page.waitForResponse(
      (r) => r.url().endsWith('/api/v1/compile-jobs') && r.request().method() === 'POST',
    );
    await pane.getByRole('button', { name: '运行代码', exact: true }).click();
    const response = await submitted;
    assert.equal(response.status(), 202);
    const { id } = await response.json();
    await pane.locator('pre.compiler-output').filter({ hasText: expected.trim() }).waitFor();
    assert.equal(await pane.locator('pre.compiler-output').textContent(), expected);
    const saved = await (await page.request.get(base + '/api/v1/compile-jobs/' + id)).json();
    assert.equal(saved.status, 'SUCCEEDED');
    assert.equal(saved.output, expected);
    assert.equal(saved.attemptId, null);
    if (warning) {
      assert.ok(
        saved.diagnostics.some((item) => item.severity === 'WARNING' && item.code === warning),
      );
      await pane.locator('.diagnostic-list').getByText(warning, { exact: true }).first().waitFor();
    }
    report.cases.push({
      language: saved.language,
      mode: saved.mode,
      toolchain: saved.runtimeVersion,
      status: saved.status,
    });
  }
  await page.reload();
  await pane.locator('pre.compiler-output').filter({ hasText: 'A学习' }).waitFor();
  assert.equal(await pane.getByLabel('main.py').inputValue(), 'print(input().upper())');
  report.restoredDraftAndOutput = true;
  report.result = 'PASS';
} finally {
  if (browser) await browser.close();
  service.kill('SIGTERM');
  await stopped;
  await rm(temp, { recursive: true, force: true });
  const directory = path.join(root, 'frontend/test-results');
  await mkdir(directory, { recursive: true });
  await writeFile(
    path.join(directory, 'program-runtime.json'),
    JSON.stringify(report, null, 2) + '\n',
  );
}
