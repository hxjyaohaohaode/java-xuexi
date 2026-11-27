package io.typeforge.learning;

import io.typeforge.curriculum.CurriculumService;
import io.typeforge.operations.RuntimeCapabilityProvider;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceAccess;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A read-only route derived from published course content and the current workspace's learning facts. */
@Service
public class PersonalLearningPath implements RuntimeCapabilityProvider {
    public record Step(UUID conceptId, String code, String title, String action, String reason, UUID attemptId,
                       UUID exerciseId, boolean readCompleted, long gradedAttempts, long independentCorrectFamilies) {}
    public record Path(UUID workspaceId, UUID releaseId, List<Step> steps, String strategyVersion) {}
    private record Topic(UUID id, String code, String title, List<UUID> prerequisites) {}
    private record Last(UUID id, boolean passed, boolean assisted, long count, String reviewStatus) {}
    private record Ranked(Step step, int priority, boolean target) {}

    private final JdbcTemplate jdbc;
    private final WorkspaceAccess workspaces;
    private final CurriculumService curriculum;
    private final JsonCodec json;

    public PersonalLearningPath(JdbcTemplate jdbc, WorkspaceAccess workspaces, CurriculumService curriculum, JsonCodec json) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.curriculum = curriculum;
        this.json = json;
    }

    @Override public Set<String> enabledCapabilities() { return Set.of("LEARNING_PATH"); }

    @Transactional(readOnly = true)
    public Path get(UUID owner, UUID workspace) {
        UUID release = workspaces.requireOwned(owner, workspace).releaseId();
        curriculum.requirePublished(release);
        String scope = workspace.toString();
        List<Topic> topics = jdbc.query("""
            SELECT c.id, c.code, rc.title, rc.prerequisite_ids FROM release_concept rc
            JOIN concept c ON c.id=rc.concept_id WHERE rc.release_id=? ORDER BY c.code
            """, (rs, n) -> new Topic(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                List.of(json.read(json.write(json.tree(rs.getObject(4))), UUID[].class))), release.toString());
        Set<String> read = new HashSet<>(jdbc.queryForList("""
            SELECT DISTINCT concept_id FROM workspace_reading WHERE workspace_id=? AND event_type='READ_COMPLETED'
            """, String.class, scope));
        List<String> goals = jdbc.queryForList("""
            SELECT goal_text FROM learning_goal WHERE workspace_id=? AND status='ACTIVE' ORDER BY created_at DESC,id LIMIT 20
            """, String.class, scope);
        Map<UUID,Last> recent = new HashMap<>();
        jdbc.query("""
            SELECT concept_id,id,score,maximum,hint_level,graded_count,review_status FROM (
              SELECT ec.concept_id,a.id,a.score,a.maximum,a.hint_level,
                ROW_NUMBER() OVER (PARTITION BY ec.concept_id ORDER BY a.created_at DESC,a.id DESC) AS position_id,
                COUNT(*) OVER (PARTITION BY ec.concept_id) AS graded_count,r.status AS review_status
              FROM workspace_attempt wa JOIN exercise_attempt a ON a.id=wa.attempt_id
              JOIN exercise_concept ec ON ec.exercise_id=a.exercise_id AND ec.exercise_revision=a.exercise_revision
              LEFT JOIN review_item r ON r.user_id=a.user_id AND r.origin_attempt_id=a.id AND r.concept_id=ec.concept_id
              WHERE wa.workspace_id=? AND a.user_id=? AND a.release_id=? AND a.status='GRADED'
            ) attempts WHERE position_id=1
            """, rs -> { recent.put(UUID.fromString(rs.getString(1)), new Last(UUID.fromString(rs.getString(2)),
                rs.getBigDecimal(3).compareTo(rs.getBigDecimal(4)) == 0, rs.getInt(5) > 0, rs.getLong(6),rs.getString(7))); },
            scope, owner.toString(), release.toString());
        Map<UUID,Long> independent = new HashMap<>();
        jdbc.query("""
            SELECT ev.concept_id, COUNT(*) FROM learning_family_evidence ev
            JOIN workspace_attempt wa ON wa.attempt_id=ev.origin_attempt_id
            WHERE ev.user_id=? AND wa.workspace_id=? AND ev.correct_weight=ev.total_weight GROUP BY ev.concept_id
            """, rs -> { independent.put(UUID.fromString(rs.getString(1)),rs.getLong(2)); },owner.toString(),scope);
        Map<UUID,UUID> pending = new HashMap<>();
        jdbc.query("""
            SELECT r.concept_id,r.origin_attempt_id FROM review_item r
            JOIN workspace_attempt wa ON wa.attempt_id=r.origin_attempt_id
            WHERE wa.workspace_id=? AND r.user_id=? AND r.status='PENDING' ORDER BY r.due_at,r.id
            """, rs -> { pending.putIfAbsent(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2))); },
            scope, owner.toString());
        Map<UUID,UUID> nextExercise = new HashMap<>();
        jdbc.query("""
            SELECT ec.concept_id,e.id FROM release_exercise re
            JOIN exercise e ON e.id=re.exercise_id AND e.revision=re.exercise_revision
            JOIN exercise_concept ec ON ec.exercise_id=e.id AND ec.exercise_revision=e.revision
            LEFT JOIN (SELECT DISTINCT ex.family_id FROM workspace_attempt wa
              JOIN exercise_attempt a ON a.id=wa.attempt_id
              JOIN exercise ex ON ex.id=a.exercise_id AND ex.revision=a.exercise_revision
              WHERE wa.workspace_id=? AND a.status='GRADED') seen ON seen.family_id=e.family_id
            WHERE re.release_id=? AND e.status='REVIEWED'
            ORDER BY CASE WHEN seen.family_id IS NULL THEN 0 ELSE 1 END,e.difficulty,e.id
            """, rs -> { nextExercise.putIfAbsent(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2))); },
            scope, release.toString());

        Set<UUID> targets = new HashSet<>();
        for (Topic topic : topics) {
            if (goals.stream().anyMatch(goal -> matches(goal, topic))) targets.add(topic.id());
        }
        // Prioritize the full prerequisite chain when a learner names an advanced topic.
        Map<UUID,Topic> byId = new HashMap<>();
        topics.forEach(topic -> byId.put(topic.id(), topic));
        ArrayDeque<UUID> queue = new ArrayDeque<>(targets);
        while (!queue.isEmpty()) {
            Topic topic = byId.get(queue.remove());
            if (topic != null) for (UUID parent : topic.prerequisites()) if (targets.add(parent)) queue.add(parent);
        }
        List<Ranked> ordered = new ArrayList<>();
        for (Topic topic : topics) {
            UUID id = topic.id();
            Last last = recent.get(id);
            boolean completed = read.contains(id.toString());
            long samples = independent.getOrDefault(id, 0L);
            UUID exercise = nextExercise.get(id);
            UUID origin = pending.get(id);
            boolean ready = topic.prerequisites().stream().allMatch(parent ->
                read.contains(parent.toString()) || independent.getOrDefault(parent, 0L) > 0);
            String action, reason;
            int priority;
            if (exercise != null && (origin != null || last != null && last.reviewStatus()==null && (!last.passed() || last.assisted()))) {
                action = "REPAIR";
                if (origin == null) origin = last.id();
                reason = pending.containsKey(id) ? "这次作答留下了待复习记录。先核对原因，再做同概念练习。" :
                    last.passed() ? "上一次借助提示完成。再独立练一次，检查自己能否解释原因。" :
                    "最近一次作答仍有错误。回看规则，再尝试同概念练习。";
                priority = 0;
            } else if (exercise != null && completed && last == null) {
                action = "PRACTICE"; reason = "已读完这一节，还没有作答记录。用一道题验证自己的判断。"; priority = 1;
            } else if (last == null && !completed) {
                action = "READ"; priority = ready ? 2 : 4;
                reason = !ready ? "前置概念还有未完成的阅读，课程目录中可以打开对应章节。" :
                    targets.contains(id) ? "这一节属于当前目标或它的前置内容，可以开始阅读。" : "已具备本节的前置阅读条件。";
            } else {
                action = "REVISIT"; priority = 5;
                reason = samples > 0 ? "本空间已有独立答对的题目家族，可以回顾证据或继续后续内容。" :
                    last != null ? "已有作答记录。可回顾规则，独立掌握度以题目家族投影为准。" : "已经读完本节，可以从目录继续学习。";
            }
            ordered.add(new Ranked(new Step(id,topic.code(),topic.title(),action,reason,origin,exercise,completed,
                last == null ? 0 : last.count(),samples),priority,targets.contains(id)));
        }
        ordered.sort(Comparator.comparingInt(Ranked::priority).thenComparing(Ranked::target, Comparator.reverseOrder())
            .thenComparing(item -> item.step().code()));
        return new Path(workspace,release,ordered.stream().map(Ranked::step).toList(),"workspace-evidence-v1");
    }

    private static boolean matches(String goal, Topic topic) {
        String normalized = goal.toLowerCase(Locale.ROOT);
        return normalized.contains(topic.title().toLowerCase(Locale.ROOT))
            || java.util.regex.Pattern.compile("(?i)(?<![a-z0-9])" + java.util.regex.Pattern.quote(topic.code())
                + "(?![a-z0-9])").matcher(goal).find();
    }
}
