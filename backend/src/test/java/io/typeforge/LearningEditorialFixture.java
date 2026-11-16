package io.typeforge;

import io.typeforge.identity.IdentityService;
import io.typeforge.ingestion.IngestionModels.*;
import io.typeforge.ingestion.ManualIngestionService;
import io.typeforge.publishing.ContentDraftService;
import io.typeforge.publishing.ContentWorkbenchModels.*;
import io.typeforge.publishing.ReleaseWorkbenchService;
import io.typeforge.shared.JsonCodec;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Named test actors and actual supplied source/review/publish services, never a production human review. */
final class LearningEditorialFixture implements AutoCloseable {
    static final String PASSWORD="Learning-fixture-only-password-608!";
    final IdentityService identities;
    final ContentDraftService drafts;
    final ReleaseWorkbenchService releases;
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final IdentityService.Session author,reviewer;
    final Concept concept;
    final Chunk chunk;
    final String originalActive;
    LearningEditorialFixture(IdentityService identities,ContentDraftService drafts,ReleaseWorkbenchService releases,ManualIngestionService ingestion,JdbcTemplate jdbc,TransactionTemplate tx) {
        this.identities=identities;this.drafts=drafts;this.releases=releases;this.jdbc=jdbc;this.tx=tx;
        var previous=jdbc.queryForList("SELECT release_id FROM active_release WHERE singleton_id=1",String.class);originalActive=previous.isEmpty()?null:previous.get(0);
        author=identities.register("learn-editor-"+key(),PASSWORD);reviewer=identities.register("learn-review-"+key(),PASSWORD);
        for(UUID id:List.of(author.userId(),reviewer.userId()))jdbc.update("UPDATE app_user SET roles='[\"LEARNER\",\"EDITOR\"]' WHERE id=?",id.toString());
        concept=drafts.createConcept(author.userId(),new ConceptInput(UUID.randomUUID(),"L"+key().replace("-","").substring(0,12).toUpperCase(Locale.ROOT),"Exact answer and safe generic types",List.of()),key());
        var source=ingestion.register(author.userId(),new SourceInput("Bounded learning test source","https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html",key(),"Test-only supplied excerpt and original explanations."),key());
        var capture=ingestion.capture(author.userId(),source.id(),new CaptureInput("Actual supplied test original","Independent, short explanation of generic type constraints.","Explicit fixture input; no external fetch or live human review."),key());
        var created=drafts.createChunk(author.userId(),new ChunkInput(UUID.randomUUID(),1,0,concept.id(),"Generic type constraints","A type parameter preserves relationships; container subtype relationships cannot be inferred from element subtyping.",List.of(new SourceReference(source.id(),"4.5",capture.id()))),key());
        approve("CHUNK",created.id(),1,created.reviewHash());chunk=drafts.chunk(author.userId(),created.id(),1);
    }
    static String key(){return UUID.randomUUID().toString();}
    IdentityService.Session learner(){return identities.register("learn-student-"+key(),PASSWORD);}
    Exercise draft(String kind,UUID family,ChoiceAnswer answer) {
        return drafts.createExercise(author.userId(),new ExerciseInput(UUID.randomUUID(),1,0,family,kind,1,"Give the precise answer within the declared policy.",List.of(concept.id()),answer,List.of(new ContentRef(chunk.id(),1))),key());
    }
    Exercise approved(String kind,UUID family,ChoiceAnswer answer) {var value=draft(kind,family,answer);approve("EXERCISE",value.id(),1,value.reviewHash());return drafts.exercise(author.userId(),value.id(),1);}
    Review approve(String kind,UUID id,int revision,String hash){return drafts.review(reviewer.userId(),new ReviewInput(kind,id,revision,hash,"APPROVED","Independent test reviewer checked exact fixed content, positive and negative boundaries; not a live production review."),key());}
    Release publish(Exercise... questions) {
        var release=releases.create(author.userId(),new ReleaseInput("learn-"+key().substring(0,20),List.of(new ContentRef(chunk.id(),1)),java.util.Arrays.stream(questions).map(q->new ContentRef(q.id(),q.revision())).toList()),key());
        return releases.publish(author.userId(),release.id(),new PublishInput(release.manifestHash(),release.activeRevision()),key());
    }
    static ChoiceAnswer choice(String correct) {return new ChoiceAnswer(List.of(new ChoiceOption("A","List<?>"),new ChoiceOption("B","raw List")),List.of(correct),"EDITOR_PRIVATE_EXPLANATION",List.of("RAW_TYPE"),List.of(),List.of("Name the source and target.","Check the declared bound.","Compare a positive and negative example."));}
    @Override public void close() {
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT singleton_id FROM content_workbench_lock WHERE singleton_id=1 FOR UPDATE",Integer.class);
            var rows=jdbc.queryForList("SELECT release_id,revision FROM active_release WHERE singleton_id=1 FOR UPDATE");if(rows.isEmpty())return;
            String current=rows.get(0).get("release_id").toString();if(current.equals(originalActive))return;
            assertEquals(author.userId().toString(),jdbc.queryForObject("SELECT created_by FROM content_release WHERE id=?",String.class,current),"Do not overwrite another fixture's publication");
            int revision=((Number)rows.get(0).get("revision")).intValue();
            if(originalActive==null)assertEquals(1,jdbc.update("DELETE FROM active_release WHERE singleton_id=1 AND release_id=? AND revision=?",current,revision));
            else assertEquals(1,jdbc.update("UPDATE active_release SET release_id=?,revision=? WHERE singleton_id=1 AND release_id=? AND revision=?",originalActive,revision+1,current,revision));
        });
    }
}
