package io.typeforge;

import io.typeforge.artifacts.ArtifactInvalidation;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.artifacts.AssetService;
import io.typeforge.artifacts.MediaInspector;
import io.typeforge.identity.AccountClosureService;
import io.typeforge.identity.IdentityService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc @ActiveProfiles({"test","demo"}) @Timeout(60)
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class PrivateAssetsIntegrationTest {
    private static final String PASSWORD="fixture-password-strong-123";
    @Autowired IdentityService identities;
    @Autowired AssetService assets;
    @Autowired MediaInspector inspector;
    @Autowired WorkspaceService workspaces;
    @Autowired ArtifactService artifacts;
    @Autowired ArtifactInvalidation invalidation;
    @Autowired EventJournal events;
    @Autowired AccountClosureService closure;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;
    @Autowired MockMvc mvc;
    @org.springframework.test.context.DynamicPropertySource
    static void explicitDatabase(org.springframework.test.context.DynamicPropertyRegistry properties) {PrivateModuleDatabaseFixture.configure(properties,"private-assets");}
    @org.junit.jupiter.api.BeforeAll
    void proveRealDatabaseWhenOptedIn() {PrivateModuleDatabaseFixture.prove(jdbc,json,getClass().getSimpleName());}
    private String key() {return UUID.randomUUID().toString();}
    private IdentityService.Session account() {return identities.register("asset-"+key(),PASSWORD);}
    private WorkspaceService.Workspace workspace(UUID owner) {return workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"私人素材验证","ON_DEMAND",key());}
    private byte[] image(String format) throws Exception {
        var image=new BufferedImage(12,8,BufferedImage.TYPE_INT_RGB);for(int x=0;x<12;x++) for(int y=0;y<8;y++) image.setRGB(x,y,(x*23471+y*132487)&0xffffff);
        var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,format,output));image.flush();return output.toByteArray();
    }
    private AssetService.Asset upload(UUID owner,UUID workspace,String filename,byte[] bytes,String idem) {return assets.upload(owner,workspace,filename,"application/octet-stream",new ByteArrayInputStream(bytes),idem);}
    private static byte[] wav() {
        int samples=800;var data=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+samples*2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples*2);return data.array();
    }
    @Test void decodesImagesAndParsesActualGeneratedAudioVideoFixtures() throws Exception {
        var png=inspector.inspect(image("png"));assertEquals("image/png",png.mimeType());assertEquals(12,png.width());assertEquals(8,png.height());
        assertEquals("image/jpeg",inspector.inspect(image("jpeg")).mimeType());assertEquals(100,inspector.inspect(wav()).durationMillis());
        var mp3=inspector.inspect(new ClassPathResource("media/tone.mp3").getInputStream().readAllBytes());assertEquals("audio/mpeg",mp3.mimeType());assertTrue(mp3.durationMillis()>=250 && mp3.durationMillis()<1000);
        var mp4=inspector.inspect(new ClassPathResource("media/clip.mp4").getInputStream().readAllBytes());assertEquals("video/mp4",mp4.mimeType());assertEquals(160,mp4.width());assertEquals(90,mp4.height());assertTrue(mp4.durationMillis()>=500 && mp4.durationMillis()<1000);
    }
    @Test void rejectsScriptImagesTruncatedFilesAndFalseMimeBeforeAnyPersistentWrite() throws Exception {
        var user=account();var space=workspace(user.userId());byte[] png=image("png");
        assertEquals(400,assertThrows(DomainException.class,()->upload(user.userId(),space.id(),"malicious.svg","<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8),key())).status());
        assertEquals(400,assertThrows(DomainException.class,()->upload(user.userId(),space.id(),"truncated.png",Arrays.copyOf(png,png.length-5),key())).status());
        assertEquals(400,assertThrows(DomainException.class,()->assets.upload(user.userId(),space.id(),"fake.jpg","image/jpeg",new ByteArrayInputStream(png),key())).status());
        byte[] broken=png.clone();broken[broken.length-1]^=1;assertEquals(400,assertThrows(DomainException.class,()->inspector.inspect(broken)).status());
        assertEquals(400,assertThrows(DomainException.class,()->inspector.inspect("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000padding".getBytes(StandardCharsets.UTF_8))).status());
        assertEquals(0,assets.list(user.userId(),space.id(),null,100).items().size());
    }
    @Test void privateUploadsReplayExactBytesAndRejectKeyReuseOrForeignScopes() throws Exception {
        var user=account();var other=account();var space=workspace(user.userId());byte[] png=image("png");String idem=key();
        var asset=upload(user.userId(),space.id(),"C:\\private\\教学.png",png,idem);assertEquals("教学.png",asset.filename());assertEquals(1,asset.revision());assertEquals(AssetService.sha256(png),asset.contentHash());
        assertEquals(asset.id(),upload(user.userId(),space.id(),"教学.png",png,idem).id());assertEquals(1,assets.usage(user.userId()).activeFiles());
        assertEquals(409,assertThrows(DomainException.class,()->upload(user.userId(),space.id(),"another.png",png,idem)).status());
        assertArrayEquals(png,assets.download(user.userId(),asset.id()).bytes());
        assertEquals(404,assertThrows(DomainException.class,()->assets.get(other.userId(),asset.id())).status());assertEquals(404,assertThrows(DomainException.class,()->assets.download(other.userId(),asset.id())).status());assertEquals(404,assertThrows(DomainException.class,()->assets.revoke(other.userId(),asset.id())).status());
        assertEquals(404,assertThrows(DomainException.class,()->upload(other.userId(),space.id(),"foreign.png",png,key())).status());
        var forged=new Reference("ASSET",asset.id(),1,"0".repeat(64));assertEquals(409,assertThrows(DomainException.class,()->workspaces.validateReferences(user.userId(),space.id(),List.of(forged))).status());
    }
    @Test void quotaUsesStoredBytesAndRevocationFreesActualPayload() throws Exception {
        var user=account();var space=workspace(user.userId());byte[] png=image("png");
        var bounded=new AssetService(jdbc,tx,workspaces,invalidation,events,json,clock,inspector,png.length+1L);
        var first=bounded.upload(user.userId(),space.id(),"first.png","image/png",new ByteArrayInputStream(png),key());
        assertEquals(png.length,bounded.usage(user.userId()).usedBytes());assertEquals(429,assertThrows(DomainException.class,()->bounded.upload(user.userId(),space.id(),"second.png","image/png",new ByteArrayInputStream(png),key())).status());
        bounded.revoke(user.userId(),first.id());bounded.revoke(user.userId(),first.id());assertEquals(0,bounded.usage(user.userId()).usedBytes());assertNull(jdbc.queryForObject("SELECT content_bytes FROM asset_payload WHERE asset_id=?",byte[].class,first.id().toString()));
        assertEquals(410,assertThrows(DomainException.class,()->bounded.download(user.userId(),first.id())).status());
        assertNotNull(bounded.upload(user.userId(),space.id(),"second.png","image/png",new ByteArrayInputStream(png),key()));
        assertEquals(413,assertThrows(DomainException.class,()->upload(user.userId(),space.id(),"huge.png",new byte[AssetService.MAX_FILE_BYTES+1],key())).status());
    }
    @Test void assetRevocationInvalidatesDirectAndTransitiveSourceRevisions() throws Exception {
        var user=account();var space=workspace(user.userId());var asset=upload(user.userId(),space.id(),"diagram.png",image("png"),key());
        Reference source=new Reference("ASSET",asset.id(),1,asset.contentHash());var picture=artifacts.create(user.userId(),space.id(),"IMAGE","学习插图",null,key());
        var first=artifacts.save(user.userId(),picture.id(),0,json.tree(json.write(Map.of("kind","IMAGE","schemaVersion","1.0","assetId",asset.id(),"alt","类型关系示意","purpose","DECORATIVE","factEvidenceRefs",List.of()))),List.of(source));
        var note=artifacts.create(user.userId(),space.id(),"DOCUMENT","关联说明",null,key());
        artifacts.save(user.userId(),note.id(),0,json.tree(json.write(Map.of("kind","DOCUMENT","schemaVersion","1.0","title","关联说明","blocks",List.of(Map.of("id","body","kind","PARAGRAPH","text","引用自己的插图","evidenceRefs",List.of()))))),List.of(new Reference("ARTIFACT",picture.id(),1,first.contentHash())));
        assets.revoke(user.userId(),asset.id());assertEquals("STALE",artifacts.version(user.userId(),picture.id(),1).reviewStatus());assertEquals("STALE",artifacts.version(user.userId(),note.id(),1).reviewStatus());
        assertEquals(409,assertThrows(DomainException.class,()->workspaces.validateReferences(user.userId(),space.id(),List.of(source))).status());
        assertTrue(workspaces.events(user.userId(),space.id(),0,100).stream().anyMatch(event->event.type().equals("ASSET_REVOKED")));
    }
    @Test void browserUploadDownloadsRangesWithSafeHeadersAndCannotBypassCsrf() throws Exception {
        var user=account();var space=workspace(user.userId());var session=HttpSessionFixture.login(mvc,json,user.username(),PASSWORD);byte[] bytes=wav();var file=new MockMultipartFile("file","example.wav","audio/wav",bytes);
        assertEquals(403,mvc.perform(multipart("/api/v1/workspaces/"+space.id()+"/assets").file(file).session(session).header("Idempotency-Key",key())).andReturn().getResponse().getStatus());
        var response=mvc.perform(multipart("/api/v1/workspaces/"+space.id()+"/assets").file(file).session(session).with(csrf()).header("Idempotency-Key",key())).andReturn().getResponse();assertEquals(201,response.getStatus(),response.getContentAsString());String id=json.tree(response.getContentAsString()).path("id").asText();
        var range=mvc.perform(get("/api/v1/assets/"+id+"/download").session(session).header("Range","bytes=0-15")).andReturn().getResponse();assertEquals(206,range.getStatus());assertArrayEquals(Arrays.copyOf(bytes,16),range.getContentAsByteArray());assertEquals("bytes 0-15/"+bytes.length,range.getHeader("Content-Range"));assertEquals("private, no-store",range.getHeader("Cache-Control"));assertEquals("nosniff",range.getHeader("X-Content-Type-Options"));
        var suffix=mvc.perform(get("/api/v1/assets/"+id+"/download").session(session).header("Range","bytes=-8")).andReturn().getResponse();assertEquals(206,suffix.getStatus());assertArrayEquals(Arrays.copyOfRange(bytes,bytes.length-8,bytes.length),suffix.getContentAsByteArray());
        assertEquals(416,mvc.perform(get("/api/v1/assets/"+id+"/download").session(session).header("Range","bytes=999999999-")).andReturn().getResponse().getStatus());
        assertEquals(416,mvc.perform(get("/api/v1/assets/"+id+"/download").session(session).header("Range","bytes=0-4,8-9")).andReturn().getResponse().getStatus());
    }
    @Test void paginationAndIntegrityChecksRetainNoRevokedDownloadPath() throws Exception {
        var user=account();var space=workspace(user.userId());var otherSpace=workspace(user.userId());byte[] png=image("png");
        var one=upload(user.userId(),space.id(),"one.png",png,key());var two=upload(user.userId(),space.id(),"two.png",png,key());
        var page=assets.list(user.userId(),space.id(),null,1);assertEquals(1,page.items().size());assertNotNull(page.nextCursor());var next=assets.list(user.userId(),space.id(),page.nextCursor(),1);assertEquals(1,next.items().size());assertNull(next.nextCursor());assertNotEquals(page.items().get(0).id(),next.items().get(0).id());
        assertEquals(400,assertThrows(DomainException.class,()->assets.list(user.userId(),otherSpace.id(),one.id(),1)).status());
        jdbc.update("UPDATE asset_payload SET content_bytes=? WHERE asset_id=?",image("jpeg"),two.id().toString());assertEquals(503,assertThrows(DomainException.class,()->assets.download(user.userId(),two.id())).status());
        assets.revoke(user.userId(),one.id());assertNull(assets.get(user.userId(),one.id()).downloadPath());assertEquals(1,assets.list(user.userId(),space.id(),null,100).items().size());
    }
    @Test void closingAccountRemovesItsUploadedBytesIncludingArchivedSpacesAndPreservesOtherUsers() throws Exception {
        var user=account();var other=account();var a=workspace(user.userId());var archived=workspace(user.userId());var b=workspace(other.userId());byte[] png=image("png");
        var owned=upload(user.userId(),a.id(),"private.png",png,key());var old=upload(user.userId(),archived.id(),"archived.png",png,key());var foreign=upload(other.userId(),b.id(),"other.png",png,key());
        jdbc.update("UPDATE learning_workspace SET status='ARCHIVED' WHERE id=?",archived.id().toString());closure.close(user.userId(),PASSWORD,user.username());
        assertNull(jdbc.queryForObject("SELECT content_bytes FROM asset_payload WHERE asset_id=?",byte[].class,owned.id().toString()));assertNull(jdbc.queryForObject("SELECT content_bytes FROM asset_payload WHERE asset_id=?",byte[].class,old.id().toString()));assertArrayEquals(png,assets.download(other.userId(),foreign.id()).bytes());
    }
}
