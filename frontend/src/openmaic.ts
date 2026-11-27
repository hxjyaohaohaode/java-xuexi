import {
  validateScene,
  validateStage,
  type Scene,
  type Slide,
  type PPTTextElement,
  type DiscussionAction,
  type PPTCodeElement,
} from '@openmaic/dsl';
import { strToU8, zipSync } from 'fflate';
import type { Concept } from './types';
import { safeSourceUrl } from './api';

// OpenMAIC v1 classroom manifest. Upstream assigns new stage, scene and agent IDs on import.
type PortableScene = {
  type: 'slide';
  title: string;
  order: number;
  content: { type: 'slide'; canvas: Slide };
  actions?: (DiscussionAction & { agentIndex: number })[];
  multiAgent?: { enabled: true; agentIndices: number[]; directorPrompt: string };
};

function escapeHtml(value: string) {
  return value.replace(
    /[&<>"']/g,
    (character) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]!,
  );
}
function textBox(
  id: string,
  content: string,
  top: number,
  height: number,
  fontSize: number,
  color: string,
): PPTTextElement {
  return {
    id,
    type: 'text',
    left: 48,
    top,
    width: 904,
    height,
    rotate: 0,
    defaultFontName: 'sans-serif',
    defaultColor: color,
    content: `<p style="font-size:${fontSize}px;color:${color}">${content}</p>`,
  };
}

export function buildMaicLesson(concept: Concept, now = Date.now()) {
  const transcripts: string[] = [];
  const scenes: PortableScene[] = [];
  const sources = concept.evidence.map((item) => ({
    title: item.source.title,
    url: safeSourceUrl(item.source.url),
    edition: item.source.edition,
    section: item.sourceSection,
    chunkId: item.chunkId,
    revision: item.revision,
    sha256: item.contentHash,
  }));
  const examples = concept.companion?.examples || [];
  function add(
    title: string,
    body: string | PPTCodeElement,
    footer: string,
    notes = '',
    question?: string,
  ) {
    const order = scenes.length;
    transcripts.push(
      typeof body === 'string' ? body : body.lines.map((line) => line.content).join('\n'),
    );
    const canvas: Slide = {
      id: 'canvas-' + order,
      viewportSize: 1000,
      viewportRatio: 0.5625,
      theme: {
        backgroundColor: '#ffffff',
        themeColors: ['#147367'],
        fontColor: '#20322e',
        fontName: 'sans-serif',
      },
      background: { type: 'solid', color: '#ffffff' },
      elements: [
        textBox('title', escapeHtml(title), 30, 78, 30, '#20322e'),
        typeof body === 'string'
          ? textBox('body', escapeHtml(body).replace(/\n/g, '<br>'), 118, 353, 23, '#20322e')
          : body,
        textBox('source', footer, 498, 54, 14, '#475c53'),
      ],
      script: notes,
    };
    const discussion = question
      ? {
          actions: [
            {
              id: 'discuss-' + order,
              type: 'discussion' as const,
              topic: question,
              prompt: '请先独立给出预测，并引用课程依据解释推理。',
              agentIndex: 1,
            },
          ],
          multiAgent: {
            enabled: true as const,
            agentIndices: [0, 1],
            directorPrompt: '请让学习者先发言，再比较两种解释与本节证据。',
          },
        }
      : {};
    scenes.push({ type: 'slide', title, order, content: { type: 'slide', canvas }, ...discussion });
  }
  const archiveUrl = (title: string, url?: string) =>
    url ? `<a href="${escapeHtml(url)}">${escapeHtml(title)}</a>` : escapeHtml(title);
  const classSource = escapeHtml(concept.code + ' · ' + concept.title);
  for (const item of concept.evidence) {
    const source = sources.find((s) => s.chunkId === item.chunkId)!;
    const footer = archiveUrl(source.title, source.url) + ' · ' + escapeHtml(source.section);
    // Keep all published text; a long evidence card continues on additional slides.
    const parts = Array.from(item.excerpt);
    for (let from = 0; from < Math.max(1, parts.length); from += 250) {
      add(
        item.title + (from ? '（续）' : ''),
        parts.slice(from, from + 250).join(''),
        footer,
        [source.title, source.edition, source.section, source.url, source.sha256]
          .filter(Boolean)
          .join('\n'),
      );
    }
  }
  for (const example of examples) {
    const lines = example.code.trimEnd().split(/\r?\n/);
    for (let index = 0; index < lines.length; index += 13) {
      const code: PPTCodeElement = {
        id: 'example',
        type: 'code',
        left: 48,
        top: 111,
        width: 904,
        height: 361,
        rotate: 0,
        language: 'java',
        fontSize: 18,
        showLineNumbers: true,
        lines: lines
          .slice(index, index + 13)
          .map((content, n) => ({ id: 'L' + (index + n + 1), content })),
      };
      add(
        example.title + (index ? '（续）' : ''),
        code,
        archiveUrl(example.section, safeSourceUrl(example.sourceUrl)),
        example.sha256,
      );
    }
  }
  const prompts = concept.companion?.reviews.length
    ? concept.companion.reviews.map((r) => r.question)
    : [`关于「${concept.title}」，可以举出一个成立的例子和一个不成立的例子吗？请说明条件。`];
  for (const prompt of prompts) add('课堂讨论', prompt, classSource, '', prompt);
  const stage = {
    name: concept.title,
    description: concept.code + ' · Java 泛型与类型安全',
    language: 'zh-CN',
    createdAt: now,
    updatedAt: now,
  };
  if (!validateStage({ ...stage, id: concept.id }).valid)
    throw Error('OpenMAIC 课程信息校验失败。');
  for (const scene of scenes) {
    const runtime: Scene = {
      ...scene,
      id: 'scene-' + scene.order,
      stageId: concept.id,
      multiAgent: scene.multiAgent && {
        enabled: true,
        agentIds: ['teacher', 'student'],
        directorPrompt: scene.multiAgent.directorPrompt,
      },
    };
    if (!validateScene(runtime).valid) throw Error('OpenMAIC 场景校验失败：' + scene.title);
  }
  return { stage, scenes, sources, examples, transcripts };
}

export function buildMaicArchive(concept: Concept, now = Date.now()) {
  const { stage, scenes, sources, examples } = buildMaicLesson(concept, now);
  const manifest = {
    formatVersion: 1,
    exportedAt: new Date(now).toISOString(),
    appVersion: 'TypeForge',
    stage,
    agents: [
      {
        name: '课程导师',
        role: 'teacher',
        persona: '引导学生引用当前课程的具体证据。',
        avatar: '/avatars/instructor.png',
        color: '#147367',
        priority: 1,
      },
      {
        name: '讨论同学',
        role: 'student',
        persona: '提出反例，说明仍不确定的问题。',
        avatar: '/avatars/student1.svg',
        color: '#465f85',
        priority: 2,
      },
    ],
    scenes,
    mediaIndex: {},
  };
  const provenance = {
    conceptId: concept.id,
    releaseId: concept.releaseId,
    sources,
    examples: examples.map(({ id, sha256, sourceUrl, section }) => ({
      id,
      sha256,
      sourceUrl,
      section,
    })),
  };
  return zipSync({
    'manifest.json': new Uint8Array(strToU8(JSON.stringify(manifest))),
    'typeforge-sources.json': new Uint8Array(strToU8(JSON.stringify(provenance))),
  });
}
