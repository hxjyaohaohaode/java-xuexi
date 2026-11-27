import { expect, it } from 'vitest';
import { unzipSync, strFromU8 } from 'fflate';
import { validateScene, validateStage } from '@openmaic/dsl';
import { buildMaicArchive } from './openmaic';
import { testConcept } from './test/workspace';

it('exports a portable OpenMAIC v1 manifest with complete typed code, source hashes and a remappable discussion roster', () => {
  const code = Array.from({ length: 17 }, (_, i) => '  // 行 ' + (i + 1)).join('\n');
  const title = 'List<T> 与 List<?> 的边界';
  const topic = {
    ...testConcept,
    title,
    companion: {
      version: '1',
      reviews: [
        {
          id: 'r',
          conceptId: testConcept.id,
          question: '这个推断如何检验？',
          criterion: '能够解释',
          correction: null,
        },
      ],
      examples: [
        {
          id: 'ex',
          title,
          conceptIds: [testConcept.id],
          sha256: 'c'.repeat(64),
          section: '课程样例',
          sourceUrl: 'https://example.org/sample',
          compiles: true,
          stdout: null,
          warning: null,
          code,
        },
      ],
    },
  };
  const files = unzipSync(buildMaicArchive(topic, 1700000000000));
  const manifest = JSON.parse(strFromU8(files['manifest.json']));
  const source = JSON.parse(strFromU8(files['typeforge-sources.json']));
  expect(manifest.formatVersion).toBe(1);
  expect(validateStage({ ...manifest.stage, id: 'new-stage' }).valid).toBe(true);
  expect(source.sources[0].sha256).toBe(testConcept.evidence[0].contentHash);
  expect(source.examples[0].sha256).toBe(topic.companion.examples[0].sha256);
  const lines: string[] = [];
  type Imported = {
    order: number;
    content: {
      canvas: { elements: { type: string; content?: string; lines?: { content: string }[] }[] };
    };
  };
  manifest.scenes.forEach((scene: Imported) => {
    expect(
      validateScene({ ...scene, stageId: 'new-stage', id: 'scene-' + scene.order }).valid,
    ).toBe(true);
    scene.content.canvas.elements.forEach((element) => {
      if (element.type === 'code') lines.push(...element.lines!.map((line) => line.content));
      if (element.type === 'text') expect(element.content).not.toContain('List<T>');
    });
  });
  expect(lines.join('\n')).toBe(code);
  const discussion = manifest.scenes.at(-1);
  expect(discussion.actions[0].topic).toBe('这个推断如何检验？');
  expect(manifest.agents[discussion.actions[0].agentIndex].role).toBe('student');
  expect(discussion.multiAgent.agentIndices.map((i: number) => manifest.agents[i].role)).toEqual([
    'teacher',
    'student',
  ]);
  expect(manifest.mediaIndex).toEqual({});
});
