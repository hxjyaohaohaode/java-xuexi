package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.artifacts.ArtifactExportService;
import io.typeforge.artifacts.TextArtifactRenderer;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.exercise.LearningService;
import io.typeforge.identity.IdentityService;
import io.typeforge.identity.OperatorRoleBootstrap;
import io.typeforge.identity.PreferenceService;
import io.typeforge.lab.LabService;
import io.typeforge.learning.ReadingService;
import io.typeforge.notebook.NotebookActions;
import io.typeforge.notebook.NotebookService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.operations.events.OutboxDispatcher;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:interactive-learning;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH")
@AutoConfigureMockMvc @ActiveProfiles({"test","demo"})
class InteractiveLearningTest {
    @Autowired IdentityService identities;
    @Autowired WorkspaceService workspaces;
    @Autowired CurriculumService curriculum;
    @Autowired LearningService learning;
    @Autowired NotebookService notebooks;
    @Autowired NotebookActions actions;
    @Autowired LabService labs;
    @Autowired ReadingService readings;
    @Autowired ArtifactService artifacts;
    @Autowired ArtifactExportService exports;
    @Autowired TextArtifactRenderer renderer;
    @Autowired PreferenceService preferences;
    @Autowired JsonCodec json;
    @Autowired SchemaCatalog schemas;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxDispatcher outbox;
    @Autowired MockMvc mvc;
    private static final UUID RELEASE=DemoContentInitializer.RELEASE_ID;
    private String key(){return UUID.randomUUID().toString();}
    private IdentityService.Session account(){return identities.register("interactive-"+key(),"fixture-password-strong-123");}
    private WorkspaceService.Workspace workspace(UUID owner){return workspaces.create(owner,RELEASE,"类型与复练","ON_DEMAND",key());}
    private JsonNode tree(Object value){return json.tree(json.write(value));}
    private ArtifactService.Artifact document(UUID owner,UUID workspace,List<Reference> refs,String text) {
        var artifact=artifacts.create(owner,workspace,"DOCUMENT","学习记录",null,key());
        artifacts.save(owner,artifact.id(),0,tree(Map.of("kind","DOCUMENT","schemaVersion","1.0","title","学习记录","blocks",List.of(Map.of("id","body","kind","PARAGRAPH","text",text,"evidenceRefs",List.of())))),refs);return artifacts.get(owner,artifact.id());
    }
    @Test void preferencesUseRealIdentityQuotedEtagAndCompareAndSwap() throws Exception {
        var user=account();var body=tree(new PreferenceService.Preference("dark",true,18,1));
        var response=mvc.perform(put("/api/v1/me/preferences").session(HttpSessionFixture.login(mvc,json,user.username(),"fixture-password-strong-123")).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(body.toString())).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertEquals("\"2\"",response.getHeader("ETag"));schemas.validate("Preference",json.tree(response.getContentAsString()));
        assertEquals(412,mvc.perform(put("/api/v1/me/preferences").session(HttpSessionFixture.login(mvc,json,user.username(),"fixture-password-strong-123")).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(body.toString())).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(put("/api/v1/me/preferences").session(HttpSessionFixture.login(mvc,json,user.username(),"fixture-password-strong-123")).with(csrf()).header("If-Match","1").contentType("application/json").content(body.toString())).andReturn().getResponse().getStatus());
        assertEquals("dark",preferences.get(user.userId()).theme());
    }
    @Test void labsPersistReplayAuthorizeAndNeverIncreaseMastery() {
        UUID owner=account().userId();var workspace=workspace(owner);String idem=key();
        JsonNode input=tree(Map.of("workspaceId",workspace.id(),"releaseId",RELEASE,"labType","ASSIGNMENT","sourceType","List<Integer>","targetType","List<Number>","prediction","ALLOWED"));
        var session=labs.create(owner,input,idem);assertEquals("DENIED",session.verdict());assertEquals(false,session.predictionCorrect());schemas.validate("LabSession",tree(session));
        assertEquals(session.id(),labs.create(owner,input,idem).id());assertEquals(1,labs.list(owner,workspace.id()).size());assertTrue(learning.progress(owner).items().isEmpty());
        assertEquals(404,assertThrows(DomainException.class,()->labs.get(account().userId(),session.id())).status());
        assertTrue(workspaces.events(owner,workspace.id(),0,100).stream().anyMatch(e->e.type().equals("TYPE_RELATION_EVALUATED")));
    }
    @Test void readingReceiptIsServerTimedAndNotMasteryEvidence() {
        UUID owner=account().userId();var workspace=workspace(owner);var concept=curriculum.concepts(RELEASE).get(0);String idem=key();
        JsonNode input=tree(Map.of("workspaceId",workspace.id(),"releaseId",RELEASE,"conceptId",concept.id(),"eventType","READ_COMPLETED"));
        var record=readings.record(owner,input,idem);schemas.validate("LearningEventReceipt",tree(record));assertEquals(record.id(),readings.record(owner,input,idem).id());
        while(outbox.dispatchBatch(100)>0) { /* drain committed events */ }
        assertTrue(learning.progress(owner).items().isEmpty());assertEquals("ACTIVITY_NOT_MASTERY",record.evidenceClass());
    }
    @Test void notebookBlockSubmissionGradesRealExerciseAndRepeatedFamilyDoesNotFarmMastery() {
        UUID owner=account().userId();var workspace=workspace(owner);var exercise=curriculum.exercises(RELEASE,null,null,100).items().get(0);
        JsonNode spec=json.tree(jdbc.queryForObject("SELECT answer_spec FROM exercise WHERE id=? AND revision=?",String.class,exercise.id().toString(),exercise.revision()));
        var expected=new ArrayList<String>();spec.path("optionIds").forEach(v->expected.add(v.asText()));
        String wrong=exercise.options().stream().map(CurriculumService.OptionView::id).filter(v->!expected.contains(v)).findFirst().orElseThrow();
        var original=learning.submit(owner,workspace.id(),RELEASE,exercise.id(),exercise.revision(),tree(Map.of("kind","CHOICE","optionIds",List.of(wrong))),key());
        var notebook=notebooks.build(owner,workspace.id(),List.of(original.id()),"错题复练",key());var version=artifacts.version(owner,notebook.id(),1);String blockId=version.content().path("blocks").get(0).path("id").asText();
        var action=tree(Map.of("artifactRevision",1,"blockId",blockId,"action","submitAnswer","expectedBlockRevision",1,"answer",Map.of("kind","CHOICE","optionIds",expected),"selection",""));String idem=key();
        var result=actions.dispatch(owner,notebook.id(),action,idem);schemas.validate("NotebookActionResult",tree(result));assertEquals(result,actions.dispatch(owner,notebook.id(),action,idem));
        var practice=learning.get(owner,result.attemptId());assertEquals(4,practice.hintLevel());assertEquals(100,practice.score().intValue());
        while(outbox.dispatchBatch(100)>0) { /* exactly once projections */ }
        assertTrue(learning.progress(owner).items().stream().allMatch(p->p.independentSamples()==1));
        artifacts.save(owner,notebook.id(),1,version.content(),version.inputRefs());
        assertEquals(409,assertThrows(DomainException.class,()->actions.dispatch(owner,notebook.id(),action,key())).status());
    }
    @Test void realExportPersistsEscapedBytesPrivateDownloadAndRejectsStaleSources() throws Exception {
        var user=account();var workspace=workspace(user.userId());
        var memory=workspaces.stateMemory(user.userId(),workspace.id(),"使用明确的类型例子",key());
        // Snapshot returns the canonical memory reference used by every source consumer.
        Reference ref=workspaces.createContext(user.userId(),workspace.id()).references().stream().filter(r->r.kind().equals("MEMORY")).findFirst().orElseThrow();
        var artifact=document(user.userId(),workspace.id(),List.of(ref),"<script>alert('x')</script> & 类型");String idem=key();
        var job=exports.create(user.userId(),artifact.id(),1,"HTML",idem);assertEquals("QUEUED",job.status());assertEquals(job.id(),exports.create(user.userId(),artifact.id(),1,"HTML",idem).id());
        exports.dispatchOnce();var done=exports.get(user.userId(),job.id());schemas.validate("ExportJob",tree(done));assertEquals("SUCCEEDED",done.status());
        var file=exports.download(user.userId(),job.id());String html=new String(file.bytes(),StandardCharsets.UTF_8);assertTrue(html.contains("&lt;script&gt;"));assertFalse(html.contains("<script>"));assertEquals(json.sha256(html),file.contentHash());
        var response=mvc.perform(get("/api/v1/export-jobs/"+job.id()+"/download").session(HttpSessionFixture.login(mvc,json,user.username(),"fixture-password-strong-123"))).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals("private, no-store",response.getHeader("Cache-Control"));assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));assertTrue(response.getHeader("Content-Security-Policy").startsWith("sandbox;"));
        assertEquals(404,assertThrows(DomainException.class,()->exports.download(account().userId(),job.id())).status());
        workspaces.revokeMemory(user.userId(),workspace.id(),memory.id(),memory.revision());assertNull(exports.get(user.userId(),job.id()).downloadPath());assertEquals(409,assertThrows(DomainException.class,()->exports.download(user.userId(),job.id())).status());
    }
    @Test void queuedExportCanCancelAndOldSourceFailsWithoutFile() {
        UUID owner=account().userId();var workspace=workspace(owner);var artifact=document(owner,workspace.id(),List.of(),"一份固定源稿");
        var cancelled=exports.create(owner,artifact.id(),1,"MARKDOWN",key());assertEquals("CANCELLED",exports.cancel(owner,cancelled.id()).status());assertEquals("CANCELLED",exports.cancel(owner,cancelled.id()).status());
        var pending=exports.create(owner,artifact.id(),1,"MARKDOWN",key());var version=artifacts.version(owner,artifact.id(),1);artifacts.save(owner,artifact.id(),1,version.content(),version.inputRefs());
        exports.dispatchOnce();assertEquals("FAILED",exports.get(owner,pending.id()).status());assertEquals("STALE_EXPORT_SOURCE",exports.get(owner,pending.id()).errorCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM artifact_export_content WHERE export_id=?",Integer.class,pending.id().toString()));
        assertEquals(503,assertThrows(DomainException.class,()->exports.create(owner,artifact.id(),2,"MP4",key())).status());
    }
    @Test void operatorRolesRequireExistingAccountsAndAreAuditedWithoutCreatingCredentials() {
        var editor=account();var bootstrap=new OperatorRoleBootstrap(jdbc,tx,json,editor.username(),"");bootstrap.run(new DefaultApplicationArguments());bootstrap.run(new DefaultApplicationArguments());
        assertTrue(identities.session(editor.username()).roles().contains("EDITOR"));assertTrue(identities.loadUserByUsername(editor.username()).getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_EDITOR")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='OPERATOR_ROLE_GRANTED' AND resource_id=?",Integer.class,editor.userId().toString()));
        assertThrows(IllegalStateException.class,()->new OperatorRoleBootstrap(jdbc,tx,json,"missing-"+key(),"").run(new DefaultApplicationArguments()));
        assertEquals(List.of("LEARNER"),account().roles());
    }
    @Test void operatorBootstrapWaitsForEditorialAdmissionBeforeLockingAnyAccount() throws Exception {
        var author=account();var reviewer=account();
        var editorialLocked=new java.util.concurrent.CountDownLatch(1);
        var bootstrapAtAdmission=new java.util.concurrent.CountDownLatch(1);
        var observedJdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public <T> T queryForObject(String sql,Class<T> requiredType) {
                if(sql.contains("content_workbench_lock")) bootstrapAtAdmission.countDown();
                return super.queryForObject(sql,requiredType);
            }
        };
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var review=executor.submit(()->tx.executeWithoutResult(status->{
                jdbc.queryForObject("SELECT singleton_id FROM content_workbench_lock WHERE singleton_id=1 FOR UPDATE",Integer.class);
                jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",String.class,reviewer.userId().toString());
                editorialLocked.countDown();
                try { assertTrue(bootstrapAtAdmission.await(10,java.util.concurrent.TimeUnit.SECONDS),"Bootstrap must acquire editorial admission before account locks"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException(error); }
                // Mirrors the author FK lock needed when the independent reviewer records a decision.
                jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",String.class,author.userId().toString());
            }));
            var bootstrap=executor.submit(()->{
                try { assertTrue(editorialLocked.await(10,java.util.concurrent.TimeUnit.SECONDS)); }
                catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException(error); }
                new OperatorRoleBootstrap(observedJdbc,tx,json,author.username()+","+reviewer.username(),"").run(new DefaultApplicationArguments());
            });
            review.get(15,java.util.concurrent.TimeUnit.SECONDS);
            bootstrap.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(identities.session(author.username()).roles().contains("EDITOR"));
            assertTrue(identities.session(reviewer.username()).roles().contains("EDITOR"));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='OPERATOR_ROLE_GRANTED' AND resource_id IN (?,?)",Integer.class,author.userId().toString(),reviewer.userId().toString()));
        } finally {
            editorialLocked.countDown();bootstrapAtAdmission.countDown();executor.shutdownNow();
            assertTrue(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));
        }
        var unusedJdbc=new JdbcTemplate() {
            @Override public <T> T queryForObject(String sql,Class<T> requiredType) { fail("Empty operator configuration must not acquire database locks");return null; }
        };
        new OperatorRoleBootstrap(unusedJdbc,tx,json,"","").run(new DefaultApplicationArguments());
    }
    @Test void goalEditInvalidatesDerivedArtifactsAndSnapshotsAtomically() {
        UUID owner=account().userId();var workspace=workspace(owner);var goal=workspaces.createGoal(owner,workspace.id(),"解释不变性",List.of("独立回答一道新题"),key());var context=workspaces.createContext(owner,workspace.id());
        var artifact=document(owner,workspace.id(),context.references(),"按目标编写的解释");
        var changed=workspaces.updateGoal(owner,goal.id(),1,"比较协变数组与不变泛型",List.of("解释运行时与编译期区别"),"ACTIVE");assertEquals(2,changed.revision());assertEquals("STALE",artifacts.version(owner,artifact.id(),1).reviewStatus());
        assertEquals(409,assertThrows(DomainException.class,()->workspaces.readContext(owner,workspace.id(),context.id())).status());
        assertEquals(409,assertThrows(DomainException.class,()->workspaces.updateGoal(owner,goal.id(),1,"丢失更新",List.of("不应保存"),"ACTIVE")).status());
        assertEquals(changed,workspaces.goals(owner,workspace.id()).get(0));assertTrue(learning.list(owner,workspace.id()).isEmpty());
    }
    @Test void passiveTransitiveMemoryExpiryBlocksDownloadsAndNotebookActions() {
        UUID owner=account().userId();var workspace=workspace(owner);var memory=workspaces.stateMemory(owner,workspace.id(),"暂时使用的学习偏好",key());
        var context=workspaces.createContext(owner,workspace.id());var source=document(owner,workspace.id(),context.references(),"依赖记忆的源稿");var sourceVersion=artifacts.version(owner,source.id(),1);
        var sourceRef=new Reference("ARTIFACT",source.id(),1,sourceVersion.contentHash());var derived=document(owner,workspace.id(),List.of(sourceRef),"传递依赖源稿");
        var job=exports.create(owner,derived.id(),1,"MARKDOWN",key());exports.dispatchOnce();assertNotNull(exports.get(owner,job.id()).downloadPath());
        var notebook=artifacts.create(owner,workspace.id(),"INTERACTION","类型预测",null,key());
        var dsl=json.tree("{\"kind\":\"INTERACTION\",\"schemaVersion\":\"1.0\",\"blocks\":[{\"id\":\"flow\",\"kind\":\"TYPE_FLOW\",\"revision\":1,\"prompt\":\"预测类型关系\",\"conceptIds\":[],\"exerciseRef\":null,\"attemptId\":null,\"allowedActions\":[\"predict\"],\"evidenceRefs\":[],\"config\":{\"sourceType\":\"List<Integer>\",\"targetType\":\"List<Number>\",\"parameters\":[],\"operations\":[\"ASSIGN\"]}}]}");
        artifacts.save(owner,notebook.id(),0,dsl,List.of(sourceRef));
        // Expiry is passive: no explicit revoke call and no preemptive stale propagation.
        jdbc.update("UPDATE learner_memory SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)),memory.id().toString());
        assertEquals("DRAFT",artifacts.version(owner,derived.id(),1).reviewStatus());assertEquals("STALE",exports.get(owner,job.id()).sourceState());assertNull(exports.get(owner,job.id()).downloadPath());
        assertEquals(409,assertThrows(DomainException.class,()->exports.download(owner,job.id())).status());
        var action=json.tree("{\"artifactRevision\":1,\"blockId\":\"flow\",\"action\":\"predict\",\"expectedBlockRevision\":1,\"answer\":null,\"selection\":\"DENIED\"}");
        assertEquals(409,assertThrows(DomainException.class,()->actions.dispatch(owner,notebook.id(),action,key())).status());
    }
    @Test void missingAncestorIndexCannotBypassMemoryRevalidation() {
        UUID owner=account().userId();var workspace=workspace(owner);workspaces.stateMemory(owner,workspace.id(),"需要可撤回的来源",key());
        var first=document(owner,workspace.id(),workspaces.createContext(owner,workspace.id()).references(),"来源");var value=artifacts.version(owner,first.id(),1);var second=document(owner,workspace.id(),List.of(new Reference("ARTIFACT",first.id(),1,value.contentHash())),"派生");
        jdbc.update("DELETE FROM artifact_input_reference WHERE artifact_id=?",first.id().toString());jdbc.update("DELETE FROM artifact_input_reference WHERE artifact_id=? AND source_kind='MEMORY'",second.id().toString());
        assertEquals("LINEAGE_REBUILD_REQUIRED",assertThrows(DomainException.class,()->artifacts.validateCurrentInputs(owner,second.id(),1)).code());
    }
    @Test void oldRendererAndTamperedSourceFailWithoutRenderingUnderFalseIdentity() {
        UUID owner=account().userId();var workspace=workspace(owner);var first=document(owner,workspace.id(),List.of(),"版本固定");var old=exports.create(owner,first.id(),1,"MARKDOWN",key());
        jdbc.update("UPDATE export_job SET renderer_version='incompatible-old-renderer' WHERE id=?",old.id().toString());exports.dispatchOnce();assertEquals("FAILED",exports.get(owner,old.id()).status());assertEquals("RENDERER_VERSION_CHANGED",exports.get(owner,old.id()).errorCode());
        var second=document(owner,workspace.id(),List.of(),"不可变内容");var tampered=exports.create(owner,second.id(),1,"MARKDOWN",key());jdbc.update("UPDATE artifact_revision SET content_json='{}' WHERE artifact_id=? AND revision=1",second.id().toString());exports.dispatchOnce();
        assertEquals("FAILED",exports.get(owner,tampered.id()).status());assertEquals("ARTIFACT_INTEGRITY_FAILURE",exports.get(owner,tampered.id()).errorCode());assertEquals("CORRUPT",exports.get(owner,tampered.id()).sourceState());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM artifact_export_content WHERE export_id=?",Integer.class,tampered.id().toString()));
    }
    @Test void svgExportContainsNoExecutableMarkupAndRetainsFullLabels() throws Exception {
        UUID owner=account().userId();var workspace=workspace(owner);var artifact=artifacts.create(owner,workspace.id(),"DIAGRAM","关系 <svg>",null,key());
        var source=tree(Map.of("kind","DIAGRAM","schemaVersion","1.0","nodes",List.of(Map.of("id","a","label","<script>failure</script>","typeExpression","List<Integer>")),"edges",List.of()));
        var version=artifacts.save(owner,artifact.id(),0,source,List.of());var svg=renderer.render(artifact,version,"SVG").content();assertFalse(svg.contains("<script>"));assertTrue(svg.contains("&lt;script&gt;"));
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);var document=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));assertEquals("svg",document.getDocumentElement().getNodeName());
    }
    @Test void svgExportRejectsJsonLegalButXmlForbiddenTextWithoutPublishingBrokenBytes() {
        UUID owner=account().userId();var workspace=workspace(owner);var artifact=artifacts.create(owner,workspace.id(),"DIAGRAM","字符边界",null,key());
        var source=tree(Map.of("kind","DIAGRAM","schemaVersion","1.0","nodes",List.of(Map.of("id","a","label","control"+(char)1,"typeExpression","List<Integer>")),"edges",List.of()));
        artifacts.save(owner,artifact.id(),0,source,List.of());var job=exports.create(owner,artifact.id(),1,"SVG",key());exports.dispatchOnce();
        assertEquals("FAILED",exports.get(owner,job.id()).status());assertEquals("INVALID_XML_TEXT",exports.get(owner,job.id()).errorCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM artifact_export_content WHERE export_id=?",Integer.class,job.id().toString()));
    }
}
