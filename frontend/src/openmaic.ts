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
import type { Concept, CoursewareScene, PersonalCourseware } from './types';
import { lessonText } from './lessonText';
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

export function buildMaicLesson(concept: Concept, now = Date.now(), personal?: PersonalCourseware) {
  if (
    personal &&
    (personal.conceptId !== concept.id ||
      personal.releaseId !== concept.releaseId ||
      !personal.scenes.length)
  )
    throw Error('个人课件与当前课程版本不匹配。');
  const activities: (CoursewareScene | undefined)[] = [];
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
    excerpt: item.fullText ?? item.excerpt,
  }));
  const examples = concept.companion?.examples || [];
  function add(
    title: string,
    body: string | PPTCodeElement,
    footer: string,
    notes = '',
    question?: string,
    activity?: CoursewareScene,
  ) {
    activities.push(activity);
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
              prompt: activity?.prompt || '请先独立给出预测，并引用课程依据解释推理。',
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
  function codeSlides(
    title: string,
    lines: string[],
    footer: string,
    notes: string,
    activity?: CoursewareScene,
  ) {
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
      add(title + (index ? '（续）' : ''), code, footer, notes, undefined, activity);
    }
  }
  if (personal) {
    for (const activity of personal.scenes) {
      const source = sources.find(
        (s) => s.chunkId === activity.chunkId && s.revision === activity.chunkRevision,
      );
      const example = examples.find(
        (e) => e.id === activity.exampleId && e.sha256 === activity.exampleSha256,
      );
      const footer = example
        ? archiveUrl(example.section, safeSourceUrl(example.sourceUrl))
        : source
          ? archiveUrl(source.title, source.url) + ' · ' + escapeHtml(source.section)
          : classSource;
      const notes = [source?.sha256, example?.sha256].filter(Boolean).join('\n');
      if (activity.kind === 'EXAMPLE' && example) {
        codeSlides(activity.title, activity.body.split(/\r?\n/), footer, notes, activity);
      } else {
        const chars = Array.from(
          activity.kind === 'READ'
            ? lessonText({ releaseId: concept.releaseId, excerpt: activity.body }).body
            : activity.body,
        );
        const discussion = ['PREDICT', 'REFLECT'].includes(activity.kind)
          ? activity.body || activity.prompt || undefined
          : undefined;
        for (let offset = 0; offset < Math.max(1, chars.length); offset += 250) {
          const last = offset + 250 >= chars.length;
          add(
            activity.title + (offset ? '（续）' : ''),
            chars.slice(offset, offset + 250).join(''),
            footer,
            notes,
            last ? discussion : undefined,
            activity,
          );
        }
      }
    }
  } else {
    for (const item of concept.evidence) {
      const source = sources.find((s) => s.chunkId === item.chunkId)!;
      const footer = archiveUrl(source.title, source.url) + ' · ' + escapeHtml(source.section);
      // Keep all published text; a long evidence card continues on additional slides.
      const { body, sourceStatus } = lessonText(item);
      const parts = Array.from(body);
      for (let from = 0; from < Math.max(1, parts.length); from += 250) {
        add(
          item.title + (from ? '（续）' : ''),
          parts.slice(from, from + 250).join(''),
          footer,
          [source.title, source.edition, source.section, source.url, source.sha256, sourceStatus]
            .filter(Boolean)
            .join('\n'),
        );
      }
    }
    for (const example of examples) {
      codeSlides(
        example.title,
        example.code.trimEnd().split(/\r?\n/),
        archiveUrl(example.section, safeSourceUrl(example.sourceUrl)),
        example.sha256,
      );
    }
    const prompts = concept.companion?.reviews.length
      ? concept.companion.reviews.map((r) => r.question)
      : [`关于「${concept.title}」，可以举出一个成立的例子和一个不成立的例子吗？请说明条件。`];
    for (const prompt of prompts) add('课堂讨论', prompt, classSource, '', prompt);
  }
  const stage = {
    name: personal ? personal.title : concept.title,
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
  return { stage, scenes, sources, examples, transcripts, activities };
}

export function buildMaicArchive(
  concept: Concept,
  now = Date.now(),
  personal?: PersonalCourseware,
) {
  // A private interest or instruction may appear in any part of an application scene, including its discussion.
  const shareable = personal && {
    ...personal,
    brief: undefined,
    title: concept.title,
    scenes: personal.scenes.map((scene) => {
      if (scene.portable) return { ...scene, ...scene.portable, selfCheck: undefined, portable: undefined };
      if (scene.key.startsWith('application') || scene.selfCheck)
        throw Error('这页缺少可分享的讲义，无法导出。请更新课件后重试。');
      return scene;
    }),
  };
  const { stage, scenes, sources, examples } = buildMaicLesson(concept, now, shareable);
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

export function downloadMaicArchive(concept: Concept, personal?: PersonalCourseware) {
  const bytes = buildMaicArchive(concept, Date.now(), personal);
  const url = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type: 'application/zip' }));
  const link = document.createElement('a');
  link.href = url;
  link.download =
    concept.code.replace(/[^a-zA-Z0-9_-]/g, '_') +
    (personal ? '-personal-v' + personal.revision : '') +
    '.maic.zip';
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
