package io.typeforge.learning;

import io.typeforge.tutor.CoursewareSlideSource;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PersonalSlideSource implements CoursewareSlideSource {
    private final JdbcTemplate jdbc;
    private final PersonalCourseware courseware;
    public PersonalSlideSource(JdbcTemplate jdbc,PersonalCourseware courseware) { this.jdbc=jdbc; this.courseware=courseware; }

    @Override public Optional<Edition> edition(UUID owner,UUID workspace,UUID run) {
        var plans=jdbc.query("""
            SELECT p.id,p.concept_id FROM personal_courseware p JOIN courseware_study_run r
            ON r.workspace_id=p.workspace_id AND r.plan_id=p.id WHERE p.workspace_id=? AND r.run_id=?
            """,(rs,n)->courseware.get(owner,workspace,UUID.fromString(rs.getString(2)),UUID.fromString(rs.getString(1))),
            workspace.toString(),run.toString());
        return plans.stream().findFirst().map(p -> new Edition(p.title(),p.scenes().stream().flatMap(s -> {
            Reference evidence=null;
            if (s.chunkId()!=null) {
                var hash=jdbc.queryForObject("SELECT content_hash FROM knowledge_chunk WHERE id=? AND revision=?",String.class,
                    s.chunkId().toString(),s.chunkRevision());
                evidence=new Reference("KNOWLEDGE_CHUNK",s.chunkId(),s.chunkRevision(),hash);
            }
            var card=new Card(s.key(),s.title(),s.kind(),s.body(),s.prompt(),evidence);
            if (s.selfCheck()==null) return java.util.stream.Stream.of(card);
            var check=s.selfCheck();
            var answer=new Card(s.key()+"-solution","自主练习解析","READ",
                check.options().get(check.answerIndex())+"。\n"+check.explanation(),null,evidence);
            return java.util.stream.Stream.of(card,answer);
        }).toList(),p.brief()==null?"":p.brief().interest(),p.brief()==null?"":p.brief().requirements()));
    }
}
