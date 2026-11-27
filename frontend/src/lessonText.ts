import type { Evidence } from './types';

// Immutable editions created by DemoContentInitializer. An authored course may quote the same line.
const demoReleases = new Set([
  '91e77c9b-ae66-3a2b-aa47-520f4dd8647c',
  '29f405da-3e5f-318f-a9ac-790603cfa1bb',
]);
const importPrefix =
  '演示预置学习内容；origin=demo-authored-code-reviewed；未执行双人正式发布审核。目标Java版本17。\n\n';

export function lessonText(evidence: Pick<Evidence, 'excerpt' | 'fullText' | 'releaseId'>) {
  const original = evidence.fullText ?? evidence.excerpt;
  const seeded = demoReleases.has(evidence.releaseId) && original.startsWith(importPrefix);
  return {
    body: seeded ? original.slice(importPrefix.length) : original,
    sourceStatus: seeded ? '预置演示课程，尚未经过双人发布审核。适用 Java 17。' : null,
  };
}
