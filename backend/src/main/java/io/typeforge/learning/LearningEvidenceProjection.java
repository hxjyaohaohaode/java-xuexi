package io.typeforge.learning;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.operations.events.DomainEventConsumer;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Deterministic evidence accounting. Activity, assisted practice and mastery are distinct facts. */
@Component
public final class LearningEvidenceProjection implements DomainEventConsumer {
    private record Evidence(String owner,String release,BigDecimal score,BigDecimal maximum,int hint,Timestamp occurred,
                            String family,String concept,BigDecimal weight) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public LearningEvidenceProjection(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc;this.clock=clock; }
    @Override public String name() { return "learning-evidence-v1"; }
    @Override public boolean accepts(String eventType) { return eventType.equals("ATTEMPT_GRADED"); }
    @Override public void accept(JsonNode envelope) {
        String workspace=envelope.path("workspaceId").asText();
        String attempt=envelope.path("data").path("attemptId").asText();
        var rows=jdbc.query("SELECT a.user_id,a.release_id,a.score,a.maximum,a.hint_level,a.created_at,e.family_id,ec.concept_id,ec.weight FROM exercise_attempt a JOIN workspace_attempt wa ON wa.attempt_id=a.id JOIN learning_workspace w ON w.id=wa.workspace_id AND w.owner_id=a.user_id AND w.release_id=a.release_id JOIN exercise e ON e.id=a.exercise_id AND e.revision=a.exercise_revision JOIN exercise_concept ec ON ec.exercise_id=e.id AND ec.exercise_revision=e.revision WHERE a.id=? AND wa.workspace_id=? AND a.status='GRADED' ORDER BY ec.concept_id",
            (rs,n)->new Evidence(rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getInt(5),rs.getTimestamp(6),rs.getString(7),rs.getString(8),rs.getBigDecimal(9)),attempt,workspace);
        if(rows.isEmpty()) throw new IllegalStateException("Graded event has no authoritative learning evidence");
        for(var row:rows) {
            String owner=row.owner(),concept=row.concept(),family=row.family();
            BigDecimal score=row.score(),maximum=row.maximum(),weight=row.weight();
            boolean correct=score.compareTo(maximum)==0;
            int hint=row.hint();
            Timestamp occurred=row.occurred();
            // Queue delays must not rewrite learning history or postpone the suggested review date.
            jdbc.update("INSERT INTO learning_event(id,user_id,release_id,concept_id,attempt_id,event_type,payload,occurred_at) VALUES(?,?,?,?,?,'ATTEMPT_GRADED',?,?)",UUID.randomUUID().toString(),owner,row.release(),concept,attempt,envelope.toString(),occurred);
            // The dispatcher holds user -> workspace locks; concept rows follow in stable ID order.
            if(jdbc.queryForList("SELECT user_id FROM learner_concept WHERE user_id=? AND concept_id=? FOR UPDATE",owner,concept).isEmpty()) {
                jdbc.update("INSERT INTO learner_concept(user_id,concept_id,algorithm_version,updated_at) VALUES(?,?,'family-first-v1',?)",owner,concept,Timestamp.from(clock.instant()));
            }
            boolean sampled=!jdbc.queryForList("SELECT origin_attempt_id FROM learning_family_evidence WHERE user_id=? AND concept_id=? AND family_id=?",owner,concept,family).isEmpty();
            if(hint==0 && !sampled) {
                jdbc.update("INSERT INTO learning_family_evidence(user_id,concept_id,family_id,origin_attempt_id,correct_weight,total_weight) VALUES(?,?,?,?,?,?)",owner,concept,family,attempt,correct?weight:BigDecimal.ZERO,weight);
                jdbc.update("UPDATE learner_concept SET independent_samples=independent_samples+1,weighted_correct=weighted_correct+?,weighted_total=weighted_total+?,revision=revision+1,updated_at=? WHERE user_id=? AND concept_id=?",correct?weight:BigDecimal.ZERO,weight,Timestamp.from(clock.instant()),owner,concept);
            }
            if(!correct) {
                // Another workspace may already have projected genuinely later corrective evidence.
                // Equal timestamps do not establish order; a random UUID must not invent causality.
                boolean corrected=!jdbc.queryForList("SELECT evidence.origin_attempt_id FROM learning_family_evidence evidence JOIN exercise_attempt later ON later.id=evidence.origin_attempt_id AND later.user_id=evidence.user_id WHERE evidence.user_id=? AND evidence.concept_id=? AND evidence.family_id<>? AND later.status='GRADED' AND later.hint_level=0 AND later.score=later.maximum AND later.created_at>?",owner,concept,family,occurred).isEmpty();
                jdbc.update("INSERT INTO review_item(id,user_id,concept_id,origin_attempt_id,due_at,reason,status) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),owner,concept,attempt,Timestamp.from(occurred.toInstant().plusSeconds(86400)),"本次客观作答尚未通过；建议先说明类型约束，再做同概念的新题目家族。此间隔是启发式建议。",corrected?"DONE":"PENDING");
            } else if(hint==0 && !sampled) {
                jdbc.update("UPDATE review_item SET status='DONE' WHERE user_id=? AND concept_id=? AND status='PENDING' AND origin_attempt_id IN (SELECT a.id FROM exercise_attempt a JOIN exercise e ON e.id=a.exercise_id AND e.revision=a.exercise_revision WHERE a.user_id=? AND e.family_id<>? AND a.created_at<?)",owner,concept,owner,family,occurred);
            }
        }
    }
}
