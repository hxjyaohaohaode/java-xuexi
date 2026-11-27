import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { strFromU8, unzipSync } from 'fflate';
import { validateScene } from '@openmaic/dsl';

// Begins after a real enrolled learner has received a READ_COMPLETED receipt.
export async function verifyClassroom(page, screenshot) {
  const button = (name) => page.getByRole('button', { name, exact: true });
  const stage = page.locator('.classroom-stage');
  const lesson = await stage.locator('.classroom-lesson h2').innerText();
  await page.getByRole('region', { name: '个性化学习路径' }).waitFor();
  await button('课堂导师').click();
  const helper = page.getByRole('complementary', { name: '课堂导师' });
  await helper.getByLabel('输入你的问题').fill('我想检验这条规则的边界。');
  const seam = page.getByRole('separator', { name: '调整导师窗格宽度' });
  await seam.focus();
  await seam.press('End');
  assert.equal(await seam.getAttribute('aria-valuenow'), '48');
  await seam.press('Home');
  assert.equal(await seam.getAttribute('aria-valuenow'), '27');
  await seam.press('ArrowLeft');
  assert.equal(await seam.getAttribute('aria-valuenow'), '29');
  await screenshot(page, 'classroom-tutor-desktop.png');
  for (const width of [320, 390, 768]) {
    await page.setViewportSize({ width, height: 850 });
    assert.ok(await helper.getByLabel('输入你的问题').isVisible());
    assert.ok(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
      '课堂溢出 ' + width,
    );
    if (width === 390) await screenshot(page, 'classroom-tutor-mobile.png');
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await helper.getByRole('button', { name: '关闭课堂导师' }).click();
  await button('课堂导师').click();
  assert.equal(await helper.getByLabel('输入你的问题').inputValue(), '我想检验这条规则的边界。');
  await helper.getByLabel('输入你的问题').fill('');
  await helper.getByRole('button', { name: '关闭课堂导师' }).click();
  await button('练习这一概念').click();
  await stage.locator('.question-card').waitFor();
  assert.equal(await stage.locator('.classroom-lesson h2').innerText(), lesson);
  assert.match(page.url(), /#learn\/[^?]+\?scene=practice/);
  await button('代码实验').click();
  await stage.getByRole('heading', { name: '类型实验', exact: true }).waitFor();
  await page.goBack();
  await stage.locator('.question-card').waitFor();
  await button('本节复盘').click();
  await stage.getByRole('heading', { name: '复习安排', exact: true }).waitFor();
  await button('协作创作').click();
  const goal = stage.getByLabel('你想真正理解什么？');
  assert.ok((await goal.inputValue()).includes(lesson));
  await button('课程阅读').click();
  await stage.locator('summary').filter({ hasText: '导出本节课堂' }).click();
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    button('下载 OpenMAIC 课堂').click(),
  ]);
  assert.match(download.suggestedFilename(), /\.maic\.zip$/);
  const archive = unzipSync(await readFile(await download.path()));
  const manifest = JSON.parse(strFromU8(archive['manifest.json']));
  assert.equal(manifest.stage.name, lesson);
  assert.ok(manifest.scenes.length > 0);
  manifest.scenes.forEach((scene, index) =>
    assert.ok(validateScene({ ...scene, id: 'scene-' + index, stageId: 'imported' }).valid),
  );
  assert.equal(manifest.scenes.at(-1).actions[0].type, 'discussion');
  const [harness] = await Promise.all([
    page.waitForEvent('download'),
    button('导出 Harness 课堂资料').click(),
  ]);
  const packet = JSON.parse(await readFile(await harness.path(), 'utf8'));
  assert.equal(packet.lesson.title, lesson);
  assert.equal(packet.format, 'typeforge.classroom.v1');
  assert.ok(packet.lesson.evidence.length > 0);
  await stage.locator('summary').filter({ hasText: '导出本节课堂' }).click();
}
