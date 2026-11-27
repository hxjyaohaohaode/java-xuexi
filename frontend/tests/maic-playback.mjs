import assert from 'node:assert/strict';

// Enter from the selected published G06 lesson, then return to its reading surface.
export async function verifyMaicPlayback(page, conceptId) {
  const lessonUrl = page.url();
  assert.equal(decodeURIComponent(new URL(lessonUrl).hash.split('/')[1].split('?')[0]), conceptId);
  const button = (name) => page.getByRole('button', { name, exact: true });
  await button('公共课件').click();
  const player = page.getByRole('region', { name: 'OpenMAIC 课堂播放器', exact: true });
  await player.locator('.maic-canvas .slide-renderer-prose').first().waitFor();
  const select = player.getByLabel('选择课件场景');
  const count = await select.locator('option').count();
  assert.ok(count >= 3);
  await player.getByRole('button', { name: '下一页课件' }).click();
  assert.equal(await select.inputValue(), '1');
  await player.getByRole('button', { name: '上一页课件' }).click();
  assert.equal(await select.inputValue(), '0');

  const code = player.locator('.maic-canvas .base-element-code').first();
  for (let index = 1; index < count; index++) {
    await select.selectOption(String(index));
    if (await code.count()) break;
  }
  await code.waitFor();
  await page.setViewportSize({ width: 320, height: 820 });
  await player.getByText('阅读本页代码与出处', { exact: true }).click();
  const transcript = await player.locator('.maic-transcript pre code').innerText();
  assert.ok(transcript.includes('List<'), 'The displayed code must correspond to the G06 example');
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await select.selectOption(String(count - 1));
  const prediction = '我认为这两种泛型列表之间的赋值在编译阶段会被拒绝。';
  await player.getByLabel('我的课堂观点').fill(prediction);
  await player.getByRole('button', { name: '带入课堂导师', exact: true }).click();
  const composer = page.getByLabel('输入你的问题', { exact: true });
  await composer.waitFor({ state: 'visible' });
  await page.waitForFunction(
    (text) => document.getElementById('tutor-question')?.value.includes(text),
    prediction,
  );
  assert.ok((await composer.inputValue()).includes(prediction));
  assert.ok((await composer.inputValue()).includes('List<Integer>'));
  const [response] = await Promise.all([
    page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith('/tutor/requests'),
    ),
    button('发送问题').click(),
  ]);
  assert.equal(response.status(), 202);
  const body = response.request().postDataJSON();
  assert.equal(body.mode, 'EVIDENCE_ONLY');
  assert.ok(body.question.includes('【G06') && body.question.includes(prediction));
  await button('关闭课堂导师').click();
  await button('阅读原文').click();
  await page.locator('.classroom-stage .scene-read .evidence-card').first().waitFor();
  assert.equal(page.url(), lessonUrl);
}
