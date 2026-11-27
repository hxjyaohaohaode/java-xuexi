import { useResource } from '../hooks';
import type { Concept, PersonalCourseware } from '../types';
import { CompilePanel } from './Compiler';
import { ErrorNotice, Loading } from './ui';

export type ExperimentLink = { planId: string; conceptId: string; sceneKey: string };

export function CoursewareExperiment({
  link,
  workspaceId,
  concept,
  onDiscuss,
  onReturn,
  onOpenLab,
}: {
  link: ExperimentLink;
  workspaceId: string;
  concept: Concept;
  onDiscuss?: (text: string) => void;
  onReturn: () => void;
  onOpenLab: () => void;
}) {
  const plan = useResource<PersonalCourseware>(
    '/workspaces/' +
      workspaceId +
      '/courseware/' +
      concept.id +
      '?planId=' +
      encodeURIComponent(link.planId),
  );
  const admitted =
    plan.data?.workspaceId === workspaceId &&
    plan.data.releaseId === concept.releaseId &&
    plan.data.conceptId === concept.id &&
    plan.data.id === link.planId;
  const scene = admitted ? plan.data?.scenes.find((s) => s.key === link.sceneKey) : undefined;
  const code = scene?.selfCheck?.javaSource;
  return (
    <section aria-label="本题代码实验" className="lesson-experiment">
      <div className="row wrap">
        <button onClick={onReturn}>返回这道判断题</button>
        <button onClick={onOpenLab}>打开本节独立草稿</button>
      </div>
      {plan.error ? (
        <ErrorNotice error={plan.error} retry={plan.reload} />
      ) : !plan.data ? (
        <Loading label="正在读取这道判断题的 Java 源码…" />
      ) : scene && code ? (
        <CompilePanel
          key={link.planId + scene.key}
          initialConceptCode={concept.code}
          initialLessonExample={{ id: link.planId + '.' + scene.key, title: scene.title, code }}
          onDiscuss={onDiscuss}
        />
      ) : (
        <p role="status">当前课件无法提供这道判断题的源码。</p>
      )}
    </section>
  );
}
