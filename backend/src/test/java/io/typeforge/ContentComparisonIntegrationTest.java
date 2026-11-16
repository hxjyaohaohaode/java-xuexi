package io.typeforge;

import io.typeforge.identity.IdentityService;
import io.typeforge.ingestion.IngestionModels.*;
import io.typeforge.ingestion.ManualIngestionService;
import io.typeforge.publishing.ContentComparisonService;
import io.typeforge.publishing.ContentDraftService;
import io.typeforge.publishing.ContentWorkbenchModels.*;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentComparisonIntegrationTest {
    private static final String PASSWORD="Comparison-test-only-password-614!";
    @Autowired IdentityService identities;
    @Autowired ContentDraftService drafts;
    @Autowired ContentComparisonService comparisons;
    @Autowired ManualIngestionService ingestion;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired SchemaCatalog schemas;
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {PrivateModuleDatabaseFixture.configure(properties,"content-diff");}
    @BeforeAll void databaseProof() {PrivateModuleDatabaseFixture.prove(jdbc,json,getClass().getSimpleName());}
    private static String key() {return UUID.randomUUID().toString();}
    private IdentityService.Session account(boolean editor) {
        var user=identities.register("diff-"+key(),PASSWORD);if(editor) jdbc.update("UPDATE app_user SET roles='[\"LEARNER\",\"EDITOR\"]' WHERE id=?",user.userId().toString());return user;
    }
    private record Fixture(IdentityService.Session editor,UUID concept,List<SourceReference> sources,Chunk first) {}
    private Fixture fixture() {
        var editor=account(true);UUID actor=editor.userId();
        var concept=drafts.createConcept(actor,new ConceptInput(UUID.randomUUID(),"D"+key().replace("-","").substring(0,12).toUpperCase(java.util.Locale.ROOT),"泛型修订测试",List.of()),key());
        var source=ingestion.register(actor,new SourceInput("修订测试来源","https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html",key(),"独立测试来源，只记录人工提供的正文。"),key());
        var capture=ingestion.capture(actor,source.id(),new CaptureInput("original","cleaned","actual supplied test input"),key());var refs=List.of(new SourceReference(source.id(),"4.5",capture.id()));
        var first=drafts.createChunk(actor,new ChunkInput(UUID.randomUUID(),1,0,concept.id(),"旧标题","旧正文",refs),key());return new Fixture(editor,concept.id(),refs,first);
    }
    private Chunk revise(Fixture f,String title,String body) {return drafts.createChunk(f.editor().userId(),new ChunkInput(f.first().id(),2,1,f.concept(),title,body,f.sources()),key());}
    private static ChoiceAnswer answer(String option) {return new ChoiceAnswer(List.of(new ChoiceOption("A","List<?>"),new ChoiceOption("B","raw List")),List.of(option),"EDITOR_ONLY_RULE_TEXT",List.of("RAW_TYPE"));}

    @Test void comparesImmutableFieldsWithStablePathsAndRetainsHashHeaders() {
        var f=fixture();var second=revise(f,"新标题","新正文\n    List<T> value;");
        var result=comparisons.compare(f.editor().userId(),"CHUNK",f.first().id(),1,2);schemas.validate("ContentRevisionComparison",result);assertEquals(f.first().reviewHash(),result.fromReviewHash());assertEquals(second.reviewHash(),result.toReviewHash());
        assertEquals(List.of("/body","/title"),result.changes().stream().map(ContentComparisonService.Change::path).toList());assertEquals("旧正文",result.changes().get(0).before().asText());assertEquals("新正文\n    List<T> value;",result.changes().get(0).after().asText());
        assertEquals("旧正文",drafts.chunk(f.editor().userId(),f.first().id(),1).body());
    }
    @Test void metadataOnlyRevisionProducesNoInventedContentChange() {
        var f=fixture();revise(f,f.first().title(),f.first().body());var result=comparisons.compare(f.editor().userId(),"CHUNK",f.first().id(),1,2);
        assertTrue(result.changes().isEmpty());assertNotEquals(result.fromReviewHash(),result.toReviewHash());
    }
    @Test void reverseComparisonReversesBeforeAndAfterWithoutMutatingTheHistory() {
        var f=fixture();revise(f,"新版","修改内容");var forward=comparisons.compare(f.editor().userId(),"CHUNK",f.first().id(),1,2);var reverse=comparisons.compare(f.editor().userId(),"CHUNK",f.first().id(),2,1);
        for(int i=0;i<forward.changes().size();i++) {assertEquals(forward.changes().get(i).path(),reverse.changes().get(i).path());assertEquals(forward.changes().get(i).before(),reverse.changes().get(i).after());assertEquals(forward.changes().get(i).after(),reverse.changes().get(i).before());}
    }
    @Test void privateAnswerChangesAreVisibleOnlyToCurrentEditors() {
        var f=fixture();var first=drafts.createExercise(f.editor().userId(),new ExerciseInput(UUID.randomUUID(),1,0,UUID.randomUUID(),"SINGLE",1,"选择类型安全声明",List.of(f.concept()),answer("A"),List.of(new ContentRef(f.first().id(),1))),key());
        drafts.createExercise(f.editor().userId(),new ExerciseInput(first.id(),2,1,first.familyId(),"SINGLE",2,first.prompt(),first.conceptIds(),answer("B"),first.knowledgeRefs()),key());
        var result=comparisons.compare(f.editor().userId(),"EXERCISE",first.id(),1,2);assertEquals(Set.of("/answerSpec/optionIds","/difficulty"),Set.copyOf(result.changes().stream().map(ContentComparisonService.Change::path).toList()));
        var student=account(false);assertEquals(403,assertThrows(DomainException.class,()->comparisons.compare(student.userId(),"EXERCISE",first.id(),1,2)).status());
        jdbc.update("UPDATE app_user SET roles='[\"LEARNER\"]' WHERE id=?",f.editor().userId().toString());assertEquals(403,assertThrows(DomainException.class,()->comparisons.compare(f.editor().userId(),"EXERCISE",first.id(),1,2)).status());
    }
    @Test void rejectsUnknownKindMissingRevisionAndSingleRevisionComparison() {
        var f=fixture();revise(f,"新","新版");
        assertEquals(400,assertThrows(DomainException.class,()->comparisons.compare(f.editor().userId(),"SOURCE",f.first().id(),1,2)).status());assertEquals(400,assertThrows(DomainException.class,()->comparisons.compare(f.editor().userId(),"CHUNK",f.first().id(),1,1)).status());
        assertEquals(404,assertThrows(DomainException.class,()->comparisons.compare(f.editor().userId(),"CHUNK",f.first().id(),1,999)).status());assertEquals(400,assertThrows(DomainException.class,()->comparisons.compare(f.editor().userId(),"CHUNK",f.first().id(),0,1)).status());
    }
    @Test void integrityFailureCannotBeRenderedAsAnOrdinaryContentDiff() {
        var f=fixture();revise(f,"新","新版");jdbc.update("UPDATE knowledge_chunk SET body='tampered' WHERE id=? AND revision=2",f.first().id().toString());
        assertEquals(409,assertThrows(DomainException.class,()->comparisons.compare(f.editor().userId(),"CHUNK",f.first().id(),1,2)).status());
    }
    @Test void realHttpSessionCanReadDiffAndLearnerCannotProbeIt() throws Exception {
        var f=fixture();revise(f,"新标题","新正文");var editor=HttpSessionFixture.login(mvc,json,f.editor().username(),PASSWORD);
        var response=mvc.perform(get("/api/v1/admin/content-diffs").session(editor).param("kind","CHUNK").param("id",f.first().id().toString()).param("fromRevision","1").param("toRevision","2")).andReturn().getResponse();assertEquals(200,response.getStatus(),response.getContentAsString());assertEquals(2,json.tree(response.getContentAsString()).path("changes").size());
        var learner=account(false);var student=HttpSessionFixture.login(mvc,json,learner.username(),PASSWORD);assertEquals(403,mvc.perform(get("/api/v1/admin/content-diffs").session(student).param("kind","CHUNK").param("id",f.first().id().toString()).param("fromRevision","1").param("toRevision","2")).andReturn().getResponse().getStatus());
    }
}
