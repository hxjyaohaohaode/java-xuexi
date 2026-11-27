package io.typeforge.tutor;

import io.typeforge.curriculum.CurriculumService.EvidenceView;
import io.typeforge.shared.JsonCodec;
import io.typeforge.tutor.TutorAnswerProvider.Answer;
import io.typeforge.tutor.TutorAnswerProvider.Prompt;
import io.typeforge.tutor.TutorAnswerProvider.Usage;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** The teaching role is resolved from the admitted task, never inferred from text supplied by a learner. */
record TeachingTurn(String role, String searchText, UUID runId, int planRevision,long runEpoch) {
    static TeachingTurn resolve(JdbcTemplate jdbc, JsonCodec json, TutorService.Ticket ticket) {
        var matches=jdbc.query("""
            SELECT s.run_id,s.plan_revision,s.step_key,f.plan_json,r.plan_json,s.run_epoch
            FROM study_step_execution s JOIN study_workflow f ON f.run_id=s.run_id
            JOIN agent_run r ON r.id=s.run_id JOIN learning_workspace w ON w.id=r.workspace_id
            WHERE s.request_id=? AND r.workspace_id=? AND w.owner_id=?
            """, (rs,n)->{
                String key=rs.getString(3), goal=json.tree(rs.getObject(5)).path("goal").asText();
                String role=key.equals("review")?"REVIEW":"PLANNER";
                String query=goal;
                if(key.startsWith("expert-")) {
                    var section=json.tree(rs.getObject(4)).path("sections").path(Integer.parseInt(key.substring(7)));
                    role=section.path("role").asText();
                    // Retrieve on the actual focus before the general goal or workflow instructions.
                    query=section.path("focus").asText()+" "+goal;
                }
                return new TeachingTurn(role,bound(query,500),UUID.fromString(rs.getString(1)),rs.getInt(2),rs.getLong(6));
            },ticket.id().toString(),ticket.workspaceId().toString(),ticket.ownerId().toString());
        return matches.isEmpty()?new TeachingTurn("TUTOR",bound(ticket.question(),500),null,0,0):matches.get(0);
    }

    Answer localAnswer(JdbcTemplate jdbc, JsonCodec json, Prompt prompt) {
        List<EvidenceView> cards=prompt.evidence();
        var answer=new StringBuilder("本地证据整理（未调用语言模型）\n\n");
        switch(role) {
            case "REPAIR" -> {
                answer.append("回看本次作答\n");
                var attempt=prompt.learnerContext().stream().filter(v->"OWN_SUBMITTED_ATTEMPT".equals(v.get("kind"))).findFirst();
                attempt.ifPresent(item->answer.append("你提交的内容：").append(bound(String.valueOf(item.get("submittedAnswer")),420))
                    .append("\n该次得分：").append(item.get("score")).append("。\n"));
                answer.append("请标出原判断依赖的前提。在下面的规则中找一条能检验该前提的语句，再独立给出修正。\n\n");
            }
            case "EXAMPLE" -> {
                var card=cards.get(0);
                List<String> questions=List.of();
                for(var candidate:cards) {
                    questions=jdbc.queryForList("""
                    SELECT e.prompt FROM release_exercise re JOIN exercise e
                      ON e.id=re.exercise_id AND e.revision=re.exercise_revision
                    JOIN exercise_concept ec ON ec.exercise_id=e.id AND ec.exercise_revision=e.revision
                    WHERE re.release_id=? AND e.status='REVIEWED' AND ec.concept_id=?
                    ORDER BY e.difficulty,e.id LIMIT 1
                    """,String.class,candidate.releaseId().toString(),candidate.conceptId().toString());
                    if(!questions.isEmpty()) break;
                }
                if(questions.isEmpty()) answer.append("迁移练习\n以「").append(card.title()).append("」为对象，自己构造一个满足条件的例子，再只改动一个条件。");
                else answer.append("先独立预测\n课程题目：").append(bound(questions.get(0),700)).append("\n先写出判断及理由，再打开本节练习提交作答。");
                answer.append("\n\n");
            }
            case "CONCEPT" -> answer.append("规则与条件\n先读「").append(cards.get(0).title())
                .append("」。分别写出规则成立的前提和可以推出的结论，再用下列来源核对。\n\n");
            case "REVIEW" -> {
                answer.append("专家草稿的引用复核\n");
                var handoffs=jdbc.query("""
                    SELECT evidence_refs FROM agent_message WHERE run_id=? AND receiver_role='fact-checker'
                      AND dedupe_key LIKE ? ORDER BY dedupe_key
                    """,(rs,n)->json.tree(rs.getObject(1)),runId.toString(),planRevision+":"+runEpoch+":%:review");
                var sources=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                for(var handoff:handoffs) for(var ref:handoff) {
                    sources.addAll(jdbc.query("""
                        SELECT rt.evidence_refs FROM tutor_artifact_link l JOIN tutor_request_runtime rt ON rt.request_id=l.request_id
                        WHERE l.artifact_id=? AND l.artifact_revision=?
                        """,(rs,n)->json.tree(rs.getObject(1)),ref.path("id").asText(),ref.path("revision").asInt()));
                }
                var current=new HashSet<String>();
                cards.forEach(card->current.add(card.chunkId()+":"+card.revision()+":"+card.contentHash()));
                int n=0;
                for(var expert:sources) {
                    int overlap=0;
                    for(var citation:expert) if(current.contains(citation.path("chunkId").asText()+":"+citation.path("revision").asInt()+":"+citation.path("contentHash").asText())) overlap++;
                    answer.append("草稿 ").append(++n).append(" 引用 ").append(expert.size()).append(" 条；本次检索中有 ")
                        .append(overlap).append(" 条的标识、版本和摘要与草稿一致。\n");
                }
                answer.append("请逐条对照原文核对推论，特别检查代码例子适用的类型边界。\n\n");
            }
            default -> {
                String focus=switch(prompt.hintLevel()) {
                    case 0 -> "先写下自己的预测，再展开课程材料核对。";
                    case 1 -> "先读第一条来源的标题，找出问题涉及的概念。";
                    case 2 -> "把原问题的前提与第一条规则逐项对照。";
                    case 3 -> "改变例子的一个条件，观察原来的推论是否仍然成立。";
                    default -> "对照各条原文，列出它们分别能支持的判断。";
                };
                answer.append(focus).append("\n\n");
            }
        }
        int index=0;
        for(var source:cards) answer.append(++index).append(". ").append(source.title()).append("\n")
            .append(bound(source.excerpt(),1100)).append("\n\n");
        return new Answer(answer.toString(),cards.stream().map(EvidenceView::chunkId).toList(),
            "课程原文按检索结果列出。学习提问和引用核对由本地规则生成；教学推论仍需结合原文、实际作答或运行结果复核。",new Usage(0,0,0));
    }

    /** Search limits are measured in UTF-16 units by the existing provider and never split a surrogate pair. */
    private static String bound(String value,int limit) {
        if(value.length()<=limit) return value;
        return value.substring(0,Character.isHighSurrogate(value.charAt(limit-1))?limit-1:limit);
    }
}
