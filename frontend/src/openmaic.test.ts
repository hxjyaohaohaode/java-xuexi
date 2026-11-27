import { expect, it } from 'vitest';
import { unzipSync, strFromU8 } from 'fflate';
import { validateScene, validateStage } from '@openmaic/dsl';
import { buildMaicArchive, buildMaicLesson } from './openmaic';
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

it('keeps author text verbatim and separates only the seeded edition’s exact import header', () => {
  const header =
    '演示预置学习内容；origin=demo-authored-code-reviewed；未执行双人正式发布审核。目标Java版本17。\n\n';
  const body = '  第一段\n\n' + '完整正文。'.repeat(800) + '\n' + header + '文中引用。  ';
  const seeded = '29f405da-3e5f-318f-a9ac-790603cfa1bb';
  for (const [releaseId, excerpt, display] of [
    [seeded, header + body, body],
    ['custom-release', header + body, header + body],
    [seeded, '前言：' + header + body, '前言：' + header + body],
  ]) {
    const evidence = {
      ...testConcept.evidence[0],
      releaseId,
      excerpt: excerpt.slice(0, 3000),
      fullText: excerpt,
    };
    const topic = { ...testConcept, releaseId, evidence: [evidence] };
    const lesson = buildMaicLesson(topic);
    expect(lesson.transcripts.slice(0, -1).join('')).toBe(display);
    const archive = unzipSync(buildMaicArchive(topic));
    const raw = JSON.parse(strFromU8(archive['typeforge-sources.json'])).sources[0];
    expect(raw.excerpt).toBe(excerpt);
    expect(raw.sha256).toBe(evidence.contentHash);
  }
});

it('renders privately ordered evidence, questions and role actions without serializing workspace references', () => {
  const source = testConcept.evidence[0];
  const activity = {
    key: 'predict',
    kind: 'PREDICT' as const,
    title: '对照你的先前判断',
    body: '为什么这里不能写入？',
    prompt: '给出条件变化前后的两个结果。',
    attemptId: 'private-attempt-reference',
    exerciseId: 'public-exercise',
    exerciseRevision: 1,
    chunkId: source.chunkId,
    chunkRevision: source.revision,
    exampleId: null,
    exampleSha256: null,
  };
  const plan = {
    id: 'private-plan',
    workspaceId: 'private-workspace',
    releaseId: testConcept.releaseId,
    conceptId: testConcept.id,
    conceptCode: testConcept.code,
    title: testConcept.title,
    revision: 4,
    createdAt: '2026-05-12T00:00:00Z',
    snapshotId: 'private-snapshot',
    contextSequence: 42,
    checkpointId: 'private-checkpoint',
    checkpointDigest: 'private-digest',
    contentHash: 'private-content-hash',
    requestedApproach: 'AUTO' as const,
    strategy: 'REPAIR' as const,
    pace: 'GUIDED' as const,
    reasons: ['my secret note'],
    scenes: [activity],
  };
  const personal = buildMaicLesson(testConcept, 1, plan);
  expect(personal.transcripts).toEqual([activity.body]);
  expect(personal.activities[0]?.attemptId).toBe(activity.attemptId);
  const packed = unzipSync(buildMaicArchive(testConcept, 1, plan));
  const bytes = Object.values(packed)
    .map((value) => strFromU8(value))
    .join('');
  for (const privateValue of [
    plan.id,
    plan.snapshotId,
    plan.checkpointDigest,
    plan.workspaceId,
    activity.attemptId,
    plan.reasons[0],
  ])
    expect(bytes).not.toContain(privateValue);
  const manifest = JSON.parse(strFromU8(packed['manifest.json']));
  expect(manifest.scenes[0].actions[0]).toMatchObject({
    type: 'discussion',
    topic: activity.body,
    prompt: activity.prompt,
    agentIndex: 1,
  });
  expect(JSON.stringify(buildMaicLesson(testConcept, 1).scenes)).not.toContain(activity.title);
  const privateScene = {
    ...activity,
    key: 'application-check',
    title: '私人兴趣标题',
    body: '自行定义的队名',
    prompt: '私人要求',
    selfCheck: { options: ['私有选项', '另一项'], answerIndex: 0, explanation: '私有解析', javaSource: '私有源码专用标记' },
    portable: { title: '课程应用', body: '已审核的共用示例', prompt: '哪种写法类型安全？' },
  };
  const adapted = {
    ...plan,
    title: '专属课件名',
    brief: { interest: '私密爱好', requirements: '私密要求' },
    scenes: [privateScene],
  };
  expect(buildMaicLesson(testConcept, 1, adapted).transcripts[0]).toBe(privateScene.body);
  const portable = Object.values(unzipSync(buildMaicArchive(testConcept, 1, adapted)))
    .map((v) => strFromU8(v))
    .join('');
  for (const secret of [
    adapted.title,
    adapted.brief.interest,
    adapted.brief.requirements,
    privateScene.title,
    privateScene.body,
    privateScene.prompt,
    privateScene.selfCheck.options[0],
    privateScene.selfCheck.explanation,
    privateScene.selfCheck.javaSource,
  ])
    expect(portable).not.toContain(secret);
  expect(portable).toContain(privateScene.portable.body);
  expect(() =>
    buildMaicArchive(testConcept, 1, { ...adapted, scenes: [{ ...privateScene, portable: null }] }),
  ).toThrow('缺少可分享');
});
