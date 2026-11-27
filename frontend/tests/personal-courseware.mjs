import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { strFromU8, unzipSync } from 'fflate';
import { validateScene } from '@openmaic/dsl';

async function enroll(browser, base, suffix, monitor) {
  const context = await browser.newContext({
    viewport: { width: 1280, height: 900 },
    reducedMotion: 'reduce',
  });
  const page = await context.newPage();
  page.setDefaultTimeout(25000);
  monitor?.(page);
  try {
    await page.goto(base);
    await page.getByRole('button', { name: '注册', exact: true }).click();
    await page
      .getByLabel('用户名', { exact: false })
      .fill('course_' + Date.now().toString(36) + '_' + suffix);
    await page.getByLabel('密码', { exact: true }).fill('Lesson-' + crypto.randomUUID());
    await page.getByRole('button', { name: '注册并进入', exact: true }).click();
    await page.getByLabel('工作空间名称', { exact: true }).fill('独立教学验收 ' + suffix);
    await page.getByRole('button', { name: '创建工作空间', exact: true }).click();
    await page.getByRole('region', { name: '我的课件播放器', exact: true }).waitFor();
    const workspaceId = await page.locator('#workspace-select').inputValue();
    const spaces = await (await context.request.get(base + '/api/v1/workspaces?limit=100')).json();
    const releaseId = spaces.find((space) => space.id === workspaceId).releaseId;
    const { items } = await (
      await context.request.get(base + '/api/v1/concepts?releaseId=' + releaseId)
    ).json();
    const topic = items.find((c) => c.code === 'G01');
    const endpoint = base + '/api/v1/workspaces/' + workspaceId + '/courseware/' + topic.id;
    return {
      context,
      page,
      read: async () => {
        const r = await context.request.get(endpoint);
        assert.equal(r.status(), 200);
        return r.json();
      },
      endpoint,
      topic,
    };
  } catch (e) {
    await context.close();
    throw e;
  }
}

async function exported(page, personal) {
  const details = page.locator(personal ? 'details.personal-export' : 'details.classroom-export');
  if (!(await details.evaluate((el) => el.open))) await details.locator('summary').click();
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    details
      .getByRole('button', {
        name: personal ? '下载我的 OpenMAIC 课件' : '下载 OpenMAIC 课堂',
        exact: true,
      })
      .click(),
  ]);
  assert.match(
    download.suggestedFilename(),
    personal ? /^G01-personal-v\d+\.maic\.zip$/ : /^G01\.maic\.zip$/,
  );
  const files = unzipSync(await readFile(await download.path()));
  const manifest = JSON.parse(strFromU8(files['manifest.json']));
  const sources = JSON.parse(strFromU8(files['typeforge-sources.json']));
  for (const [i, scene] of manifest.scenes.entries())
    assert.ok(validateScene({ ...scene, id: 's' + i, stageId: 'lesson' }).valid);
  return { manifest, sources };
}
const pickScene = async (page, title) => {
  const select = page.getByRole('region', { name: '我的课件播放器' }).getByLabel('选择课件场景');
  const options = await select.locator('option').allTextContents();
  const idx = options.findIndex((label) => label.endsWith('. ' + title));
  assert.ok(idx >= 0, title);
  await select.selectOption(String(idx));
};
const titles = (plan) => plan.scenes.map((s) => s.title);

export async function verifyPersonalCourseware(browser, base, monitor) {
  const a = await enroll(browser, base, 'a', monitor);
  let b;
  try {
    b = await enroll(browser, base, 'b', monitor);
    const baseline = await a.read();
    const same = await b.read();
    assert.equal(baseline.conceptId, same.conceptId);
    assert.equal(baseline.strategy, 'FOUNDATION');
    assert.deepEqual(titles(baseline), titles(same));
    const marker = '课堂隐私校验词' + Date.now();
    await b.page.locator('.sidebar a[href="#memory"]').click();
    await b.page
      .getByLabel('你希望工作空间记住什么？')
      .fill('学习泛型时我希望先看代码，再自己判断。' + marker);
    await b.page.getByRole('button', { name: '保存到工作空间', exact: true }).click();
    await b.page.getByText('笔记已保存，可在后续上下文中使用。', { exact: true }).waitFor();
    await b.page.locator('.sidebar a[href="#learn"]').click();
    const [updated] = await Promise.all([
      b.page.waitForResponse((r) => r.url() === b.endpoint && r.request().method() === 'POST'),
      b.page.getByRole('button', { name: '按最新学习记录更新课件', exact: true }).click(),
    ]);
    assert.equal(updated.status(), 201);
    const example = await updated.json();
    assert.equal(example.requestedApproach, 'AUTO');
    assert.equal(example.strategy, 'EXAMPLE_FIRST');
    assert.equal(example.scenes[0].kind, 'EXAMPLE');
    assert.ok(example.revision > same.revision);
    assert.notDeepEqual(titles(example), titles(baseline));
    await b.page
      .getByRole('region', { name: '我的课件播放器' })
      .locator('.base-element-code')
      .waitFor();
    const publicA = await exported(a.page, false),
      publicB = await exported(b.page, false);
    assert.deepEqual(publicA.manifest.scenes, publicB.manifest.scenes);
    assert.deepEqual(publicA.sources, publicB.sources);
    const personalA = await exported(a.page, true),
      personalB = await exported(b.page, true);
    assert.notDeepEqual(personalA.manifest.scenes, personalB.manifest.scenes);
    await pickScene(b.page, example.scenes.find((s) => s.kind === 'PRACTICE').title);
    await b.page.getByRole('button', { name: '作答这道练习', exact: true }).click();
    const intended = example.scenes.find((s) => s.kind === 'PRACTICE');
    assert.ok(b.page.url().includes(intended.exerciseId));
    const wrong = b.page.getByLabel(/自动调用 toString\(\) 后加入|String 是否继承 Object/);
    await wrong.check();
    const [receipt] = await Promise.all([
      b.page.waitForResponse(
        (r) => r.url() === base + '/api/v1/attempts' && r.request().method() === 'POST',
      ),
      b.page.getByRole('button', { name: '提交作答', exact: true }).click(),
    ]);
    const answer = await receipt.json();
    assert.equal(answer.exerciseId, intended.exerciseId);
    assert.equal(answer.score, 0);
    const [repairResponse] = await Promise.all([
      b.page.waitForResponse((r) => r.url() === b.endpoint && r.request().method() === 'POST'),
      b.page.getByRole('button', { name: '依据这次反馈更新课件', exact: true }).click(),
    ]);
    const repair = await repairResponse.json();
    assert.equal(repair.strategy, 'REPAIR');
    assert.ok(repair.revision > example.revision);
    assert.equal(repair.scenes[0].attemptId, answer.id);
    assert.equal(repair.scenes[0].exerciseId, answer.exerciseId);
    await b.page.getByRole('button', { name: '查看这次作答反馈', exact: true }).click();
    assert.equal(await b.page.locator('.personal-feedback p').innerText(), answer.feedback);
    const privateRepair = await exported(b.page, true);
    const publicAfter = await exported(b.page, false);
    assert.deepEqual(publicAfter.manifest.scenes, publicA.manifest.scenes);
    for (const packet of [publicAfter, privateRepair]) {
      const bytes = JSON.stringify(packet);
      for (const secret of [
        marker,
        answer.id,
        repair.workspaceId,
        repair.id,
        repair.snapshotId,
        repair.checkpointId,
        repair.checkpointDigest,
      ])
        assert.ok(!bytes.includes(secret), 'private workspace reference leaked through export');
    }
    const inaccessible = await a.context.request.get(b.endpoint + '?planId=' + repair.id);
    assert.equal(inaccessible.status(), 404);
    assert.equal((await a.read()).id, baseline.id);
    await b.page.reload();
    await b.page.getByRole('region', { name: '我的课件播放器' }).waitFor();
    assert.equal((await b.read()).id, repair.id);
    await b.page.setViewportSize({ width: 320, height: 844 });
    assert.ok(await b.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await b.page.setViewportSize({ width: 1280, height: 900 });
    const area = b.page.getByRole('region', { name: '个人讲解课件' });
    await area.getByLabel('生成方式').selectOption('EVIDENCE_ONLY');
    const [created] = await Promise.all([
      b.page.waitForResponse(
        (r) => r.url() === b.endpoint + '/study' && r.request().method() === 'POST',
      ),
      area.getByRole('button', { name: '生成讲解课件', exact: true }).click(),
    ]);
    assert.ok(created.ok());
    const run = await created.json();
    const output = area.getByRole('region', { name: '已生成讲解幻灯片' });
    await output.waitFor({ timeout: 90000 });
    await output.getByRole('button', { name: '下一张讲解' }).click();
    const beforeReload = await output.locator('.slide-canvas h3').innerText();
    assert.ok(beforeReload.length > 0);
    await b.page.reload();
    await output.waitFor({ timeout: 30000 });
    assert.ok(await output.locator('.slide-canvas h3').innerText());
    const recovered = await b.context.request.get(
      b.endpoint + '/study?planId=' + repair.id + '&mode=EVIDENCE_ONLY',
    );
    assert.equal((await recovered.json()).id, run.id);
    return {
      concept: baseline.conceptCode,
      ordinary: baseline.strategy,
      preference: example.strategy,
      feedback: repair.strategy,
      revisions: [same.revision, example.revision, repair.revision],
      readableFeedback: true,
      publicExportIsolated: true,
      privateExportAuthorized: true,
      studyOutput: 'SLIDES',
    };
  } finally {
    await a.context.close();
    if (b) await b.context.close();
  }
}
