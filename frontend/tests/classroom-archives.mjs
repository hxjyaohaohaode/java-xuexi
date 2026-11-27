import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { strFromU8, unzipSync } from 'fflate';
import { verifyMaicPlayback } from './maic-playback.mjs';

// Read the published course available to this authenticated learner; never read private grading rules.
export async function verifyPublishedClassrooms(page, workspaceId) {
  async function read(path) {
    const response = await page.request.get(new URL('/api/v1' + path, page.url()).href);
    assert.equal(response.status(), 200, path);
    return response.json();
  }
  const spaces = await read('/workspaces?limit=100');
  const releaseId = spaces.find((space) => space.id === workspaceId)?.releaseId;
  assert.ok(releaseId, 'The selected workspace must have a pinned release.');
  const { items } = await read('/concepts?releaseId=' + releaseId);
  assert.deepEqual(
    items.map((topic) => topic.code),
    Array.from({ length: 16 }, (_, index) => 'G' + String(index + 1).padStart(2, '0')),
  );

  const stage = page.locator('.classroom-stage');
  const downloads = [];
  const originalUrl = page.url();
  const viewport = page.viewportSize();
  try {
    await page.setViewportSize({ width: 320, height: 850 });
    for (const topic of items) {
      await page.getByRole('button', { name: '课程目录', exact: true }).click();
      await page
        .locator('.classroom-index button')
        .filter({ has: page.getByText(topic.code, { exact: true }) })
        .click();
      await stage
        .getByRole('heading', { name: topic.title, exact: true, level: 2 })
        .first()
        .waitFor();
      const lesson = await stage.locator('.classroom-lesson h2').innerText();
      assert.equal(lesson, topic.title);
      if (topic.code === 'G06') {
        await page.setViewportSize({ width: 1440, height: 1000 });
        await verifyMaicPlayback(page, topic.id);
        await page.setViewportSize({ width: 320, height: 850 });
      }
      const exp = stage.locator('details.classroom-export');
      if (!(await exp.evaluate((element) => element.open))) await exp.locator('summary').click();
      const [download] = await Promise.all([
        page.waitForEvent('download'),
        exp.getByRole('button', { name: '下载 OpenMAIC 课堂', exact: true }).click(),
      ]);
      assert.equal(download.suggestedFilename(), topic.code + '.maic.zip');
      const files = unzipSync(await readFile(await download.path()));
      const manifest = JSON.parse(strFromU8(files['manifest.json']));
      const provenance = JSON.parse(strFromU8(files['typeforge-sources.json']));
      assert.equal(provenance.releaseId, releaseId);
      assert.equal(provenance.conceptId, topic.id);
      assert.equal(manifest.formatVersion, 1);
      assert.equal(manifest.stage.name, lesson);
      assert.ok(topic.evidence.length > 0 && topic.companion?.examples.length > 0, topic.code);
      assert.deepEqual(
        provenance.sources.map((s) => [s.chunkId, s.revision, s.sha256, s.title, s.section]),
        topic.evidence.map((e) => [
          e.chunkId,
          e.revision,
          e.contentHash,
          e.source.title,
          e.sourceSection,
        ]),
      );
      assert.deepEqual(
        provenance.examples,
        topic.companion.examples.map(({ id, sha256, sourceUrl, section }) => ({
          id,
          sha256,
          sourceUrl,
          section,
        })),
      );
      for (const evidence of topic.evidence) {
        const fragments = manifest.scenes
          .filter((scene) =>
            scene.content.canvas.script?.split('\n').includes(evidence.contentHash),
          )
          .map(
            (scene) => scene.content.canvas.elements.find((item) => item.id === 'body')?.content,
          );
        assert.ok(
          fragments.length && fragments.every((html) => typeof html === 'string'),
          topic.code + ' cited lesson pages',
        );
        const actual = await page.evaluate(
          (pages) =>
            pages
              .map((html) => {
                const body = new DOMParser().parseFromString(html, 'text/html').body;
                for (const br of body.querySelectorAll('br')) br.replaceWith('\n');
                return body.textContent;
              })
              .join(''),
          fragments,
        );
        assert.equal(actual, evidence.excerpt, topic.code + ' published evidence text');
      }
      const elements = manifest.scenes.flatMap((scene) => scene.content.canvas.elements);
      const code = elements.filter((element) => element.type === 'code');
      assert.ok(code.every((element) => element.language === 'java'));
      assert.deepEqual(
        code.flatMap((element) => element.lines.map((line) => line.content)),
        topic.companion.examples.flatMap((example) => example.code.trimEnd().split(/\r?\n/)),
        topic.code + ' source text',
      );
      const discussion = manifest.scenes
        .flatMap((scene) => scene.actions || [])
        .filter((action) => action.type === 'discussion');
      assert.ok(discussion.length > 0);
      assert.ok(
        discussion.every(
          (action) => action.topic && manifest.agents[action.agentIndex]?.role === 'student',
        ),
      );
      if (topic.companion.reviews.length)
        assert.deepEqual(
          discussion.map((action) => action.topic),
          topic.companion.reviews.map((review) => review.question),
        );
      assert.ok(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
        topic.code + ' overflows at 320px',
      );
      downloads.push({ topic: topic.code, manifest });
    }
  } finally {
    if (viewport) await page.setViewportSize(viewport);
    if (page.url() !== originalUrl) await page.goto(originalUrl);
  }
  const verifier = fileURLToPath(new URL('./validate-maic-schemas.py', import.meta.url));
  return JSON.parse(
    execFileSync(process.env.PYTHON || 'python3', [verifier], {
      encoding: 'utf8',
      input: JSON.stringify(downloads),
    }),
  );
}
