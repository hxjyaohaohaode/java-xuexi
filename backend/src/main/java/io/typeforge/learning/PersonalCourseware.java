package io.typeforge.learning;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.curriculum.CurriculumService.ConceptView;
import io.typeforge.curriculum.CurriculumService.EvidenceView;
import io.typeforge.operations.RuntimeCapabilityProvider;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.LearningContextJournal;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A student's immutable courseware revision. Only validated, owner-scoped inputs reach the outline. */
@Service
public class PersonalCourseware implements RuntimeCapabilityProvider {
    public record Brief(String interest,String requirements) {
        public Brief {
            interest=clean(interest,160); requirements=clean(requirements,600);
        }
        private static String clean(String value,int max) {
            if (value==null || value.codePointCount(0,value.length())>max || value.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\t' && c!='\r'))
                throw DomainException.invalid("Invalid lesson brief text");
            return value.strip();
        }
    }
    public record SelfCheck(List<String> options,int answerIndex,String explanation,
            @JsonInclude(JsonInclude.Include.NON_NULL) String javaSource) {}
    public record Portable(String title,String body,String prompt) {}
    public record Scene(String key, String kind, String title, String body, String prompt, UUID chunkId,
            Integer chunkRevision, String exampleId, String exampleSha256, UUID exerciseId,
            Integer exerciseRevision, UUID attemptId,
            @JsonInclude(JsonInclude.Include.NON_NULL) SelfCheck selfCheck,
            @JsonInclude(JsonInclude.Include.NON_NULL) Portable portable) {
        Scene(String key,String kind,String title,String body,String prompt,UUID chunkId,Integer chunkRevision,
                String exampleId,String exampleSha256,UUID exerciseId,Integer exerciseRevision,UUID attemptId) {
            this(key,kind,title,body,prompt,chunkId,chunkRevision,exampleId,exampleSha256,exerciseId,exerciseRevision,attemptId,null,null);
        }
    }
    public record Plan(UUID id,UUID workspaceId,UUID releaseId,UUID conceptId,String conceptCode,String title,
            int revision,Instant createdAt,UUID snapshotId,long contextSequence,UUID checkpointId,String checkpointDigest,
            String contentHash,String requestedApproach,String strategy,String pace,List<String> reasons,List<Scene> scenes,
            @JsonInclude(JsonInclude.Include.NON_NULL) Brief brief) {
        Plan withHash(String hash) {
            return new Plan(id,workspaceId,releaseId,conceptId,conceptCode,title,revision,createdAt,snapshotId,contextSequence,
                checkpointId,checkpointDigest,hash,requestedApproach,strategy,pace,reasons,scenes,brief);
        }
    }
    record ExerciseSource(UUID id,int revision,String publicHash) {}
    record Sources(List<Reference> knowledge,List<ExerciseSource> exercises) {}
    private record Stored(UUID id,int revision,boolean automatic,String generationHash,String contentHash,String plan,String sources) {}
    private final JdbcTemplate jdbc;
    private final WorkspaceService workspaces;
    private final LearningContextJournal journal;
    private final CurriculumService course;
    private final JsonCodec json;
    private final Clock clock;
    private final LessonBriefStore briefs;
    private final InterestApplications applications;
    public PersonalCourseware(JdbcTemplate jdbc,WorkspaceService workspaces,LearningContextJournal journal,
            CurriculumService course,JsonCodec json,Clock clock,LessonBriefStore briefs,InterestApplications applications) {
        this.jdbc=jdbc; this.workspaces=workspaces; this.journal=journal; this.course=course; this.json=json; this.clock=clock; this.briefs=briefs; this.applications=applications;
    }
    @Override public Set<String> enabledCapabilities() { return Set.of("PERSONAL_COURSEWARE"); }

    @Transactional
    public Plan generate(UUID owner,UUID workspace,UUID concept,UUID selected,String approach,String key) {
        return generate(owner,workspace,concept,selected,approach,null,key);
    }

    @Transactional
    public Plan generate(UUID owner,UUID workspace,UUID concept,UUID selected,String approach,Brief requested,String key) {
        if (approach == null || !Set.of("AUTO","GUIDED","EXAMPLE_FIRST","CHALLENGE").contains(approach))
            throw DomainException.invalid("Unsupported teaching approach");
        WorkspaceService.requireKey(key);
        var scope=workspaces.lockOwned(owner,workspace);
        UUID release=scope.releaseId();
        var topic=course.concept(release,concept);
        var identity=new ArrayList<Object>(List.of(workspace,concept,selected == null ? "LATEST" : selected.toString(),approach));
        if (requested!=null) identity.add(requested);
        String signature=json.hash(identity);
        UUID replay=workspaces.replay(owner,"personal.courseware",key,signature);
        if (replay != null) return get(owner,workspace,concept,replay);

        var previous=stored(workspace,concept,null);
        WorkspaceService.Context snapshot=selected==null?null:workspaces.readContext(owner,workspace,selected);
        var instruction=briefs.resolve(owner,workspace,concept,requested,snapshot);
        Brief brief=instruction==null?null:instruction.brief();
        if (snapshot==null && previous.isPresent() && previous.get().automatic()) {
            var saved=json.read(previous.get().plan(),Plan.class);
            if (Objects.equals(saved.brief(),brief) && saved.contextSequence()==scope.eventSequence()) {
                // Reuse the source, then recompute the outline: updated public guidance may need a new edition.
                try { snapshot=workspaces.readContext(owner,workspace,saved.snapshotId()); }
                catch (DomainException changed) { if (changed.status()!=409 && changed.status()!=404) throw changed; }
            }
        }
        if (snapshot==null) snapshot=createSnapshot(owner,workspace,release,concept,instruction==null?null:instruction.reference());
        var checkpoint=journal.capture(owner,workspace,snapshot.id(),concept);
        var attempts=new ArrayList<PersonalLessonOutline.Attempt>();
        for (var fact:checkpoint.facts()) if (fact.kind().equals("ATTEMPT")) {
            var admitted=jdbc.query("""
                SELECT a.exercise_id,a.exercise_revision,e.family_id,a.score,a.maximum,a.hint_level,a.created_at FROM exercise_attempt a
                JOIN workspace_attempt wa ON wa.attempt_id=a.id
                JOIN exercise e ON e.id=a.exercise_id AND e.revision=a.exercise_revision
                JOIN exercise_concept ec ON ec.exercise_id=e.id AND ec.exercise_revision=e.revision
                WHERE a.id=? AND a.user_id=? AND wa.workspace_id=? AND a.release_id=? AND ec.concept_id=? AND a.status='GRADED'
                """,(r,n)->new PersonalLessonOutline.Attempt(fact.sourceId(),UUID.fromString(r.getString(1)),r.getInt(2),
                    UUID.fromString(r.getString(3)),r.getTimestamp(7).toInstant(),r.getBigDecimal(4).compareTo(r.getBigDecimal(5))==0,r.getInt(6)>0),
                fact.sourceId().toString(),owner.toString(),workspace.toString(),release.toString(),concept.toString());
            if (admitted.size()!=1) throw DomainException.conflict("An admitted attempt is unavailable");
            attempts.add(admitted.get(0));
        }
        attempts.sort(Comparator.comparing(PersonalLessonOutline.Attempt::gradedAt).thenComparing(a->a.id().toString()).reversed());
        var completed=completedReading(workspace,snapshot.eventSequence(),topic);
        var prerequisite=topic.prerequisiteIds().stream().filter(id->!completed.contains(id)).findFirst()
            .map(id->course.concept(release,id)).orElse(null);
        var questions=jdbc.query("""
            SELECT e.id,e.revision,e.family_id,e.difficulty,e.prompt FROM release_exercise re
            JOIN exercise e ON e.id=re.exercise_id AND e.revision=re.exercise_revision
            JOIN exercise_concept ec ON ec.exercise_id=e.id AND ec.exercise_revision=e.revision
            WHERE re.release_id=? AND ec.concept_id=? AND e.status='REVIEWED' ORDER BY e.difficulty,e.id
            """,(r,n)->new PersonalLessonOutline.Question(UUID.fromString(r.getString(1)),r.getInt(2),UUID.fromString(r.getString(3)),
                r.getInt(4),r.getString(5)),release.toString(),concept.toString());
        if (topic.evidence().isEmpty() && questions.isEmpty() && (topic.companion()==null || topic.companion().examples().isEmpty()))
            throw DomainException.conflict("No published teaching material is available for this concept");
        var outline=PersonalLessonOutline.build(topic,prerequisite,questions,attempts,checkpoint.facts(),completed.contains(concept),approach);
        var teachingPreference=brief!=null?brief:InterestApplications.profileInterest(checkpoint.facts());
        if (teachingPreference!=null) outline=applications.adapt(outline,topic,teachingPreference);
        var sources=sources(release,topic,prerequisite,outline.scenes());
        var generation=json.hash(List.of("courseware-v1",release,concept,approach,selected==null?"LATEST":selected.toString(),
            checkpoint.facts().stream().map(f->List.of(f.kind(),f.sourceId(),f.revision(),f.contentHash())).toList(),
            completed.stream().sorted().toList(),outline,sources,topic.companion()==null?"":topic.companion().version()));
        if (previous.isPresent() && previous.get().generationHash().equals(generation)) {
            try {
                var same=validate(owner,workspace,concept,release,previous.get());
                workspaces.remember(owner,"personal.courseware",key,signature,same.id());
                return same;
            } catch (DomainException changed) {
                if (changed.status()!=409) throw changed;
                // An unrelated dependency in the previous frozen snapshot may have been withdrawn.
            }
        }
        UUID id=UUID.randomUUID();
        int revision=Math.addExact(previous.map(Stored::revision).orElse(0),1);
        var body=new Plan(id,workspace,release,concept,topic.code(),topic.title(),revision,clock.instant(),snapshot.id(),
            snapshot.eventSequence(),checkpoint.id(),checkpoint.digest(),"",approach,outline.strategy(),outline.pace(),
            outline.reasons(),outline.scenes(),brief);
        String hash=json.hash(List.of(body,sources));
        jdbc.update("""
            INSERT INTO personal_courseware(id,workspace_id,release_id,concept_id,revision,snapshot_id,checkpoint_id,
            auto_snapshot,generation_hash,content_hash,plan_json,sources_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,id.toString(),workspace.toString(),release.toString(),concept.toString(),revision,snapshot.id().toString(),
            checkpoint.id().toString(),selected==null,generation,hash,json.write(body),json.write(sources));
        workspaces.remember(owner,"personal.courseware",key,signature,id);
        return body.withHash(hash);
    }

    private WorkspaceService.Context createSnapshot(UUID owner,UUID workspace,UUID release,UUID concept,Reference lessonMemory) {
        var attempts=jdbc.queryForList("""
            SELECT a.id FROM exercise_attempt a JOIN workspace_attempt wa ON wa.attempt_id=a.id
            JOIN exercise_concept ec ON ec.exercise_id=a.exercise_id AND ec.exercise_revision=a.exercise_revision
            WHERE wa.workspace_id=? AND a.user_id=? AND a.release_id=? AND ec.concept_id=? AND a.status='GRADED'
            AND a.score IS NOT NULL AND a.maximum IS NOT NULL AND a.feedback IS NOT NULL
            ORDER BY a.created_at DESC,a.id DESC LIMIT 8
            """,String.class,workspace.toString(),owner.toString(),release.toString(),concept.toString());
        return workspaces.createLessonContext(owner,workspace,attempts.isEmpty()?null:attempts.stream().map(UUID::fromString).toList(),lessonMemory);
    }
    private Set<UUID> completedReading(UUID workspace,long sequence,ConceptView topic) {
        var relevant=new HashSet<>(topic.prerequisiteIds());relevant.add(topic.id());
        var completed=new HashSet<UUID>();
        jdbc.query("SELECT payload FROM workspace_event WHERE workspace_id=? AND sequence_no<=? AND event_type='READ_COMPLETED'",
            r->{String id=json.tree(r.getString(1)).path("data").path("conceptId").asText();
                if (!id.isEmpty()) {UUID concept=UUID.fromString(id); if (relevant.contains(concept)) completed.add(concept);}
            }, workspace.toString(),sequence);
        return completed;
    }
    private Sources sources(UUID release,ConceptView topic,ConceptView prerequisite,List<Scene> scenes) {
        Map<UUID,EvidenceView> available=new HashMap<>();
        topic.evidence().forEach(c->available.put(c.chunkId(),c));
        if (prerequisite!=null) prerequisite.evidence().forEach(c->available.put(c.chunkId(),c));
        Map<UUID,Reference> cards=new LinkedHashMap<>();
        Map<UUID,ExerciseSource> exercises=new LinkedHashMap<>();
        for (var scene:scenes) {
            if (scene.chunkId()!=null) {
                var card=available.get(scene.chunkId());
                cards.put(card.chunkId(),new Reference("KNOWLEDGE_CHUNK",card.chunkId(),card.revision(),card.contentHash()));
            }
            if (scene.exerciseId()!=null) exercises.computeIfAbsent(scene.exerciseId(),id->new ExerciseSource(id,
                scene.exerciseRevision(),json.hash(course.exercise(release,id,scene.exerciseRevision()))));
        }
        return new Sources(List.copyOf(cards.values()),List.copyOf(exercises.values()));
    }

    @Transactional
    public Plan get(UUID owner,UUID workspace,UUID concept,UUID planId) {
        return lookup(owner,workspace,concept,planId).orElseThrow(DomainException::missing);
    }
    @Transactional
    public Optional<Plan> lookup(UUID owner,UUID workspace,UUID concept,UUID planId) {
        var scope=workspaces.requireOwned(owner,workspace);
        course.requirePublished(scope.releaseId());
        if (jdbc.queryForList("SELECT concept_id FROM release_concept WHERE release_id=? AND concept_id=?",
            String.class,scope.releaseId().toString(),concept.toString()).isEmpty()) throw DomainException.missing();
        return stored(workspace,concept,planId).map(row->validate(owner,workspace,concept,scope.releaseId(),row));
    }
    private Optional<Stored> stored(UUID workspace,UUID concept,UUID id) {
        String sql="SELECT id,revision,auto_snapshot,generation_hash,content_hash,plan_json,sources_json FROM personal_courseware WHERE workspace_id=? AND concept_id=?";
        Object[] args=id==null?new Object[]{workspace.toString(),concept.toString()}:new Object[]{workspace.toString(),concept.toString(),id.toString()};
        return jdbc.query(sql+(id==null?" ORDER BY revision DESC LIMIT 1":" AND id=?"),
            (r,n)->new Stored(UUID.fromString(r.getString(1)),r.getInt(2),r.getBoolean(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)),args)
            .stream().findFirst();
    }
    private Plan validate(UUID owner,UUID workspace,UUID concept,UUID release,Stored row) {
        Plan body=json.read(row.plan(),Plan.class);
        Sources lineage=json.read(row.sources(),Sources.class);
        if (!row.id().equals(body.id()) || row.revision()!=body.revision() || !workspace.equals(body.workspaceId())
            || !concept.equals(body.conceptId()) || !release.equals(body.releaseId()) || !json.hash(List.of(body.withHash(""),lineage)).equals(row.contentHash()))
            throw DomainException.conflict("Saved courseware no longer matches its revision");
        LearningContextJournal.Checkpoint check;
        try { check=journal.read(owner,workspace,body.checkpointId()); }
        catch (DomainException failure) {
            if (failure.status()!=404) throw failure;
            throw DomainException.conflict("A saved learning source is no longer available");
        }
        if (!check.snapshotId().equals(body.snapshotId()) || !concept.equals(check.conceptId()) || !check.digest().equals(body.checkpointDigest()))
            throw DomainException.conflict("Saved courseware no longer matches its learning context");
        workspaces.validateReferences(owner,workspace,lineage.knowledge());
        for (var source:lineage.exercises()) {
            try {
                if (json.hash(course.exercise(release,source.id(),source.revision())).equals(source.publicHash())) continue;
            } catch (DomainException failure) { if (failure.status()!=404) throw failure; }
            throw DomainException.conflict("A published exercise has changed");
        }
        return body.withHash(row.contentHash());
    }
}
