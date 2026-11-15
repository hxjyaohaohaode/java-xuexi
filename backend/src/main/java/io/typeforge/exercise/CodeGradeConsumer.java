package io.typeforge.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.compiler.CodeRubric;
import io.typeforge.compiler.CompileProtocol;
import io.typeforge.operations.events.DomainEventConsumer;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.DomainException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A durable worker result becomes a score only after the fixed private positive/negative rubric is checked. */
@Component
public final class CodeGradeConsumer implements DomainEventConsumer {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final EventJournal events;
    public CodeGradeConsumer(JdbcTemplate jdbc,JsonCodec json,EventJournal events){this.jdbc=jdbc;this.json=json;this.events=events;}
    @Override public String name(){return "compile-contract-grading-v1";}
    @Override public boolean accepts(String type){return type.equals("COMPILE_FINISHED");}
    @Override public void accept(JsonNode envelope) {
        String jobId=envelope.path("data").path("jobId").asText(),workspace=envelope.path("workspaceId").asText();
        var rows=jdbc.queryForList("SELECT j.status AS job_status,j.result,j.source_hash,j.attempt_id,a.answer,a.status AS attempt_status,a.exercise_id,a.exercise_revision,a.release_id,a.hint_level,e.answer_spec,w.request_json,w.request_hash,u.status AS user_status,lw.status AS workspace_status FROM compile_job j JOIN workspace_compile w ON w.job_id=j.id JOIN exercise_attempt a ON a.id=j.attempt_id JOIN exercise e ON e.id=a.exercise_id AND e.revision=a.exercise_revision JOIN workspace_attempt wa ON wa.attempt_id=a.id AND wa.workspace_id=w.workspace_id JOIN learning_workspace lw ON lw.id=w.workspace_id JOIN app_user u ON u.id=a.user_id WHERE j.id=? AND w.workspace_id=? AND a.user_id=j.user_id AND a.release_id=j.release_id",jobId,workspace);
        if(rows.isEmpty())return;var row=rows.get(0);
        if(!Set.of("PENDING","GRADING").contains(row.get("attempt_status").toString()))return;
        String attempt=row.get("attempt_id").toString(),jobStatus=row.get("job_status").toString();
        if(!Set.of("SUCCEEDED","COMPILE_ERROR").contains(jobStatus) || !row.get("user_status").equals("ACTIVE") || !row.get("workspace_status").equals("ACTIVE")) {fail(attempt,workspace,"编译任务未产生可判分结果；没有记为答错，也没有增加掌握证据。");return;}
        var checks=List.of(json.read(json.tree(row.get("request_json")).toString(),CompileProtocol.Check[].class));
        if(!json.hash(checks).equals(row.get("request_hash")) || checks.isEmpty()) {fail(attempt,workspace,"固定判分依据未通过完整性校验；请联系课程维护者。");return;}
        JsonNode spec=json.tree(row.get("answer_spec"));
        try {if(!checks.equals(CodeRubric.parse(spec))) {fail(attempt,workspace,"题目判分依据与提交时不一致；本次没有记录成绩。");return;}}
        catch(DomainException invalid){fail(attempt,workspace,"题目判分依据无法复验；本次没有记录成绩。");return;}
        if(!json.sha256(json.tree(row.get("answer")).path("source").asText()).equals(row.get("source_hash"))) {fail(attempt,workspace,"源码与作答记录不一致；本次没有记录成绩。");return;}
        CompileProtocol.Result result=json.read(json.tree(row.get("result")).toString(),CompileProtocol.Result.class);
        if(result.checks().stream().anyMatch(check->!Set.of("SUCCEEDED","COMPILE_ERROR").contains(check.status()))) {fail(attempt,workspace,"部分检查遇到超时或资源问题；本次不作为错误学习证据。");return;}
        boolean correct=jobStatus.equals("SUCCEEDED") && CodeRubric.passes(checks,result.checks());
        String explanation=(correct?"源码通过了该题固定的正向与反向编译契约检查。":"源码未通过该题的编译契约检查。")+"这只验证题目规定的类型约束，不证明任意运行时行为。\n"+spec.path("explanation").asText();
        var feedback=Map.of("explanation",explanation,"correct",correct,"misconceptions",correct?json.tree("[]"):spec.path("misconceptions"),"evidenceClass",((Number)row.get("hint_level")).intValue()==0?"INDEPENDENT_FIRST_ATTEMPT":"ASSISTED_PRACTICE","compilerJobId",jobId,"rubricVersion",CodeRubric.VERSION);
        jdbc.update("UPDATE exercise_attempt SET status='GRADED',score=?,maximum=100,feedback=? WHERE id=? AND status IN ('PENDING','GRADING')",correct?new BigDecimal("100.00"):new BigDecimal("0.00"),json.write(feedback),attempt);
        events.append(UUID.fromString(workspace),"ATTEMPT_GRADED","SERVER",Map.of("attemptId",attempt,"releaseId",row.get("release_id"),"exerciseId",row.get("exercise_id"),"exerciseRevision",row.get("exercise_revision"),"compilerJobId",jobId));
    }
    private void fail(String attempt,String workspace,String explanation){
        jdbc.update("UPDATE exercise_attempt SET status='FAILED',score=NULL,maximum=NULL,feedback=? WHERE id=? AND status IN ('PENDING','GRADING')",json.write(Map.of("explanation",explanation,"misconceptions",List.of())),attempt);
        events.append(UUID.fromString(workspace),"ATTEMPT_FAILED","SERVER",Map.of("attemptId",attempt,"evidenceClass","NO_GRADING_EVIDENCE"));
    }
}
