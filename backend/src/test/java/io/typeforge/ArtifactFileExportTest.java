package io.typeforge;

import io.typeforge.artifacts.*;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.shared.*;
import io.typeforge.workspace.WorkspaceService;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Reopens actual native bytes; verifies cancellation/lineage across render and commit boundaries. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:native-files;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH")
@ActiveProfiles({"test","demo"}) @Timeout(60)
class ArtifactFileExportTest {
    @Autowired ArtifactService artifacts; @Autowired ArtifactExportService exports; @Autowired AssetService assets;
    @Autowired WorkspaceService workspaces; @Autowired JdbcTemplate jdbc; @Autowired JsonCodec json;
    @MockitoSpyBean ArtifactFileRenderer renderer;
    UUID owner,workspace;
    static String key(){return UUID.randomUUID().toString();}
    @BeforeEach void scope(){owner=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",owner.toString(),"file-"+owner,"fixture-not-login");workspace=workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"真实文件导出","QUIET",key()).id();}
    ArtifactService.Artifact document(String text){var a=artifacts.create(owner,workspace,"DOCUMENT","泛型与类型安全",null,key());artifacts.save(owner,a.id(),0,json.tree(json.write(Map.of("kind","DOCUMENT","schemaVersion","1.0","title",a.title(),"blocks",List.of(Map.of("id","main","kind","PARAGRAPH","text",text,"evidenceRefs",List.of()))))),List.of());return artifacts.get(owner,a.id());}
    ArtifactExportService.Download file(ArtifactService.Artifact a,String format){var job=exports.create(owner,a.id(),a.currentRevision(),format,key());exports.dispatchOnce();var done=exports.get(owner,job.id());assertEquals("SUCCEEDED",done.status(),done.errorCode());var download=exports.download(owner,job.id());assertEquals(ArtifactFileRenderer.sha256(download.bytes()),download.contentHash());return download;}
    @Test void docxRoundTripKeepsEditableChineseTextAndSourceMetadata() throws Exception {
        var a=document("PECS：生产者使用 extends，消费者使用 super。\nList<String> 不是 List<Object> 的子类型。");
        var bytes=file(a,"DOCX");assertTrue(bytes.mimeType().endsWith("wordprocessingml.document"));
        try(var doc=new XWPFDocument(new ByteArrayInputStream(bytes.bytes()))){String text=doc.getParagraphs().stream().map(p->p.getText()).reduce("",(x,y)->x+"\n"+y);assertTrue(text.contains("生产者使用 extends"));assertTrue(text.contains("List<String>"));assertTrue(text.contains(artifacts.version(owner,a.id(),1).contentHash()));assertEquals(a.title(),doc.getProperties().getCoreProperties().getTitle());}
    }
    @Test void pdfRoundTripIsSearchableWithEmbeddedChineseFont() throws Exception {
        var a=document("类型安全 PECS 通配符：生产者与消费者。".repeat(240));
        try(var pdf=Loader.loadPDF(file(a,"PDF").bytes())){String text=new PDFTextStripper().getText(pdf);assertTrue(text.contains("类型安全"));assertTrue(text.contains("PECS"));assertTrue(pdf.getNumberOfPages()>=2);var fonts=pdf.getPage(0).getResources();assertTrue(fonts.getFont(fonts.getFontNames().iterator().next()).isEmbedded());}
    }
    AssetService.Asset image() throws Exception {var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(64,32,BufferedImage.TYPE_INT_RGB),"png",bytes);return assets.upload(owner,workspace,"示例.png","image/png",new ByteArrayInputStream(bytes.toByteArray()),key());}
    ArtifactService.Artifact slides(AssetService.Asset image){var a=artifacts.create(owner,workspace,"SLIDES","泛型图解",null,key());var elements=new ArrayList<Map<String,Object>>();elements.add(Map.of("id","text","kind","TEXT","x",0.05,"y",0.20,"width",0.43,"height",0.65,"text","List<?> 的边界","assetId",json.tree("null"),"alt",""));elements.add(Map.of("id","image","kind","IMAGE","x",0.52,"y",0.2,"width",0.43,"height",0.6,"text","","assetId",image.id(),"alt","用于检验真实图片嵌入的固定测试图"));artifacts.save(owner,a.id(),0,json.tree(json.write(Map.of("kind","SLIDES","schemaVersion","1.0","aspect","16:9","slides",List.of(Map.of("id","slide","title","泛型与通配符","elements",elements,"notes","实际演示备注与出处","evidenceRefs",List.of()))))),List.of(new Reference("ASSET",image.id(),image.revision(),image.contentHash())));return artifacts.get(owner,a.id());}
    @Test void pptxAndPdfAndHtmlActuallyEmbedAdmittedImages() throws Exception {
        var a=slides(image());
        try(var ppt=new XMLSlideShow(new ByteArrayInputStream(file(a,"PPTX").bytes()))){assertEquals(1,ppt.getSlides().size());assertEquals(1,ppt.getPictureData().size());assertTrue(ppt.getSlides().get(0).getShapes().stream().anyMatch(s->s instanceof org.apache.poi.xslf.usermodel.XSLFPictureShape));assertTrue(ppt.getNotesSlide(ppt.getSlides().get(0)).getShapes().stream().anyMatch(s->s instanceof org.apache.poi.xslf.usermodel.XSLFTextShape t && t.getText().contains("实际演示")));}
        try(var pdf=Loader.loadPDF(file(a,"PDF").bytes())){assertEquals(1,pdf.getNumberOfPages());assertTrue(pdf.getPage(0).getResources().getXObjectNames().iterator().hasNext());assertTrue(new PDFTextStripper().getText(pdf).contains("通配符"));}
        String html=new String(file(a,"HTML").bytes(),StandardCharsets.UTF_8);assertTrue(html.contains("data:image/png;base64,"));assertTrue(html.contains("img-src data:"));assertFalse(html.contains("src=\"http"));
    }
    @Test void revokedImageInvalidatesExistingExportAndCannotBeReattached() throws Exception {
        var image=image();var a=slides(image);var job=exports.create(owner,a.id(),1,"PPTX",key());exports.dispatchOnce();assertNotNull(exports.get(owner,job.id()).downloadPath());assets.revoke(owner,image.id());assertNull(exports.get(owner,job.id()).downloadPath());assertThrows(DomainException.class,()->exports.download(owner,job.id()));assertThrows(DomainException.class,()->slides(image));
    }
    @Test void cancelWhileRendererIsRunningWinsWithoutHoldingTheWorkspaceLock() throws Exception {
        var a=document("取消后的文件不能迟到提交");var job=exports.create(owner,a.id(),1,"DOCX",key());var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{started.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return call.callRealMethod();}).when(renderer).render(eq(owner),any(),any(),eq("DOCX"));
        var executor=Executors.newSingleThreadExecutor();
        try{var task=executor.submit(exports::dispatchOnce);assertTrue(started.await(10,TimeUnit.SECONDS));assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->assertEquals("CANCELLED",exports.cancel(owner,job.id()).status()));release.countDown();task.get(15,TimeUnit.SECONDS);assertEquals("CANCELLED",exports.get(owner,job.id()).status());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM artifact_export_binary WHERE export_id=?",Integer.class,job.id().toString()));}finally{release.countDown();executor.shutdownNow();reset(renderer);}
    }
    @Test void abandonedRenderLeaseFailsDurablyWithoutInventingFile() {
        var a=document("过期任务必须可观察");var job=exports.create(owner,a.id(),1,"PDF",key());jdbc.update("UPDATE export_job SET status='RUNNING',lease_token=?,lease_until=? WHERE id=?",key(),Timestamp.from(Instant.now().minusSeconds(1)),job.id().toString());exports.dispatchOnce();assertEquals("FAILED",exports.get(owner,job.id()).status());assertNull(exports.get(owner,job.id()).downloadPath());
    }
    @Test void editedSourceDuringRenderingRejectsLateOutput() throws Exception {
        var a=document("原始稿");var job=exports.create(owner,a.id(),1,"DOCX",key());
        doAnswer(call->{var current=artifacts.version(owner,a.id(),1);artifacts.save(owner,a.id(),1,current.content(),current.inputRefs());return call.callRealMethod();}).when(renderer).render(eq(owner),any(),any(),eq("DOCX"));
        try{exports.dispatchOnce();assertEquals("FAILED",exports.get(owner,job.id()).status());assertNull(exports.get(owner,job.id()).downloadPath());}finally{reset(renderer);}
    }
    @Test void identicalExportsReuseBytesAndObsoleteCacheIsReclaimed() {
        var a=document("可重建缓存");var first=exports.create(owner,a.id(),1,"DOCX",key());exports.dispatchOnce();
        assertEquals(first.id(),exports.create(owner,a.id(),1,"DOCX",key()).id());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM artifact_export_binary WHERE export_id=?",Integer.class,first.id().toString()));
        var old=artifacts.version(owner,a.id(),1);artifacts.save(owner,a.id(),1,old.content(),old.inputRefs());exports.create(owner,a.id(),2,"DOCX",key());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM artifact_export_binary WHERE export_id=?",Integer.class,first.id().toString()));
        assertEquals("SUPERSEDED",exports.get(owner,first.id()).sourceState());
    }
    @Test void editedDocumentTitleAppearsInActualNativeFile() throws Exception {
        var a=document("正文");var old=artifacts.version(owner,a.id(),1);var source=old.content().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)source).put("title","学习者修改的新标题");artifacts.save(owner,a.id(),1,source,old.inputRefs());
        try(var doc=new XWPFDocument(new ByteArrayInputStream(file(artifacts.get(owner,a.id()),"DOCX").bytes()))){assertEquals("学习者修改的新标题",doc.getProperties().getCoreProperties().getTitle());assertEquals("学习者修改的新标题",doc.getParagraphs().get(0).getText());}
    }
    @Test void repeatedEmbeddedImagesAreRejectedBeforeUnboundedHtmlAllocation() throws Exception {
        var bitmap=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);var random=new Random(391);for(int y=0;y<512;y++)for(int x=0;x<512;x++)bitmap.setRGB(x,y,random.nextInt());
        var bytes=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",bytes);var image=assets.upload(owner,workspace,"noise.png","image/png",new ByteArrayInputStream(bytes.toByteArray()),key());
        var a=slides(image);var version=artifacts.version(owner,a.id(),1);var source=version.content().deepCopy();var elements=(com.fasterxml.jackson.databind.node.ArrayNode)source.path("slides").get(0).path("elements");
        var original=elements.get(1);for(int i=0;i<12;i++){var copy=original.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)copy).put("id","copy-"+i);elements.add(copy);}
        artifacts.save(owner,a.id(),1,source,version.inputRefs());var job=exports.create(owner,a.id(),2,"HTML",key());exports.dispatchOnce();assertEquals("EXPORT_TOO_LARGE",exports.get(owner,job.id()).errorCode());assertNull(exports.get(owner,job.id()).downloadPath());
    }

}
