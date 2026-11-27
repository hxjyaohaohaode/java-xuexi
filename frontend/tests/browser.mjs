import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { verifyClassroom } from './classroom-journey.mjs';
import { runWorkbenchJourney } from './workbench-journey.mjs';

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
  colorScheme: 'light',
});
const page = await context.newPage();
page.setDefaultTimeout(25000);
const failures = [];
const pageErrors = [];
const consoleErrors = [];
const results = [];
const visualChecks = [];
let failurePages = [];
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
    results.push({ name, status: 'FAIL', error: sanitizeText(error) });
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
const settledProgress = async () => {
  let progress;
  await until(async () => {
    const response = await context.request.get(`${base}/api/v1/me/progress`);
    assert.equal(response.status(), 200);
    progress = await response.json();
    return !progress.rebuilding;
  }, 'Learning evidence projection did not settle');
  return progress;
};
const independentSamples = (progress) =>
  progress.items.reduce((count, item) => count + item.independentSamples, 0);
let workspaceA, artifactId;
const username = `web_${Date.now().toString(36)}`;
const password = `BrowserStudy-${crypto.randomUUID()}`;
const docTitle = '浏览器验收 · 泛型学习讲义';
const sanitizeText = (value) =>
  [
    username,
    password,
    process.env.TYPEFORGE_TEST_EDITOR_AUTHOR,
    process.env.TYPEFORGE_TEST_EDITOR_REVIEWER,
    process.env.TYPEFORGE_TEST_ADMIN_USERNAME,
    process.env.TYPEFORGE_TEST_EDITOR_PASSWORD,
  ]
    .filter(Boolean)
    .reduce((text, secret) => text.replaceAll(secret, '[redacted test identity]'), String(value));
// Field wraps its label, control and optional hint. A select's option text also
// participates in getByLabel(), so match the actual title span and its own select.
const fieldSelect = (p, title) =>
  p
    .locator('label.field')
    .filter({ has: p.getByText(title, { exact: true }) })
    .locator('select');
const verifyReadingSurfaces = async (p, filename) => {
  const metrics = await p.evaluate(() => {
    const visible = (element) => {
      const rect = element.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0 && getComputedStyle(element).visibility !== 'hidden';
    };
    const luminance = (color) => {
      const channels = color.match(/[\d.]+/g)?.map(Number);
      if (!channels || channels.length < 3 || (channels.length === 4 && channels[3] !== 1))
        throw new Error(`Expected an opaque rendered reading color, received ${color}`);
      const linear = channels.slice(0, 3).map((channel) => {
        const value = channel / 255;
        return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    };
    const readingSurfaces = [
      '.batch-original',
      '.content-diff-values pre',
      '.notebook-code-comparison pre',
    ].flatMap((selector) => {
      const ratios = Array.from(document.querySelectorAll(selector))
        .filter(visible)
        .map((element) => {
          const style = getComputedStyle(element);
          const foreground = luminance(style.color);
          const background = luminance(style.backgroundColor);
          return (
            (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05)
          );
        });
      return ratios.length
        ? [{ selector, samples: ratios.length, minimumContrast: Math.min(...ratios) }]
        : [];
    });
    const contentTabs = Array.from(document.querySelectorAll('.content-tabs'))
      .filter(visible)
      .map((tabs) => {
        const frame = tabs.getBoundingClientRect();
        const selected = Array.from(tabs.querySelectorAll('[role="tab"][aria-selected="true"]'));
        return {
          selectedCount: selected.length,
          selectedFullyVisible:
            selected.length === 1 &&
            selected.every((element) => {
              const rect = element.getBoundingClientRect();
              return (
                rect.left >= frame.left - 1 &&
                rect.right <= frame.right + 1 &&
                rect.top >= frame.top - 1 &&
                rect.bottom <= frame.bottom + 1 &&
                rect.left >= -1 &&
                rect.right <= window.innerWidth + 1
              );
            }),
        };
      });
    return {
      width: window.innerWidth,
      theme: document.documentElement.dataset.theme,
      readingSurfaces,
      contentTabs,
    };
  });
  for (const surface of metrics.readingSurfaces)
    assert.ok(
      surface.minimumContrast >= 4.5,
      `${filename}: ${surface.selector} normal text contrast must be at least 4.5:1, received ${surface.minimumContrast.toFixed(2)}:1`,
    );
  for (const tabs of metrics.contentTabs) {
    assert.equal(tabs.selectedCount, 1, `${filename}: exactly one content step must be selected`);
    assert.ok(
      tabs.selectedFullyVisible,
      `${filename}: the selected content step must remain fully visible`,
    );
  }
  visualChecks.push({ filename, ...metrics });
};
const capturePage = async (p, filename) => {
  await p.evaluate(async () => {
    await document.fonts.ready;
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
  await verifyReadingSurfaces(p, filename);
  await p.screenshot({ path: path.join(out, filename), fullPage: true });
};
const describeControls = async (p) => {
  // Never collect input/textarea values, cookies, storage, headers or raw HTML.
  const summary = await p.evaluate(() => {
    const text = (value) =>
      String(value || '')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 180);
    const visible = (el) => {
      const rect = el.getBoundingClientRect();
      const style = getComputedStyle(el);
      return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden';
    };
    return {
      route: location.pathname + location.hash,
      headings: Array.from(document.querySelectorAll('h1,h2,h3'))
        .filter(visible)
        .slice(0, 30)
        .map((el) => text(el.textContent)),
      controls: Array.from(document.querySelectorAll('input,textarea,select,button,a[href]'))
        .slice(0, 200)
        .map((el) => ({
          tag: el.tagName.toLowerCase(),
          type: el.getAttribute('type'),
          id: el.id || undefined,
          ariaLabel: text(el.getAttribute('aria-label')) || undefined,
          labels: Array.from(el.labels || []).map((label) =>
            text(
              label.querySelector(':scope > span')?.textContent ||
                Array.from(label.childNodes)
                  .filter((node) => node.nodeType === Node.TEXT_NODE)
                  .map((node) => node.textContent)
                  .join(' '),
            ),
          ),
          text: el.matches('button,a') ? text(el.textContent) : undefined,
          options: el.matches('select')
            ? Array.from(el.options).map((option) => text(option.textContent))
            : undefined,
          disabled: 'disabled' in el ? el.disabled : undefined,
          visible: visible(el),
        })),
    };
  });
  return JSON.parse(sanitizeText(JSON.stringify(summary)));
};
function workbenchDriver() {
  const scope = (selector) => (selector ? page.locator(selector) : page);
  const role = (kind, name, selector) =>
    scope(selector).getByRole(kind, { name, exact: typeof name === 'string' });
  const label = (name, selector) =>
    scope(selector)
      .getByLabel(name, { exact: false })
      .and(scope(selector).locator('input,textarea,select'));
  const readJson = async (route) => {
    const response = await context.request.get(`${base}/api/v1${route}`);
    assert.equal(response.status(), 200, route);
    return response.json();
  };
  const capture = async (filename, selector) => {
    if (selector) {
      await verifyReadingSurfaces(page, filename);
      await page.locator(selector).screenshot({ path: path.join(out, filename) });
    } else await capturePage(page, filename);
  };
  return {
    async fill(name, value, selector) {
      await label(name, selector).fill(value);
    },
    async verifyPattern(name, accepted, rejected) {
      const input = label(name);
      const before = await input.inputValue();
      const attribute = await input.getAttribute('pattern');
      assert.ok(attribute, `${name} must expose its actual HTML constraint`);
      const expression = new RegExp(`^(?:${attribute})$`, 'v');
      for (const [values, expected] of [
        [accepted, true],
        [rejected, false],
      ])
        for (const value of values) {
          assert.equal(expression.test(value), expected, `${name}: ${value}`);
          await input.fill(value);
          assert.equal(await input.inputValue(), value);
          assert.equal(
            await input.evaluate((element) => element.checkValidity()),
            expected,
            `${name} native validity must enforce ${JSON.stringify(value)}`,
          );
        }
      await input.fill(before);
    },
    async route() {
      return page.evaluate(() => location.hash);
    },
    async click(kind, name, selector) {
      await role(kind, name, selector).click();
    },
    async select(name, value, selector) {
      await label(name, selector).selectOption(value);
    },
    async check(name, selector) {
      await role('checkbox', name, selector).check();
    },
    async summary(name, selector) {
      const target = scope(selector).locator('summary').filter({ hasText: name });
      await target.waitFor({ state: 'visible' });
      assert.equal(await target.count(), 1, `Summary must be unambiguous: ${name}`);
      if (!(await target.evaluate((element) => element.parentElement.open))) await target.click();
    },
    async text(text, selector) {
      await scope(selector).getByText(text, { exact: false }).first().waitFor();
    },
    async heading(name, selector) {
      await role('heading', name, selector).waitFor();
    },
    async link(name, selector) {
      await role('link', name, selector).waitFor();
    },
    async disabled(kind, name, selector) {
      await until(
        async () => await role(kind, name, selector).isDisabled(),
        `${name} should be disabled`,
      );
    },
    async enabled(kind, name, selector) {
      await until(
        async () => await role(kind, name, selector).isEnabled(),
        `${name} should be enabled`,
      );
    },
    async value(name, selector) {
      return label(name, selector).inputValue();
    },
    async absentLabel(name) {
      const target = page.getByLabel(name, { exact: true });
      await target.waitFor({ state: 'detached' });
      assert.equal(await target.count(), 0);
    },
    async clickRow(selector, text, name) {
      const target = page.locator(selector).filter({ hasText: text });
      await target.waitFor({ state: 'visible' });
      assert.equal(await target.count(), 1, `Saved revision row should be unambiguous: ${text}`);
      await target.getByRole('button', { name, exact: true }).click();
    },
    async clickArtifact(id) {
      const artifact = await readJson(`/artifacts/${id}`);
      await page.locator('.artifact-list > button').filter({ hasText: artifact.title }).click();
      await role('heading', artifact.title).waitFor();
    },
    readJson,
    async mutate(route, method, body) {
      const token = await (await context.request.get(`${base}/api/v1/auth/csrf`)).json();
      const response = await context.request.fetch(`${base}/api/v1${route}`, {
        method,
        headers: { [token.headerName]: token.token, 'Idempotency-Key': crypto.randomUUID() },
        data: body,
      });
      assert.ok(
        response.status() >= 200 && response.status() < 300,
        `Actual separate client update failed: ${route} (${response.status()})`,
      );
      return response.json();
    },
    async upload(name, files) {
      await label(name).setInputFiles(files);
    },
    async report(name) {
      const anchor = role('link', name);
      const href = await anchor.getAttribute('href');
      assert.match(href, /^\/api\/v1\/evaluation-runs\/[a-f0-9-]+\/report$/);
      const response = await context.request.get(`${base}${href}`);
      assert.equal(response.status(), 200);
      const bytes = await response.body();
      assert.equal(
        createHash('sha256').update(bytes).digest('hex'),
        response.headers()['x-content-sha256'],
      );
      const [download] = await Promise.all([page.waitForEvent('download'), anchor.click()]);
      const filename = `v06-${download.suggestedFilename()}`;
      assert.match(filename, /^v06-evaluation-[a-f0-9-]+\.json$/);
      await download.saveAs(path.join(out, filename));
      assert.deepEqual(await readFile(path.join(out, filename)), bytes);
      return JSON.parse(bytes.toString());
    },
    capture,
    async captureResponsive(prefix, widths, selector, systemTheme) {
      const originalScheme = await page.evaluate(() =>
        matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
      );
      if (systemTheme) {
        const preference = await readJson('/me/preferences');
        assert.equal(
          preference.theme,
          'system',
          'System-theme captures require the real saved system preference',
        );
        await page.emulateMedia({ colorScheme: systemTheme });
        await until(
          async () =>
            (await page.evaluate(() => document.documentElement.dataset.theme)) === systemTheme,
          'The application must react to the browser color scheme before dark screenshots',
        );
      }
      try {
        for (const width of widths) {
          await page.setViewportSize({ width, height: 1000 });
          await page.evaluate(async () => {
            await document.fonts.ready;
            await new Promise((resolve) =>
              requestAnimationFrame(() => requestAnimationFrame(resolve)),
            );
          });
          assert.ok(
            await page.evaluate(
              () => document.documentElement.scrollWidth <= window.innerWidth + 1,
            ),
            `${prefix} overflows the page at ${width}px`,
          );
          const regions = page.locator(
            '.concept-map-scroll:visible,.evaluation-metric-table:visible',
          );
          for (let i = 0; i < (await regions.count()); i += 1) {
            const region = regions.nth(i);
            const scrollable = await region.evaluate(
              (element) => element.scrollWidth > element.clientWidth + 1,
            );
            await region.focus();
            assert.ok(
              await region.evaluate((element) => element === document.activeElement),
              'Overflow region must accept keyboard focus',
            );
            if (scrollable) {
              await region.evaluate((element) => {
                element.scrollLeft = 0;
              });
              await page.keyboard.press('ArrowRight');
              await until(
                async () => await region.evaluate((element) => element.scrollLeft > 0),
                'Keyboard must reach horizontally scrolled content',
              );
              await region.evaluate((element) => {
                element.scrollLeft = 0;
              });
            }
          }
          const controls = scope(selector || 'main').locator('button:visible');
          for (let i = 0; i < (await controls.count()); i += 1) {
            const size = await controls.nth(i).boundingBox();
            assert.ok(
              size && size.height >= 43,
              `${prefix} has a button below the 44px touch target at ${width}px`,
            );
          }
          await capture(`${prefix}-${width}.png`, selector);
        }
      } finally {
        await page.setViewportSize({ width: 1440, height: 1000 });
        if (systemTheme) {
          await page.emulateMedia({ colorScheme: originalScheme });
          await until(
            async () =>
              (await page.evaluate(() => document.documentElement.dataset.theme)) ===
              originalScheme,
            'The application must restore its original system color scheme',
          );
        }
      }
    },
    eventually: until,
  };
}
try {
  await check('register, login and create workspace through visible controls', async () => {
    await page.goto(base);
    await page.getByRole('button', { name: '注册', exact: true }).click();
    const usernameInput = page.getByLabel('用户名', { exact: false });
    const usernamePattern = await usernameInput.evaluate((input, fixture) => {
      // HTML pattern uses Unicode sets and implicitly matches the entire value.
      const pattern = new RegExp(`^(?:${input.pattern})$`, 'v');
      return {
        acceptsFixture: pattern.test(fixture),
        acceptsAllAllowedPunctuation: pattern.test('student-name_01.test'),
        acceptsWhitespace: pattern.test('bad name'),
      };
    }, username);
    assert.deepEqual(usernamePattern, {
      acceptsFixture: true,
      acceptsAllAllowedPunctuation: true,
      acceptsWhitespace: false,
    });
    await usernameInput.fill(username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '注册并进入', exact: true }).click();
    await page.getByLabel('工作空间名称', { exact: true }).fill('浏览器验收 A');
    await page.getByRole('button', { name: '创建工作空间', exact: true }).click();
    await page.getByRole('heading', { name: '泛型与类型安全', exact: true }).waitFor();
    workspaceA = await page.locator('#workspace-select').inputValue();
    assert.match(workspaceA, /^[a-f0-9-]{36}$/);
    const capabilities = await context.request.get(`${base}/api/v1/capabilities`);
    assert.equal(capabilities.status(), 200);
    assert.ok((await capabilities.json()).enabled.includes('EXERCISE_HINTS'));
    await page.getByRole('button', { name: '记录已读', exact: true }).click();
    await page.getByText('服务端已记录本次阅读。', { exact: true }).waitFor();
    await capturePage(page, '01-learning-desktop.png');
  });
  await check(
    'in-place classroom, responsive tutor, lesson-scoped activities and portable OpenMAIC download',
    async () => verifyClassroom(page, capturePage),
  );
  await check(
    'wrong answer creates evidence notebook, real hints and an assisted new attempt',
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
      const beforeHints = await settledProgress();
      const hintEvents = [];
      for (const level of [1, 2, 3]) {
        const [response] = await Promise.all([
          page.waitForResponse(
            (response) =>
              response.request().method() === 'POST' &&
              /\/api\/v1\/notebooks\/[^/]+\/actions$/.test(response.url()) &&
              response.request().postDataJSON().action === 'requestHint',
          ),
          page.getByRole('button', { name: `查看第 ${level} 级提示`, exact: true }).click(),
        ]);
        assert.equal(response.status(), 200);
        const receipt = await response.json();
        assert.equal(receipt.status, 'COMPLETED');
        assert.ok(receipt.message.length > 10);
        hintEvents.push(receipt.eventId);
        await page.getByText(receipt.message, { exact: true }).waitFor();
        if (level < 3)
          assert.ok(
            await page
              .getByRole('button', { name: `查看第 ${level + 1} 级提示`, exact: true })
              .isEnabled(),
          );
      }
      assert.equal(new Set(hintEvents).size, 3);
      assert.ok(
        await page.getByRole('button', { name: '已查看全部提示', exact: true }).isDisabled(),
      );
      const eventResponse = await context.request.get(
        `${base}/api/v1/workspaces/${workspaceA}/events?limit=100`,
      );
      assert.equal(eventResponse.status(), 200);
      const events = await eventResponse.json();
      assert.deepEqual(
        events
          .filter((event) => event.type === 'EXERCISE_HINT_REVEALED')
          .map((event) => event.data.hintLevel),
        [1, 2, 3],
      );
      await page.locator('.notebook-block .answer-option input').first().check();
      await page.getByRole('button', { name: '提交复练', exact: true }).click();
      await page.getByRole('heading', { name: /复练反馈/ }).waitFor();
      const response = await context.request.get(
        `${base}/api/v1/workspaces/${workspaceA}/attempts`,
      );
      assert.equal(response.status(), 200);
      const attempts = await response.json();
      assert.ok(attempts.length >= 2);
      assert.ok(attempts.some((a) => a.hintLevel === 4 && a.status === 'GRADED'));
      assert.equal(new Set(attempts.map((a) => a.workspaceId)).size, 1);
      assert.equal(independentSamples(await settledProgress()), independentSamples(beforeHints));
      await page.getByRole('button', { name: '查看关联证据', exact: true }).click();
      await page.locator('.notebook-block .evidence-card').first().waitFor();
      await page.setViewportSize({ width: 390, height: 844 });
      assert.ok(await page.getByRole('button', { name: '提交复练', exact: true }).isVisible());
      const radio = page.locator('.notebook-block .answer-option input[type="radio"]').first();
      const radioBox = await radio.boundingBox();
      assert.ok(radioBox && radioBox.width > 0 && radioBox.width < 32);
      assert.ok(radioBox && radioBox.height > 0 && radioBox.height < 32);
      const answerBox = await page.locator('.notebook-block .answer-option').first().boundingBox();
      assert.ok(answerBox && answerBox.height >= 44);
      assert.ok(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
      );
      await capturePage(page, '02-notebook-mobile.png');
      await page.setViewportSize({ width: 1440, height: 1000 });
    },
  );
  await check(
    'practice reveals three real hints and assisted first attempt cannot increase independent mastery',
    async () => {
      const before = await settledProgress();
      const previous = await (
        await context.request.get(`${base}/api/v1/workspaces/${workspaceA}/attempts`)
      ).json();
      await page.getByRole('link', { name: '练习与反馈', exact: true }).click();
      await page.locator('.exercise-nav').nth(1).click();
      const hints = [];
      for (const level of [1, 2, 3]) {
        const [response] = await Promise.all([
          page.waitForResponse(
            (response) =>
              response.request().method() === 'POST' &&
              /\/api\/v1\/exercises\/[^/]+\/hints$/.test(response.url()),
          ),
          page.getByRole('button', { name: `查看第 ${level} 级提示`, exact: true }).click(),
        ]);
        assert.equal(response.status(), 200);
        const hint = await response.json();
        assert.equal(hint.level, level);
        assert.equal(hint.workspaceId, workspaceA);
        assert.equal(hint.evidenceClass, 'ASSISTED_PRACTICE');
        hints.push(hint);
        await page.getByText(hint.text, { exact: true }).waitFor();
      }
      assert.equal(new Set(hints.map((hint) => hint.id)).size, 3);
      assert.ok(
        await page.getByRole('button', { name: '已查看全部提示', exact: true }).isDisabled(),
      );
      const exerciseId = hints[0].exerciseId;
      assert.ok(!previous.some((attempt) => attempt.exerciseId === exerciseId));
      await page.locator('.question-card .answer-option input').first().check();
      const [response] = await Promise.all([
        page.waitForResponse(
          (response) =>
            response.request().method() === 'POST' && response.url() === `${base}/api/v1/attempts`,
        ),
        page.getByRole('button', { name: '提交作答', exact: true }).click(),
      ]);
      assert.equal(response.status(), 201);
      const attempt = await response.json();
      assert.equal(attempt.exerciseId, exerciseId);
      assert.equal(attempt.status, 'GRADED');
      assert.equal(attempt.hintLevel, 3);
      await page.getByRole('heading', { name: '服务端反馈', exact: true }).waitFor();
      assert.equal(independentSamples(await settledProgress()), independentSamples(before));
      await page.getByRole('link', { name: '作品与错题集', exact: true }).click();
      await page.getByRole('button', { name: '新建作品', exact: true }).waitFor();
    },
  );
  await check(
    'document source saves, previews safely, and downloads actual Markdown bytes',
    async () => {
      await page.getByRole('button', { name: '新建作品', exact: true }).click();
      await page.getByLabel('作品名称', { exact: true }).fill(docTitle);
      await fieldSelect(page, '源稿类型').selectOption('DOCUMENT');
      await page.getByRole('button', { name: '创建源稿', exact: true }).click();
      await page.getByRole('heading', { name: docTitle, exact: true }).waitFor();
      artifactId = decodeURIComponent(new URL(page.url()).hash.split('/')[1]);
      const text =
        '泛型是编译期类型约束。List<Integer> 不是 List<Number> 的子类型。\n<script>window.__unsafePreview=true</script>';
      await page.getByLabel('段落 1 内容', { exact: true }).fill(text);
      await page.getByRole('button', { name: '保存版本', exact: true }).click();
      await page.getByText('服务端已保存版本 1。', { exact: true }).waitFor();
      await page.getByRole('button', { name: '预览', exact: true }).click();
      const preview = page.locator('.document-preview .preserve-text');
      await preview.waitFor();
      assert.equal(await preview.textContent(), text);
      assert.equal(await page.locator('.document-preview script').count(), 0);
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
      await capturePage(page, '03-document-desktop.png');
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
      const initial = await context.request.get(`${base}/api/v1/me/preferences`);
      assert.equal(initial.status(), 200);
      let revision = (await initial.json()).revision;
      const theme = fieldSelect(page, '主题');
      const reducedMotion = page.getByLabel('减少界面动效', { exact: true });
      for (const desired of [
        { theme: 'dark', reducedMotion: true },
        { theme: 'light', reducedMotion: false },
      ]) {
        await theme.selectOption(desired.theme);
        if (desired.reducedMotion) await reducedMotion.check();
        else await reducedMotion.uncheck();
        const [response] = await Promise.all([
          page.waitForResponse(
            (r) => r.url() === `${base}/api/v1/me/preferences` && r.request().method() === 'PUT',
          ),
          page.getByRole('button', { name: '保存显示偏好', exact: true }).click(),
        ]);
        assert.equal(response.status(), 200);
        const saved = await response.json();
        assert.equal(saved.revision, revision + 1);
        assert.equal(saved.theme, desired.theme);
        assert.equal(saved.reducedMotion, desired.reducedMotion);
        await page.getByText('服务端已保存显示偏好。', { exact: true }).waitFor();
        assert.equal(await theme.inputValue(), desired.theme);
        assert.equal(await reducedMotion.isChecked(), desired.reducedMotion);
        assert.deepEqual(
          await page.evaluate(() => ({
            theme: document.documentElement.dataset.theme,
            reducedMotion: document.documentElement.dataset.reducedMotion,
          })),
          { theme: desired.theme, reducedMotion: String(desired.reducedMotion) },
        );
        const persisted = await context.request.get(`${base}/api/v1/me/preferences`);
        assert.equal(persisted.status(), 200);
        assert.deepEqual(await persisted.json(), saved);
        if (desired.theme === 'dark') await capturePage(page, 'review-settings-dark-1440.png');
        revision = saved.revision;
      }
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
    'all enabled learner pages fit desktop and 390/320px layouts with keyboard-complete mobile navigation',
    async () => {
      const routes = [
        ['learn', '.reading-main'],
        ['practice', '.question-card'],
        ['progress', '.analytics-totals'],
        ['lab', '.compiler-layout'],
        ['studio', '.editor-heading'],
        ['tutor', '.conversation-main'],
        ['memory', '.panel'],
        ['agents', '.study-layout'],
        ['settings', '.account-settings'],
      ];
      for (const [route, ready] of routes) {
        await page.setViewportSize({ width: 1440, height: 1000 });
        const link = page.locator(`.sidebar a[href="#${route}"]`);
        if (!(await link.count())) continue;
        await link.click();
        await page.locator(ready).first().waitFor();
        for (const width of [1440, 390, 320]) {
          await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 });
          if (route === 'progress' && width < 760) {
            const evidence = page.getByRole('region', { name: '概念证据表格', exact: true });
            await evidence.waitFor();
            const concept = evidence.locator('tbody tr').first().locator('td').first();
            assert.ok(
              (await concept.boundingBox()).width >= 160,
              `concept evidence text is squeezed at ${width}px`,
            );
            await evidence.focus();
            await page.keyboard.press('ArrowRight');
            await until(
              () => evidence.evaluate((element) => element.scrollLeft > 0),
              `evidence table cannot scroll with the keyboard at ${width}px`,
              5000,
            );
            const practice = evidence
              .getByRole('button', { name: '继续练习', exact: true })
              .first();
            await practice.focus();
            await until(
              async () => {
                const frame = await evidence.boundingBox();
                const button = await practice.boundingBox();
                return (
                  button.x >= frame.x - 1 && button.x + button.width <= frame.x + frame.width + 1
                );
              },
              `evidence action cannot be reached at ${width}px`,
              5000,
            );
            const heading = page.locator('.review-section-heading h2');
            assert.ok(
              await heading.evaluate((element) => {
                const style = getComputedStyle(element);
                return element.getBoundingClientRect().height <= parseFloat(style.lineHeight) * 1.5;
              }),
              `review heading wraps into squeezed characters at ${width}px`,
            );
            await evidence.evaluate((element) => {
              element.scrollLeft = 0;
            });
          }
          assert.ok(
            await page.evaluate(
              () => document.documentElement.scrollWidth <= window.innerWidth + 1,
            ),
            `${route} overflows at ${width}px`,
          );
          await capturePage(page, `review-${route}-${width}.png`);
        }
      }
      await page.getByRole('button', { name: '打开菜单', exact: true }).click();
      const menu = page.getByRole('dialog', { name: '主导航', exact: true });
      await menu.waitFor();
      assert.equal(await page.locator('.main-shell').evaluate((element) => element.inert), true);
      await page.keyboard.press('Shift+Tab');
      assert.equal(
        await menu.evaluate((element) => element.contains(document.activeElement)),
        true,
      );
      await capturePage(page, 'review-mobile-navigation.png');
      await page.keyboard.press('Escape');
      assert.equal(
        await page
          .getByRole('button', { name: '打开菜单', exact: true })
          .evaluate((element) => element === document.activeElement),
        true,
      );
      assert.equal(await page.locator('.main-shell').evaluate((element) => element.inert), false);
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.getByRole('button', { name: '收起导航', exact: true }).click();
      await capturePage(page, 'review-collapsed-navigation.png');
      await page.getByRole('button', { name: '展开导航', exact: true }).click();
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
  await runWorkbenchJourney(workbenchDriver(), check);
  await check(
    'all editorial, learner evaluation and administrator journeys finish with no uncaught browser errors',
    async () => {
      assert.deepEqual(pageErrors, []);
      assert.deepEqual(consoleErrors, []);
      assert.equal(
        await page.evaluate(
          () => Object.keys(sessionStorage).filter((key) => key.startsWith('tf.')).length,
        ),
        0,
      );
    },
  );
} catch (error) {
  if (!failures.length) {
    const name = 'runner failed outside a recorded scenario';
    failures.push(name);
    results.push({ name, status: 'FAIL', error: sanitizeText(error) });
  }
  console.error(sanitizeText(error));
  failurePages = await Promise.all(
    context.pages().map(async (p) => {
      try {
        return await describeControls(p);
      } catch (snapshotError) {
        return { summaryUnavailable: sanitizeText(snapshotError) };
      }
    }),
  );
} finally {
  const report = {
    status: failures.length ? 'FAIL' : 'PASS',
    baseUrl: base,
    at: new Date().toISOString(),
    results,
    failurePages,
    pageErrors,
    consoleErrors,
    visualChecks,
    expectedConflict: {
      url: expectedConflict.url,
      responses: expectedConflict.responses,
      consoleMessages: expectedConflict.consoleMessages,
      expectedResponses: 1,
    },
    notes: [
      'Real demo backend and session/CSRF, no API response fixtures.',
      'Screenshots cover actual learner, editorial and administrator paths; each executed responsive capture checks page overflow, touch target heights and reachable scroll regions.',
      'Reading surfaces are checked for rendered text contrast of at least 4.5:1; content steps must keep their selected tab visible. Dark captures use a saved dark preference or the real saved system preference reacting to browser media changes, never an injected theme attribute.',
      'Editor and administrator identities are temporary isolated test accounts; a test-account review is not a claim of human teaching review.',
      'No model provider was invoked; tutor used EVIDENCE_ONLY.',
    ],
  };
  await writeFile(
    path.join(out, 'browser-report.json'),
    sanitizeText(JSON.stringify(report, null, 2)) + '\n',
  );
  await browser.close();
}
if (failures.length) process.exitCode = 1;
