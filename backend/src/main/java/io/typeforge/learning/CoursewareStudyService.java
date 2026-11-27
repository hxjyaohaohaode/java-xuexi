package io.typeforge.learning;

import io.typeforge.orchestration.DurableAgentRuntime;
import io.typeforge.orchestration.TaskPlan;
import io.typeforge.shared.JsonCodec;
import io.typeforge.tutor.StudyWorkflowService;
import io.typeforge.tutor.TutorService;
import io.typeforge.workspace.LearningContextJournal;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoursewareStudyService {
    private final PersonalCourseware lessons;
    private final WorkspaceService workspaces;
    private final LearningContextJournal journal;
    private final StudyWorkflowService study;
    private final DurableAgentRuntime runtime;
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final Clock clock;

    public CoursewareStudyService(PersonalCourseware lessons,WorkspaceService workspaces,LearningContextJournal journal,
            StudyWorkflowService study,DurableAgentRuntime runtime,JdbcTemplate jdbc,JsonCodec json,Clock clock) {
        this.lessons=lessons; this.workspaces=workspaces; this.journal=journal; this.study=study;
        this.runtime=runtime; this.jdbc=jdbc; this.json=json; this.clock=clock;
    }

    @Transactional
    public DurableAgentRuntime.RunView start(UUID owner,UUID workspace,UUID concept,UUID planId,TutorService.Mode mode,String key) {
        WorkspaceService.requireKey(key);
        workspaces.lockOwned(owner,workspace);
        var plan=lessons.get(owner,workspace,concept,planId);
        String hash=json.hash(List.of(workspace,concept,planId,mode));
        UUID replay=workspaces.replay(owner,"courseware.study",key,hash);
        if (replay!=null) return runtime.get(owner,workspace,replay);
        var prior=latest(owner,workspace,planId,mode);
        var run=prior.filter(r->!Set.of("FAILED","CANCELLED","PARTIAL").contains(r.status()) || hasUnsettledTools(r.id())).orElseGet(()->{
            var memory=journal.read(owner,workspace,plan.checkpointId());
            var admitted=memory.facts().stream().map(f->new Reference(f.kind(),f.sourceId(),f.revision(),f.contentHash())).toList();
            var context=workspaces.createTeachingContext(owner,workspace,plan.snapshotId(),admitted,teachingBrief(plan),
                List.of("按本节个人分镜顺序授课，每一页给出对应的知识依据。","保留预测、练习和回顾的学生任务。"));
            String runKey="courseware:"+UUID.nameUUIDFromBytes((owner+"/"+key).getBytes(StandardCharsets.UTF_8));
            var created=study.create(owner,workspace,new StudyWorkflowService.Input(context.goalId(),context.snapshot().id(),
                List.of("SLIDES"),new TaskPlan.Budget(100000,480000,1,1),mode),runKey);
            jdbc.update("INSERT INTO courseware_study_run(workspace_id,plan_id,run_id,mode_key,created_at) VALUES(?,?,?,?,?)",
                workspace.toString(),planId.toString(),created.id().toString(),mode.name(),Timestamp.from(clock.instant()));
            return created;
        });
        workspaces.remember(owner,"courseware.study",key,hash,run.id());
        return run;
    }

    private String teachingBrief(PersonalCourseware.Plan plan) {
        var summary=new StringBuilder(plan.title()).append("。");
        if (plan.brief()!=null) {
            summary.append("\n学生亲自选择的教学素材，仅用于选择讲解情境、深度和任务：")
                .append(json.write(plan.brief())).append("\n");
        }
        for (var scene:plan.scenes()) if (scene.selfCheck()!=null) {
            var check=scene.selfCheck();
            // Keep the real proposed line and its reviewed rationale together, before the outline.
            summary.append(scene.title()).append("：").append(scene.body())
                    .append("\n自主练习的核对依据：").append(check.options().get(check.answerIndex()))
                    .append("。").append(check.explanation()).append("\n");
        }
        summary.append("分镜顺序：").append(String.join(" → ",plan.scenes().stream().map(PersonalCourseware.Scene::title).toList())).append("。");
        for (var scene:plan.scenes()) if (scene.selfCheck()==null && Set.of("EXAMPLE","PREDICT","PRACTICE").contains(scene.kind())) {
            summary.append("\n").append(scene.title()).append("：").append(scene.body().codePoints().limit(150)
                .collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append));
            if (scene.prompt()!=null) summary.append("。").append(scene.prompt());
        }
        if (summary.length()<=4000) return summary.toString();
        int end=3999;
        if (Character.isHighSurrogate(summary.charAt(end-1))) end--;
        return summary.substring(0,end)+"…";
    }

    @Transactional
    public Optional<DurableAgentRuntime.RunView> resume(UUID owner,UUID workspace,UUID concept,UUID planId,TutorService.Mode mode) {
        lessons.get(owner,workspace,concept,planId);
        return latest(owner,workspace,planId,mode);
    }

    private boolean hasUnsettledTools(UUID run) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM tool_execution t JOIN agent_step s ON s.id=t.step_id WHERE s.run_id=?
            AND t.status IN ('ADMITTED','RUNNING','UNKNOWN')
            """,Integer.class,run.toString())>0;
    }

    private Optional<DurableAgentRuntime.RunView> latest(UUID owner,UUID workspace,UUID planId,TutorService.Mode mode) {
        return jdbc.queryForList("""
            SELECT run_id FROM courseware_study_run WHERE workspace_id=? AND plan_id=? AND mode_key=?
            ORDER BY created_at DESC,run_id DESC LIMIT 1
            """,String.class,workspace.toString(),planId.toString(),mode.name()).stream().findFirst()
            .map(id->runtime.get(owner,workspace,UUID.fromString(id)));
    }
}
