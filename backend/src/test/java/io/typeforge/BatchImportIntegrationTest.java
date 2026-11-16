package io.typeforge;

import io.typeforge.identity.AccountClosureService;
import io.typeforge.identity.IdentityService;
import io.typeforge.ingestion.BatchImportModels.*;
import io.typeforge.ingestion.BatchImportService;
import io.typeforge.ingestion.IngestionModels.*;
import io.typeforge.ingestion.ManualIngestionService;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.shared.SchemaCatalog;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Timeout(60)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchImportIntegrationTest {
    private static final String PASSWORD="Batch-test-only-password-614!";
    @Autowired IdentityService identities;
    @Autowired BatchImportService batches;
    @Autowired ManualIngestionService ingestion;
    @Autowired AccountClosureService closure;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired SchemaCatalog schemas;
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {PrivateModuleDatabaseFixture.configure(properties,"batch-import");}
    @BeforeAll void databaseProof() {PrivateModuleDatabaseFixture.prove(jdbc,json,getClass().getSimpleName());}
    private static String key() {return UUID.randomUUID().toString();}
    private IdentityService.Session account(boolean editor) {
        var user=identities.register("batch-"+key(),PASSWORD);
        if(editor) jdbc.update("UPDATE app_user SET roles='[\"LEARNER\",\"EDITOR\"]' WHERE id=?",user.userId().toString());
        return user;
    }
    private Source source(UUID owner) {return ingestion.register(owner,new SourceInput("独立导入测试","https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html",key(),"测试中人工提供的正文，不抓取远端内容。"),key());}
    private static MockMultipartFile file(String name,String value) {return new MockMultipartFile("files",name,"application/octet-stream",value.getBytes(StandardCharsets.UTF_8));}
    private Batch preview(UUID owner,UUID source,String text) {return batches.preview(owner,source,"逐项人工确认",List.of(file("lesson.md",text)),key());}
    private static Selection select(Item item,String cleaned) {return new Selection(item.id(),cleaned,"人工清洗与来源核对测试");}
    private static void rejected(int status,Runnable work) {assertEquals(status,assertThrows(DomainException.class,work::run).status());}

    @Test void previewReportsIndependentFailuresAndDoesNotCreateOrPublishCaptures() {
        var owner=account(true);var source=source(owner.userId());
        var batch=batches.preview(owner.userId(),source.id(),"逐项检查",List.of(file("good.md","    List<T> values;"),file("scan.pdf","%PDF-1.7")),key());
        schemas.validate("ImportBatch",batch);assertEquals("PREVIEW",batch.status());assertEquals("READY",batch.items().get(0).status());assertEquals("FAILED",batch.items().get(1).status());assertEquals("IMPORT_FORMAT_UNSUPPORTED",batch.items().get(1).errorCode());
        assertEquals(0,ingestion.captures(owner.userId(),source.id(),100).size());assertEquals(json.sha256("    List<T> values;"),batch.items().get(0).originalHash());
        var summary=batches.list(owner.userId(),20).get(0);schemas.validate("ImportBatchSummary",summary);assertEquals(2,summary.itemCount());assertEquals(1,summary.readyCount());assertEquals(1,summary.failedCount());
    }
    @Test void selectedCommitIsImmutableIdempotentAndClearsOnlyTransientPreviewText() {
        var owner=account(true);var source=source(owner.userId());
        var batch=batches.preview(owner.userId(),source.id(),"确认选择",List.of(file("one.md","原文\r\n    A"),file("two.txt","暂不导入")),key());
        var input=new CommitInput(1,List.of(select(batch.items().get(0),"清洗\r\n    A")));String idem=key();
        var committed=batches.commit(owner.userId(),batch.id(),input,idem);schemas.validate("ImportBatch",committed);assertEquals("COMMITTED",committed.status());assertEquals(2,committed.revision());
        assertEquals("COMMITTED",committed.items().get(0).status());assertEquals("SKIPPED",committed.items().get(1).status());assertTrue(committed.items().stream().allMatch(i->i.originalMarkdown().isEmpty() && i.cleanedMarkdown().isEmpty()));
        var capture=ingestion.capture(owner.userId(),committed.items().get(0).captureId());assertEquals("原文\n    A",capture.originalMarkdown());assertEquals("清洗\n    A",capture.cleanedMarkdown());assertEquals(owner.userId(),capture.authorId());
        assertEquals(committed,batches.commit(owner.userId(),batch.id(),input,idem));assertEquals(1,ingestion.captures(owner.userId(),source.id(),100).size());
        rejected(409,()->batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(select(batch.items().get(0),"changed"))),idem));
    }
    @Test void sameFilesAcrossBatchesReuseVerifiedCaptureWithoutDuplicatingProvenance() {
        var owner=account(true);var source=source(owner.userId());var first=preview(owner.userId(),source.id(),"identical text");var second=preview(owner.userId(),source.id(),"identical text");
        var a=batches.commit(owner.userId(),first.id(),new CommitInput(1,List.of(select(first.items().get(0),"same clean"))),key());
        var b=batches.commit(owner.userId(),second.id(),new CommitInput(1,List.of(select(second.items().get(0),"same clean"))),key());
        assertEquals(a.items().get(0).captureId(),b.items().get(0).captureId());assertEquals(1,ingestion.captures(owner.userId(),source.id(),100).size());
    }
    @Test void previewReplayChecksBytesSourceAndMetadataBeforeReusingResult() {
        var owner=account(true);var source=source(owner.userId());String idem=key();var files=List.<org.springframework.web.multipart.MultipartFile>of(file("one.md","original"));
        var original=batches.preview(owner.userId(),source.id(),"note",files,idem);assertEquals(original.id(),batches.preview(owner.userId(),source.id(),"note",files,idem).id());
        rejected(409,()->batches.preview(owner.userId(),source.id(),"note",List.of(file("one.md","changed")),idem));rejected(409,()->batches.preview(owner.userId(),source.id(),"changed note",files,idem));
    }
    @Test void learnerAndForeignEditorCannotReadOrMutatePrivatePreview() {
        var owner=account(true);var other=account(true);var student=account(false);var source=source(owner.userId());var batch=preview(owner.userId(),source.id(),"private");
        rejected(403,()->batches.preview(student.userId(),source.id(),"note",List.of(file("one.md","text")),key()));rejected(403,()->batches.get(student.userId(),batch.id()));
        rejected(404,()->batches.get(other.userId(),batch.id()));rejected(404,()->batches.cancel(other.userId(),batch.id(),new CancelInput(1),key()));
        rejected(404,()->batches.commit(other.userId(),batch.id(),new CommitInput(1,List.of(select(batch.items().get(0),"stolen"))),key()));assertTrue(batches.list(other.userId(),20).isEmpty());
    }
    @Test void staleRevisionFailedForeignOrDuplicateSelectionsDoNotWriteAnyCapture() {
        var owner=account(true);var source=source(owner.userId());var batch=batches.preview(owner.userId(),source.id(),"note",List.of(file("one.md","text"),file("bad.pdf","data")),key());
        rejected(409,()->batches.commit(owner.userId(),batch.id(),new CommitInput(2,List.of(select(batch.items().get(0),"clean"))),key()));
        rejected(400,()->batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(select(batch.items().get(1),"invented"))),key()));
        rejected(400,()->batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(new Selection(UUID.randomUUID(),"foreign","note"))),key()));
        var selected=select(batch.items().get(0),"clean");rejected(400,()->batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(selected,selected)),key()));assertTrue(ingestion.captures(owner.userId(),source.id(),20).isEmpty());
    }
    @Test void secondCaptureIntegrityFailureRollsBackFirstCaptureAndWholeBatch() {
        var owner=account(true);var source=source(owner.userId());
        var damaged=ingestion.capture(owner.userId(),source.id(),new CaptureInput("second original","second cleaned","test fixture"),key());
        jdbc.update("UPDATE content_ingestion SET cleaned_markdown='tampered body' WHERE id=?",damaged.id().toString());
        var batch=batches.preview(owner.userId(),source.id(),"atomic",List.of(file("one.txt","first original"),file("two.txt","second original")),key());
        rejected(409,()->batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(select(batch.items().get(0),"first cleaned"),select(batch.items().get(1),"second cleaned"))),key()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM content_ingestion WHERE source_id=?",Integer.class,source.id().toString()));assertEquals("PREVIEW",batches.get(owner.userId(),batch.id()).status());assertEquals(0,batches.get(owner.userId(),batch.id()).items().stream().filter(i->i.captureId()!=null).count());
    }
    @Test void cancelIsIdempotentAndCannotBeFollowedByCommit() {
        var owner=account(true);var source=source(owner.userId());var batch=preview(owner.userId(),source.id(),"transient");String idem=key();
        var cancelled=batches.cancel(owner.userId(),batch.id(),new CancelInput(1),idem);assertEquals(cancelled,batches.cancel(owner.userId(),batch.id(),new CancelInput(1),idem));assertEquals("CANCELLED",cancelled.status());assertEquals("",cancelled.items().get(0).originalMarkdown());
        rejected(409,()->batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(select(batch.items().get(0),"clean"))),key()));
    }
    @Test void expiredPreviewsClearPayloadBeforeReadAndNeverCommit() {
        var owner=account(true);var source=source(owner.userId());var batch=preview(owner.userId(),source.id(),"expires");
        jdbc.update("UPDATE import_batch SET created_at=?,expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(90000)),Timestamp.from(Instant.now().minusSeconds(1)),batch.id().toString());
        var expired=batches.get(owner.userId(),batch.id());assertEquals("EXPIRED",expired.status());assertEquals(2,expired.revision());assertEquals("",expired.items().get(0).originalMarkdown());
        rejected(409,()->batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(select(batch.items().get(0),"clean"))),key()));
    }
    @Test void activePreviewQuotaCanBeRecoveredByCancellationButNotByReplay() {
        var owner=account(true);var source=source(owner.userId());var first=preview(owner.userId(),source.id(),"first");preview(owner.userId(),source.id(),"second");preview(owner.userId(),source.id(),"third");
        rejected(429,()->preview(owner.userId(),source.id(),"fourth"));batches.cancel(owner.userId(),first.id(),new CancelInput(1),key());assertNotNull(preview(owner.userId(),source.id(),"fourth"));assertEquals(4,batches.list(owner.userId(),20).size());
    }
    @Test void uploadCountByteSizeAndCleanedTextAreBoundedBeforePersistence() {
        var owner=account(true);var source=source(owner.userId());
        rejected(400,()->batches.preview(owner.userId(),source.id(),"note",java.util.Collections.nCopies(9,file("small.txt","x")),key()));
        rejected(413,()->batches.preview(owner.userId(),source.id(),"note",List.of(new MockMultipartFile("files","large.txt","text/plain",new byte[1_048_577])),key()));
        rejected(413,()->batches.preview(owner.userId(),source.id(),"note",java.util.Collections.nCopies(5,new MockMultipartFile("files","large.txt","text/plain",new byte[1_048_576])),key()));
        var batch=preview(owner.userId(),source.id(),"long text "+"x".repeat(16000));assertEquals("",batch.items().get(0).cleanedMarkdown());
        rejected(400,()->batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(select(batch.items().get(0),"x".repeat(16001)))),key()));assertEquals(1,batches.list(owner.userId(),20).size());
    }
    @Test void previewHashTamperingStopsReadsAndCommit() {
        var owner=account(true);var source=source(owner.userId());var batch=preview(owner.userId(),source.id(),"exact original");
        jdbc.update("UPDATE import_batch_item SET original_markdown='changed' WHERE id=?",batch.items().get(0).id().toString());rejected(409,()->batches.get(owner.userId(),batch.id()));
        rejected(409,()->batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(select(batch.items().get(0),"clean"))),key()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM content_ingestion WHERE source_id=?",Integer.class,source.id().toString()));
    }
    @Test void accountClosureCancelsPendingImportsButPreservesAttributedCommittedCaptures() {
        var owner=account(true);var other=account(true);var source=source(owner.userId());var batch=preview(owner.userId(),source.id(),"retained original");
        var committed=batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(select(batch.items().get(0),"retained cleaned"))),key());var pending=preview(owner.userId(),source.id(),"private transient");var foreign=preview(other.userId(),source.id(),"other editor");
        closure.close(owner.userId(),PASSWORD,owner.username());assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",String.class,pending.id().toString()));assertEquals("",jdbc.queryForObject("SELECT original_markdown FROM import_batch_item WHERE batch_id=?",String.class,pending.id().toString()));
        assertEquals("retained original",ingestion.capture(other.userId(),committed.items().get(0).captureId()).originalMarkdown());assertEquals("PREVIEW",batches.get(other.userId(),foreign.id()).status());rejected(403,()->batches.get(owner.userId(),batch.id()));
    }
    @Test void commitAndCancelRaceHaveExactlyOneTerminalWinner() throws Exception {
        var owner=account(true);var source=source(owner.userId());var batch=preview(owner.userId(),source.id(),"race");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{start.await();try {batches.commit(owner.userId(),batch.id(),new CommitInput(1,List.of(select(batch.items().get(0),"race clean"))),key());return 200;}catch(DomainException failure){return failure.status();}});
            var b=pool.submit(()->{start.await();try {batches.cancel(owner.userId(),batch.id(),new CancelInput(1),key());return 200;}catch(DomainException failure){return failure.status();}});
            start.countDown();var statuses=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertEquals(1,statuses.stream().filter(x->x==200).count());assertEquals(1,statuses.stream().filter(x->x==409).count());
            var result=batches.get(owner.userId(),batch.id());assertEquals(2,result.revision());assertEquals(result.status().equals("COMMITTED")?1:0,ingestion.captures(owner.userId(),source.id(),20).size());
        } finally {pool.shutdownNow();}
    }
    @Test void busyAdmissionRejectsBeforeReadingOrCopyingAnotherUploadedPayload() throws Exception {
        var owner=account(true);var source=source(owner.userId());var opened=new CountDownLatch(2);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        class BlockingFile extends MockMultipartFile {
            BlockingFile() {super("files","bounded.md","text/markdown",new byte[]{'x'});}
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                reads.incrementAndGet();opened.countDown();
                try {if(!release.await(15,TimeUnit.SECONDS))throw new java.io.IOException("test fixture deadline");}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new java.io.IOException("test fixture interrupted",interrupted);}
                return super.getInputStream();
            }
        }
        try {
            var a=pool.submit(()->batches.preview(owner.userId(),source.id(),"bounded-a",List.of(new BlockingFile()),key()));
            var b=pool.submit(()->batches.preview(owner.userId(),source.id(),"bounded-b",List.of(new BlockingFile()),key()));
            assertTrue(opened.await(10,TimeUnit.SECONDS));
            var failure=assertThrows(DomainException.class,()->batches.preview(owner.userId(),source.id(),"must-not-read",List.of(new BlockingFile()),key()));assertEquals("IMPORT_PARSER_BUSY",failure.code());assertEquals(2,reads.get());
            release.countDown();assertNotNull(a.get(15,TimeUnit.SECONDS));assertNotNull(b.get(15,TimeUnit.SECONDS));
            assertNotNull(preview(owner.userId(),source.id(),"permits released"));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void realMultipartSessionRequiresCsrfAndEditorialRoleThenConfirmsSeparately() throws Exception {
        var owner=account(true);var source=source(owner.userId());var session=HttpSessionFixture.login(mvc,json,owner.username(),PASSWORD);
        assertEquals(403,mvc.perform(multipart("/api/v1/admin/import-batches").file(file("note.md","HTTP original")).session(session).param("sourceId",source.id().toString()).param("processingNote","HTTP preview").header("Idempotency-Key",key())).andReturn().getResponse().getStatus());
        var response=mvc.perform(multipart("/api/v1/admin/import-batches").file(file("C:\\private\\note.md","HTTP original")).session(session).with(csrf()).param("sourceId",source.id().toString()).param("processingNote","HTTP preview").header("Idempotency-Key",key())).andReturn().getResponse();assertEquals(201,response.getStatus(),response.getContentAsString());
        var dto=json.tree(response.getContentAsString());UUID batch=UUID.fromString(dto.path("id").asText()),item=UUID.fromString(dto.path("items").get(0).path("id").asText());assertEquals("note.md",dto.path("items").get(0).path("fileName").asText());
        var committed=mvc.perform(post("/api/v1/admin/import-batches/"+batch+"/commit").session(session).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(json.write(new CommitInput(1,List.of(new Selection(item,"HTTP cleaned","explicit confirm")))))).andReturn().getResponse();assertEquals(200,committed.getStatus(),committed.getContentAsString());assertEquals("COMMITTED",json.tree(committed.getContentAsString()).path("status").asText());
        var student=account(false);var studentSession=HttpSessionFixture.login(mvc,json,student.username(),PASSWORD);assertEquals(403,mvc.perform(get("/api/v1/admin/import-batches").session(studentSession)).andReturn().getResponse().getStatus());
    }
}
