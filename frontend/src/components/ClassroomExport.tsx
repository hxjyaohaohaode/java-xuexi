import { Download } from 'lucide-react';
import type { Concept } from '../types';

interface LearningStep {
  code: string;
  title: string;
  action: string;
  reason: string;
}

export function ClassroomExport({
  topic,
  path,
}: {
  topic: Concept;
  path?: { steps: LearningStep[] } | null;
}) {
  function download() {
    if (!path) return;
    const packet = {
      format: 'typeforge.classroom.v1',
      exportedAt: new Date().toISOString(),
      releaseId: topic.releaseId,
      lesson: {
        code: topic.code,
        title: topic.title,
        evidence: topic.evidence.slice(0, 4).map((card) => ({
          title: card.title,
          excerpt: card.excerpt.slice(0, 1500),
          source: card.source.title,
          section: card.sourceSection,
          revision: card.revision,
          contentHash: card.contentHash,
        })),
      },
      nextSteps: path.steps
        .slice(0, 3)
        .map(({ code, title, action, reason }) => ({ code, title, action, reason })),
    };
    const file = new Blob([JSON.stringify(packet, null, 2)], {
      type: 'application/json;charset=utf-8',
    });
    const url = URL.createObjectURL(file);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'typeforge-classroom.json';
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return (
    <button
      className="text-button"
      type="button"
      onClick={download}
      disabled={!path}
      title="仅导出本节最多四条课程依据与前三条学习建议，供 DeepSeek Harness 读取"
    >
      <Download size={16} /> 导出 Harness 课堂资料
    </button>
  );
}
