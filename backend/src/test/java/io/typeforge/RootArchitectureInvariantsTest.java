package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.artifacts.ArtifactService;
import io.typeforge.exercise.LearningService;
import io.typeforge.operations.events.DomainEventConsumer;
import io.typeforge.operations.events.EventJournal;
import io.typeforge.operations.events.OutboxDispatcher;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import io.typeforge.workspace.WorkspaceService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-module invariants deliberately exercise real rows, rollback, duplicate delivery and bad references. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:typeforge-invariants;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH")
@ActiveProfiles("test")
@Timeout(60)
class RootArchitectureInvariantsTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JsonCodec json;
    @Autowired WorkspaceService workspaces;
    @Autowired ArtifactService artifacts;
    @Autowired LearningService learning;
    @Autowired EventJournal journal;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired List<DomainEventConsumer> consumers;
    private UUID owner;
    private UUID workspace;
    private UUID release;
    private UUID concept;
    private UUID exercise;
    private UUID family;
    private boolean isolateFanoutDeliveries;

    @BeforeEach void fixture() {
        owner=addUser(); release=UUID.randomUUID();concept=UUID.randomUUID();family=UUID.randomUUID();
        jdbc.update("INSERT INTO content_release(id,version,status,manifest_hash,created_by,published_at) VALUES(?,?,?,?,?,?)",release.toString(),"invariant-"+release.toString().substring(0,16),"PUBLISHED",json.hash(release),owner.toString(),Timestamp.from(Instant.now()));
        workspace=workspaces.create(owner,release,"Invariant workspace","ON_DEMAND",key()).id();
        jdbc.update("INSERT INTO concept(id,code,title) VALUES(?,?,?)",concept.toString(),"I"+concept.toString().replace("-","").substring(0,14),"Invariant concept");
        jdbc.update("INSERT INTO release_concept(release_id,concept_id,title,prerequisite_ids) VALUES(?,?,?,'[]')",release.toString(),concept.toString(),"Invariant concept");
        exercise=addExercise(family);
    }
    @AfterEach void discardFanoutTransportFixtures() {
        if(!isolateFanoutDeliveries) return;
        // Keep every business row and source event. A large test's transport queue must not become
        // another method's fixture; the direct fanout test fully exercises delivery before cleanup.
        tx.executeWithoutResult(status->{
            jdbc.update("DELETE FROM event_receipt WHERE event_id IN (SELECT id FROM outbox_event WHERE aggregate_id=?)",workspace.toString());
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id=?",workspace.toString());
        });
    }
    private UUID addUser() {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,roles) VALUES(?,?,?,'[\"LEARNER\"]')",id.toString(),"inv-"+id,"fixture-is-not-a-login-credential");
        return id;
    }
    private UUID addExercise(UUID familyId) {
        UUID id=UUID.randomUUID();
        var spec=Map.of("optionIds",List.of("right"),"options",List.of(Map.of("id","right","text","Accept"),Map.of("id","wrong","text","Reject")),"explanation","The reviewed fixture has an explicit objective key.","misconceptions",List.of("G-TEST"));
        jdbc.update("INSERT INTO exercise(id,revision,family_id,kind,difficulty,prompt,answer_spec,rubric_version,status,author_id) VALUES(?,1,?,'SINGLE',1,?,?,'fixture-v1','REVIEWED',?)",id.toString(),familyId.toString(),"Choose the reviewed fixture answer",json.write(spec),owner.toString());
        jdbc.update("INSERT INTO exercise_concept(exercise_id,exercise_revision,concept_id,weight) VALUES(?,1,?,1)",id.toString(),concept.toString());
        jdbc.update("INSERT INTO release_exercise(release_id,exercise_id,exercise_revision) VALUES(?,?,1)",release.toString(),id.toString());
        return id;
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private JsonNode answer(String option) { return json.tree(json.write(Map.of("kind","CHOICE","optionIds",List.of(option)))); }
    private JsonNode document(String text) {
        return json.tree(json.write(Map.of("kind","DOCUMENT","schemaVersion","1.0","title","Invariant document","blocks",List.of(Map.of("id","paragraph","kind","PARAGRAPH","text",text,"evidenceRefs",List.of())))));
    }
    private ArtifactService.Artifact documentArtifact(String title) { return artifacts.create(owner,workspace,"DOCUMENT",title,null,key()); }
    private Reference reference(ArtifactService.Version version) { return new Reference("ARTIFACT",version.artifactId(),version.revision(),version.contentHash()); }
    private Map<String,Object> block(String kind,Map<String,Object> config) {
        var value=new LinkedHashMap<String,Object>();
        value.put("id","block-one");value.put("kind",kind);value.put("revision",1);value.put("prompt","Check references");
        value.put("conceptIds",List.of());value.put("exerciseRef",null);value.put("attemptId",null);value.put("allowedActions",List.of("openEvidence"));value.put("evidenceRefs",List.of());value.put("config",config);
        return value;
    }
    private JsonNode interaction(Map<String,Object> block) { return json.tree(json.write(Map.of("kind","INTERACTION","schemaVersion","1.0","blocks",List.of(block)))); }
    private ArtifactService.Artifact interactionArtifact() { return artifacts.create(owner,workspace,"INTERACTION","Reference fixture",null,key()); }
    private void drain() { for(int i=0;i<10;i++) if(dispatcher.dispatchBatch(100)==0) break; }

    @Test void memoryRevocationInvalidatesTransitiveArtifactsAndOldSnapshotInTheSameTransaction() {
        String privatePreference="Keep this learner preference private";
        var memory=workspaces.stateMemory(owner,workspace,privatePreference,key());
        Reference memoryRef=new Reference("MEMORY",memory.id(),memory.revision(),json.hash(Map.of("summary",privatePreference)));
        var first=documentArtifact("Based on memory");
        var firstVersion=artifacts.save(owner,first.id(),0,document("Derived source"),List.of(memoryRef));
        var second=documentArtifact("Based on first document");
        var secondVersion=artifacts.save(owner,second.id(),0,document("Transitive derivative"),List.of(reference(firstVersion)));
        var independent=documentArtifact("Independent");
        artifacts.save(owner,independent.id(),0,document("Independent source"),List.of());
        var context=workspaces.createContext(owner,workspace);
        workspaces.validateSnapshot(owner,workspace,context.id());
        workspaces.revokeMemory(owner,workspace,memory.id(),memory.revision());
        assertEquals("STALE",artifacts.version(owner,first.id(),firstVersion.revision()).reviewStatus());
        assertEquals("STALE",artifacts.version(owner,second.id(),secondVersion.revision()).reviewStatus());
        assertEquals("DRAFT",artifacts.version(owner,independent.id(),1).reviewStatus());
        assertThrows(DomainException.class,()->workspaces.validateSnapshot(owner,workspace,context.id()));
        assertEquals("{}",json.tree(jdbc.queryForObject("SELECT content_json FROM learner_memory WHERE id=?",String.class,memory.id().toString())).toString());
        assertFalse(jdbc.queryForList("SELECT payload FROM workspace_event WHERE workspace_id=?",String.class,workspace.toString()).stream().anyMatch(payload->payload.contains(privatePreference)));
    }

    @Test void cyclicDerivationIsRejectedWithoutCreatingAnImmediatelyStaleRevision() {
        var first=documentArtifact("First");
        var a1=artifacts.save(owner,first.id(),0,document("A1"),List.of());
        var second=documentArtifact("Second");
        var b1=artifacts.save(owner,second.id(),0,document("B1 from A1"),List.of(reference(a1)));
        assertThrows(DomainException.class,()->artifacts.save(owner,first.id(),1,document("A2 from B1"),List.of(reference(b1))));
        assertEquals(1,artifacts.get(owner,first.id()).currentRevision());
        assertEquals("DRAFT",artifacts.version(owner,second.id(),1).reviewStatus());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM artifact_revision WHERE artifact_id=?",Integer.class,first.id().toString()));
    }

    @Test void directFanoutAdmissionStopsBeforeAStoredMemoryCanBecomeUnrevocable() {
        isolateFanoutDeliveries=true;
        String summary="Bounded direct fanout";
        var memory=workspaces.stateMemory(owner,workspace,summary,key());
        var ref=new Reference("MEMORY",memory.id(),memory.revision(),json.hash(Map.of("summary",summary)));
        for(int i=0;i<256;i++) {
            var artifact=documentArtifact("Dependent "+i);
            artifacts.save(owner,artifact.id(),0,document("Bounded dependent"),List.of(ref));
        }
        var overflow=documentArtifact("Must remain an unsaved draft");
        assertEquals("LINEAGE_LIMIT",assertThrows(DomainException.class,()->artifacts.save(owner,overflow.id(),0,document("Overflow"),List.of(ref))).code());
        assertEquals(0,artifacts.get(owner,overflow.id()).currentRevision());
        assertEquals(256,activeDescendants("MEMORY",memory.id()));
        workspaces.revokeMemory(owner,workspace,memory.id(),memory.revision());
        assertEquals("REVOKED",workspaces.memories(owner,workspace).get(0).status());
        assertEquals(0,activeDescendants("MEMORY",memory.id()));
        assertEquals(256,jdbc.queryForObject("SELECT COUNT(*) FROM artifact_revision r JOIN learning_artifact a ON a.id=r.artifact_id WHERE a.workspace_id=? AND r.review_status='STALE'",Integer.class,workspace.toString()));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND published_at IS NULL",Integer.class,workspace.toString())>=770);
        drain();
        var projection=workspaces.projection(owner,workspace);
        assertTrue(projection.caughtUp());
        assertEquals(projection.sourceSequence(),projection.processedSequence());
    }

    @Test void indirectFanoutCannotEvadeTheAncestorBoundAndReplacingItsRootFreesCapacity() {
        isolateFanoutDeliveries=true;
        String summary="Bounded inherited fanout";
        var memory=workspaces.stateMemory(owner,workspace,summary,key());
        var ref=new Reference("MEMORY",memory.id(),memory.revision(),json.hash(Map.of("summary",summary)));
        var root=documentArtifact("Root source");
        var rootVersion=artifacts.save(owner,root.id(),0,document("First root"),List.of(ref));
        for(int i=0;i<255;i++) {
            var child=documentArtifact("Indirect child "+i);
            artifacts.save(owner,child.id(),0,document("Inherited source"),List.of(reference(rootVersion)));
        }
        var overflow=documentArtifact("Indirect overflow");
        assertEquals("LINEAGE_LIMIT",assertThrows(DomainException.class,()->artifacts.save(owner,overflow.id(),0,document("Must reject"),List.of(reference(rootVersion)))).code());
        assertEquals(256,activeDescendants("MEMORY",memory.id()));
        // The same transaction will stale 255 children, so those revisions must not block this save.
        var replacement=artifacts.save(owner,root.id(),1,document("Updated root"),List.of(ref));
        assertEquals(2,activeDescendants("MEMORY",memory.id()));
        assertEquals(255,jdbc.queryForObject("SELECT COUNT(*) FROM artifact_revision r JOIN learning_artifact a ON a.id=r.artifact_id WHERE a.workspace_id=? AND r.review_status='STALE'",Integer.class,workspace.toString()));
        var leaf=artifacts.save(owner,overflow.id(),0,document("Capacity now available"),List.of(reference(replacement)));
        assertEquals(3,activeDescendants("MEMORY",memory.id()));
        // A transitive entry remains sufficient even if an intermediate revision is already STALE.
        jdbc.update("UPDATE artifact_revision SET review_status='STALE' WHERE artifact_id=? AND revision=2",root.id().toString());
        workspaces.revokeMemory(owner,workspace,memory.id(),memory.revision());
        assertEquals("STALE",artifacts.version(owner,leaf.artifactId(),leaf.revision()).reviewStatus());
        assertEquals(0,activeDescendants("MEMORY",memory.id()));
    }

    @Test void mergedAncestorPathsDeduplicateSourcesAndRejectConflictingVersionsExplicitly() {
        String summary="One source on two paths";
        var memory=workspaces.stateMemory(owner,workspace,summary,key());
        var ref=new Reference("MEMORY",memory.id(),memory.revision(),json.hash(Map.of("summary",summary)));
        var first=documentArtifact("First path");var second=documentArtifact("Second path");
        var a=artifacts.save(owner,first.id(),0,document("First"),List.of(ref));
        var b=artifacts.save(owner,second.id(),0,document("Second"),List.of(ref));
        var merged=documentArtifact("Merged");
        var result=artifacts.save(owner,merged.id(),0,document("Both paths"),List.of(reference(a),reference(b)));
        assertEquals(List.of(reference(a),reference(b)),result.inputRefs(),"The public source manifest preserves direct-input semantics");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM artifact_input_reference WHERE artifact_id=? AND artifact_revision=1 AND source_kind='MEMORY' AND source_id=?",Integer.class,merged.id().toString(),memory.id().toString()));
        assertEquals(3,activeDescendants("MEMORY",memory.id()));
        // Simulate an incompatible old source index; do not turn its primary-key collision into 503.
        jdbc.update("UPDATE artifact_input_reference SET source_revision=2 WHERE artifact_id=? AND source_kind='MEMORY' AND source_id=?",second.id().toString(),memory.id().toString());
        var rejected=documentArtifact("Conflicting ancestor versions");
        assertEquals("LINEAGE_VERSION_CONFLICT",assertThrows(DomainException.class,()->artifacts.save(owner,rejected.id(),0,document("No silent overwrite"),List.of(reference(a),reference(b)))).code());
        assertEquals(0,artifacts.get(owner,rejected.id()).currentRevision());
    }

    @Test void incompleteLegacyIndexIsRejectedWithoutClaimingPartialRevocationSucceeded() {
        String summary="Legacy direct-only fixture";
        var memory=workspaces.stateMemory(owner,workspace,summary,key());
        var ref=new Reference("MEMORY",memory.id(),memory.revision(),json.hash(Map.of("summary",summary)));
        var root=documentArtifact("Legacy root");var rootVersion=artifacts.save(owner,root.id(),0,document("Root"),List.of(ref));
        var child=documentArtifact("Legacy child");artifacts.save(owner,child.id(),0,document("Child"),List.of(reference(rootVersion)));
        jdbc.update("DELETE FROM artifact_input_reference WHERE artifact_id=? AND source_kind='MEMORY'",child.id().toString());
        assertEquals("LINEAGE_REBUILD_REQUIRED",assertThrows(DomainException.class,()->workspaces.revokeMemory(owner,workspace,memory.id(),memory.revision())).code());
        assertEquals("ACTIVE",workspaces.memories(owner,workspace).get(0).status());
        assertEquals("DRAFT",artifacts.version(owner,root.id(),1).reviewStatus());
        assertEquals("DRAFT",artifacts.version(owner,child.id(),1).reviewStatus());
    }

    @Test void oversizedLegacyGraphRequiresExplicitRepairInsteadOfUnboundedOrPartialMutation() {
        String summary="Legacy oversized fixture";
        var memory=workspaces.stateMemory(owner,workspace,summary,key());
        var ref=new Reference("MEMORY",memory.id(),memory.revision(),json.hash(Map.of("summary",summary)));
        var content=document("Imported pre-admission revision");
        var artifactRows=new ArrayList<Object[]>();var revisionRows=new ArrayList<Object[]>();var sourceRows=new ArrayList<Object[]>();
        for(int i=0;i<257;i++) {
            String id=UUID.randomUUID().toString();
            artifactRows.add(new Object[]{id,workspace.toString(),"Imported "+i});
            revisionRows.add(new Object[]{id,json.write(content),json.hash(content),json.write(List.of(ref))});
            sourceRows.add(new Object[]{workspace.toString(),id,ref.id().toString(),ref.revision(),ref.contentHash()});
        }
        tx.executeWithoutResult(status->{
            workspaces.lockOwned(owner,workspace);
            jdbc.batchUpdate("INSERT INTO learning_artifact(id,workspace_id,kind,title,current_revision) VALUES(?,?,'DOCUMENT',?,1)",artifactRows);
            jdbc.batchUpdate("INSERT INTO artifact_revision(artifact_id,revision,schema_version,content_json,content_hash,input_refs,review_status) VALUES(?,1,'1.0',?,?,?,'DRAFT')",revisionRows);
            jdbc.batchUpdate("INSERT INTO artifact_input_reference(workspace_id,artifact_id,artifact_revision,source_kind,source_id,source_revision,source_hash) VALUES(?,?,1,'MEMORY',?,?,?)",sourceRows);
        });
        var newArtifact=documentArtifact("Further expansion denied");
        assertEquals("LINEAGE_REBUILD_REQUIRED",assertThrows(DomainException.class,()->artifacts.save(owner,newArtifact.id(),0,document("Cannot expand legacy overflow"),List.of(ref))).code());
        assertEquals("LINEAGE_REBUILD_REQUIRED",assertThrows(DomainException.class,()->workspaces.revokeMemory(owner,workspace,memory.id(),memory.revision())).code());
        assertEquals(257,activeDescendants("MEMORY",memory.id()));
        assertEquals("ACTIVE",workspaces.memories(owner,workspace).get(0).status());
    }

    private int activeDescendants(String kind,UUID source) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM artifact_input_reference ref JOIN artifact_revision revision ON revision.artifact_id=ref.artifact_id AND revision.revision=ref.artifact_revision WHERE ref.workspace_id=? AND ref.source_kind=? AND ref.source_id=? AND revision.review_status<>'STALE'",Integer.class,workspace.toString(),kind,source.toString());
    }

    @Test void concurrentEditsPreserveTheLosingDraftInsteadOfOverwritingTheWinner() throws Exception {
        var artifact=documentArtifact("Concurrent");artifacts.save(owner,artifact.id(),0,document("Original"),List.of());
        var executor=Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> writes=List.of(()->saveIfCurrent(artifact.id(),"Author one"),()->saveIfCurrent(artifact.id(),"Author two"));
            var results=executor.invokeAll(writes);int accepted=0;
            for(var result:results) if(result.get()) accepted++;
            assertEquals(1,accepted);assertEquals(2,artifacts.get(owner,artifact.id()).currentRevision());
        } finally { executor.shutdownNow(); }
    }
    private boolean saveIfCurrent(UUID artifact,String text) {
        try { artifacts.save(owner,artifact,1,document(text),List.of());return true; }
        catch(DomainException conflict) { assertEquals(409,conflict.status());return false; }
    }

    @Test void sourceHashChecksTheActualSourceAndDoesNotAcceptPlausibleLookingHex() {
        var artifact=interactionArtifact();
        var config=Map.<String,Object>of("studentReason","Not supplied","originalSource","class Demo {}","sourceHash","a".repeat(64),"diagnosticIds",List.of());
        assertThrows(DomainException.class,()->artifacts.save(owner,artifact.id(),0,interaction(block("ERROR_CONTEXT",config)),List.of()));
        var valid=Map.<String,Object>of("studentReason","Not supplied","originalSource","class Demo {}","sourceHash",json.sha256("class Demo {}"),"diagnosticIds",List.of());
        assertEquals("DRAFT",artifacts.save(owner,artifact.id(),0,interaction(block("ERROR_CONTEXT",valid)),List.of()).reviewStatus());
    }

    @Test void embeddedArtifactAndReviewLinksCannotBypassTheTypedInputManifest() {
        var foreign=documentArtifact("Not admitted to manifest");artifacts.save(owner,foreign.id(),0,document("Private"),List.of());
        var artifact=interactionArtifact();
        var config=Map.<String,Object>of("reviewIds",List.of(),"artifactRefs",List.of(Map.of("id",foreign.id(),"revision",1)),"conceptIds",List.of());
        assertThrows(DomainException.class,()->artifacts.save(owner,artifact.id(),0,interaction(block("REVIEW_LINKS",config)),List.of()));
        var invalidReview=Map.<String,Object>of("reviewIds",List.of(UUID.randomUUID()),"artifactRefs",List.of(),"conceptIds",List.of());
        assertThrows(DomainException.class,()->artifacts.save(owner,artifact.id(),0,interaction(block("REVIEW_LINKS",invalidReview)),List.of()));
    }

    @Test void actualOtherLearnerReviewCannotBeLinkedByKnowingItsUuid() {
        UUID other=addUser();UUID otherWorkspace=workspaces.create(other,release,"Other learner","ON_DEMAND",key()).id();
        var attempt=learning.submit(other,otherWorkspace,release,exercise,1,answer("wrong"),key());drain();
        UUID review=learning.reviews(other).stream().filter(value->value.originAttemptId().equals(attempt.id())).findFirst().orElseThrow().id();
        var artifact=interactionArtifact();
        var config=Map.<String,Object>of("reviewIds",List.of(review),"artifactRefs",List.of(),"conceptIds",List.of());
        assertThrows(DomainException.class,()->artifacts.save(owner,artifact.id(),0,interaction(block("REVIEW_LINKS",config)),List.of()));
        assertEquals(0,artifacts.get(owner,artifact.id()).currentRevision());
    }

    @Test void counterfactualCandidatesMustBeCurrentMembersOfTheWorkspaceRelease() {
        var artifact=interactionArtifact();
        var config=Map.<String,Object>of("baseExerciseRef",Map.of("id",exercise,"revision",1),"changeDimension","BOUND","candidateExerciseRefs",List.of(Map.of("id",UUID.randomUUID(),"revision",1)));
        assertThrows(DomainException.class,()->artifacts.save(owner,artifact.id(),0,interaction(block("COUNTERFACTUAL",config)),List.of()));
        jdbc.update("UPDATE exercise SET status='RETIRED' WHERE id=?",exercise.toString());
        var retired=Map.<String,Object>of("baseExerciseRef",Map.of("id",exercise,"revision",1),"changeDimension","BOUND","candidateExerciseRefs",List.of(Map.of("id",exercise,"revision",1)));
        assertThrows(DomainException.class,()->artifacts.save(owner,artifact.id(),0,interaction(block("COUNTERFACTUAL",retired)),List.of()));
    }

    @Test void unavailablePrivateDiagnosticAndSolutionAdaptersDoNotPretendToValidateIds() {
        var artifact=interactionArtifact();
        var diagnostic=Map.<String,Object>of("studentReason","Not supplied","originalSource","","sourceHash",json.sha256(""),"diagnosticIds",List.of(UUID.randomUUID()));
        assertEquals(503,assertThrows(DomainException.class,()->artifacts.save(owner,artifact.id(),0,interaction(block("ERROR_CONTEXT",diagnostic)),List.of())).status());
        var solution=Map.<String,Object>of("originalSource","class Demo {}","studentDraft","","solutionRef",UUID.randomUUID(),"sourceHash",json.sha256("class Demo {}"));
        assertEquals(503,assertThrows(DomainException.class,()->artifacts.save(owner,artifact.id(),0,interaction(block("REPAIR_DIFF",solution)),List.of())).status());
    }

    @Test void notebookAttemptMustMatchTheLinkedExerciseVersion() {
        var attempt=learning.submit(owner,workspace,release,exercise,1,answer("wrong"),key());
        UUID different=addExercise(UUID.randomUUID());var artifact=interactionArtifact();
        var config=Map.<String,Object>of("studentReason","Not supplied","originalSource","","sourceHash",json.sha256(""),"diagnosticIds",List.of());
        var block=block("ERROR_CONTEXT",config);block.put("attemptId",attempt.id());block.put("exerciseRef",Map.of("id",different,"revision",1));
        assertThrows(DomainException.class,()->artifacts.save(owner,artifact.id(),0,interaction(block),List.of(attempt.evidenceRef())));
    }

    @Test void repeatedCorrectAnswersInSameFamilyDoNotEraseTheFirstIndependentFailure() {
        var first=learning.submit(owner,workspace,release,exercise,1,answer("wrong"),key());
        UUID secondWorkspace=workspaces.create(owner,release,"Same learner another workspace","ON_DEMAND",key()).id();
        var repeat=learning.submit(owner,secondWorkspace,release,exercise,1,answer("right"),key());
        assertEquals(0,first.hintLevel());assertEquals(4,repeat.hintLevel());drain();
        var item=learning.progress(owner).items().stream().filter(value->value.conceptId().equals(concept)).findFirst().orElseThrow();
        assertEquals(1,item.independentSamples());assertEquals(0,item.weightedCorrect().compareTo(BigDecimal.ZERO));assertEquals(0,item.weightedTotal().compareTo(BigDecimal.ONE));
        assertEquals("PENDING",learning.reviews(owner).get(0).status());
    }

    @Test void delayedEarlierMistakeFindsTheLaterIndependentCorrectionAcrossWorkspaces() {
        Instant earlier=Instant.parse("2020-02-01T00:00:00Z"),later=earlier.plusSeconds(600);
        var mistake=learning.submit(owner,workspace,release,exercise,1,answer("wrong"),key());
        UUID secondWorkspace=workspaces.create(owner,release,"Later independent evidence","ON_DEMAND",key()).id();
        var correction=learning.submit(owner,secondWorkspace,release,addExercise(UUID.randomUUID()),1,answer("right"),key());
        stampAttempt(mistake.id(),earlier);stampAttempt(correction.id(),later);
        delayAttemptProjection(workspace,true);drain();
        assertTrue(learning.reviews(owner).isEmpty());
        assertEquals(1,learning.progress(owner).items().get(0).independentSamples());
        delayAttemptProjection(workspace,false);drain();
        var review=learning.reviews(owner).stream().filter(value->value.originAttemptId().equals(mistake.id())).findFirst().orElseThrow();
        assertEquals("DONE",review.status(),"Delayed older mistakes must observe already projected newer corrective evidence");
        assertEquals(earlier.plusSeconds(86400),review.dueAt(),"Processing latency must not shift the actual review date");
        assertEquals(earlier,jdbc.queryForObject("SELECT occurred_at FROM learning_event WHERE attempt_id=?",Timestamp.class,mistake.id().toString()).toInstant());
        assertEquals(later,jdbc.queryForObject("SELECT occurred_at FROM learning_event WHERE attempt_id=?",Timestamp.class,correction.id().toString()).toInstant());
        assertEquals(2,learning.progress(owner).items().get(0).independentSamples());
    }

    @Test void delayedEarlierSuccessCannotCloseANewerMistakeFromAnotherWorkspace() {
        Instant earlier=Instant.parse("2020-02-02T00:00:00Z"),later=earlier.plusSeconds(600);
        var oldSuccess=learning.submit(owner,workspace,release,exercise,1,answer("right"),key());
        UUID secondWorkspace=workspaces.create(owner,release,"Newer misconception","ON_DEMAND",key()).id();
        var laterMistake=learning.submit(owner,secondWorkspace,release,addExercise(UUID.randomUUID()),1,answer("wrong"),key());
        stampAttempt(oldSuccess.id(),earlier);stampAttempt(laterMistake.id(),later);
        delayAttemptProjection(workspace,true);drain();
        assertEquals("PENDING",learning.reviews(owner).get(0).status());
        delayAttemptProjection(workspace,false);drain();
        var review=learning.reviews(owner).stream().filter(value->value.originAttemptId().equals(laterMistake.id())).findFirst().orElseThrow();
        assertEquals("PENDING",review.status(),"An earlier success is not remediation of a later failure");
        var progress=learning.progress(owner).items().get(0);
        assertEquals(2,progress.independentSamples());assertEquals(0,progress.weightedCorrect().compareTo(BigDecimal.ONE));
    }

    @Test void equalTimestampEvidenceDoesNotInventAnOrderFromRandomIds() {
        Instant simultaneous=Instant.parse("2020-02-03T00:00:00Z");
        var mistake=learning.submit(owner,workspace,release,exercise,1,answer("wrong"),key());
        UUID secondWorkspace=workspaces.create(owner,release,"Same timestamp evidence","ON_DEMAND",key()).id();
        var success=learning.submit(owner,secondWorkspace,release,addExercise(UUID.randomUUID()),1,answer("right"),key());
        stampAttempt(mistake.id(),simultaneous);stampAttempt(success.id(),simultaneous);
        delayAttemptProjection(workspace,true);drain();
        delayAttemptProjection(workspace,false);drain();
        assertEquals("PENDING",learning.reviews(owner).get(0).status());
    }

    private void stampAttempt(UUID attempt,Instant time) {
        jdbc.update("UPDATE exercise_attempt SET created_at=? WHERE id=?",Timestamp.from(time),attempt.toString());
    }
    private void delayAttemptProjection(UUID workspaceId,boolean hold) {
        jdbc.update("UPDATE outbox_event SET next_attempt_at=? WHERE aggregate_id=? AND event_type='ATTEMPT_GRADED'",hold?Timestamp.from(Instant.parse("2099-01-01T00:00:00Z")):null,workspaceId.toString());
    }

    @Test void duplicateOutboxDeliveryDoesNotDoubleLearningEvidenceOrActivity() {
        var attempt=learning.submit(owner,workspace,release,exercise,1,answer("wrong"),key());drain();
        var before=workspaces.projection(owner,workspace);
        String event=jdbc.queryForObject("SELECT id FROM outbox_event WHERE aggregate_id=? AND event_type='ATTEMPT_GRADED'",String.class,workspace.toString());
        jdbc.update("UPDATE outbox_event SET published_at=NULL WHERE id=?",event);drain();
        assertEquals(before.activityCount(),workspaces.projection(owner,workspace).activityCount());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM learning_event WHERE attempt_id=?",Integer.class,attempt.id().toString()));
        assertEquals(1,learning.progress(owner).items().get(0).independentSamples());
    }

    @Test void sameOwnerAcrossWorkspacesUsesOneParentFirstOrderForWritesAndProjection() throws Exception {
        UUID second=workspaces.create(owner,release,"Concurrent owner stream","ON_DEMAND",key()).id();
        learning.submit(owner,workspace,release,exercise,1,answer("wrong"),key());
        var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(3);
        try {
            var submissions=executor.submit(()->{
                start.await();
                for(int i=0;i<10;i++) learning.submit(owner,workspace,release,exercise,1,answer("right"),key());
                return true;
            });
            var otherWorkspaceWrites=executor.submit(()->{
                start.await();
                for(int i=0;i<10;i++) workspaces.createGoal(owner,second,"Concurrent goal "+i,List.of("Preserve parent lock order"),key());
                return true;
            });
            var projections=executor.submit(()->{
                start.await();for(int i=0;i<20;i++) dispatcher.dispatchBatch(100);return true;
            });
            start.countDown();
            assertTrue(submissions.get(15,TimeUnit.SECONDS));
            assertTrue(otherWorkspaceWrites.get(15,TimeUnit.SECONDS));
            assertTrue(projections.get(15,TimeUnit.SECONDS));
        } finally {executor.shutdownNow();}
        drain();
        assertEquals(11,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_attempt WHERE user_id=?",Integer.class,owner.toString()));
        assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM learning_goal WHERE workspace_id=?",Integer.class,second.toString()));
        assertEquals(1,learning.progress(owner).items().get(0).independentSamples());
        assertTrue(workspaces.projection(owner,workspace).caughtUp());assertTrue(workspaces.projection(owner,second).caughtUp());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event o JOIN learning_workspace w ON w.id=o.aggregate_id WHERE w.owner_id=? AND o.attempts>0",Integer.class,owner.toString()));
    }

    @Test void timestampTiesAndReverseUuidOrderCannotStarveTheWorkspaceHead() {
        Clock fixed=Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"),ZoneOffset.UTC);
        EventJournal writer=new EventJournal(jdbc,json,fixed);
        tx.executeWithoutResult(status->{
            workspaces.lockOwned(owner,workspace);
            for(int sequence=0;sequence<40;sequence++) {
                UUID id=new UUID(workspace.getMostSignificantBits(),Long.MAX_VALUE-sequence);
                writer.append(id,workspace,"ORDER_TEST","SERVER",Map.of("index",sequence));
            }
        });
        for(int round=0;round<30 && !workspaces.projection(owner,workspace).caughtUp();round++) dispatcher.dispatchBatch(7);
        assertTrue(workspaces.projection(owner,workspace).caughtUp(),"Dispatcher must advance by sequence, independent of timestamp and UUID ordering");
    }

    @Test void aRetryingWorkspaceDoesNotStarveUnrelatedWorkspaceStreams() {
        drain();
        UUID healthy=workspaces.create(owner,release,"Healthy stream","ON_DEMAND",key()).id();drain();
        Clock old=Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"),ZoneOffset.UTC);
        EventJournal writer=new EventJournal(jdbc,json,old);
        tx.executeWithoutResult(status->{
            workspaces.lockOwned(owner,workspace);
            writer.append(workspace,"POISON_TEST","SERVER",Map.of());
            for(int i=0;i<20;i++) writer.append(workspace,"AFTER_POISON","SERVER",Map.of("index",i));
        });
        tx.executeWithoutResult(status->{workspaces.lockOwned(owner,healthy);journal.append(healthy,"HEALTHY_TEST","SERVER",Map.of());});
        DomainEventConsumer failing=new DomainEventConsumer() {
            @Override public String name(){return "intentional-poison-fixture";}
            @Override public boolean accepts(String type){return type.equals("POISON_TEST");}
            @Override public void accept(JsonNode envelope){throw new IllegalStateException("Deliberate bounded retry fixture");}
        };
        var targets=new ArrayList<>(consumers);targets.add(failing);
        var isolated=new OutboxDispatcher(jdbc,tx,json,Clock.systemUTC(),targets,false);
        isolated.dispatchBatch(5);
        assertTrue(workspaces.projection(owner,healthy).caughtUp());
        assertFalse(workspaces.projection(owner,workspace).caughtUp());
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE aggregate_id=? AND event_type='POISON_TEST'",Integer.class,workspace.toString()));
    }

    @Test void aConsumerFailureRollsBackItsProjectionAndReceiptTogether() {
        drain();long previous=workspaces.projection(owner,workspace).activityCount();
        UUID event=tx.execute(status->{workspaces.lockOwned(owner,workspace);return journal.append(workspace,"ROLLBACK_TEST","SERVER",Map.of());});
        DomainEventConsumer failing=new DomainEventConsumer() {
            @Override public String name(){return "intentional-rollback-fixture";}
            @Override public boolean accepts(String type){return type.equals("ROLLBACK_TEST");}
            @Override public void accept(JsonNode envelope){
                jdbc.update("UPDATE workspace_projection SET activity_count=999999 WHERE workspace_id=?",workspace.toString());
                throw new IllegalStateException("Deliberate transaction rollback fixture");
            }
        };
        var targets=new ArrayList<>(consumers);targets.add(failing);
        new OutboxDispatcher(jdbc,tx,json,Clock.systemUTC(),targets,false).dispatchBatch(100);
        assertEquals(previous,workspaces.projection(owner,workspace).activityCount());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM event_receipt WHERE event_id=?",Integer.class,event.toString()));
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id=?",Integer.class,event.toString()));
    }

    @Test void snapshotIndirectionCannotBypassRevocableArtifactLineage() {
        var snapshot=workspaces.createContext(owner,workspace);var artifact=documentArtifact("No opaque lineage");
        var ref=new Reference("SNAPSHOT",snapshot.id(),snapshot.workspaceRevision(),snapshot.contentHash());
        assertEquals(503,assertThrows(DomainException.class,()->artifacts.save(owner,artifact.id(),0,document("Explicit sources required"),List.of(ref))).status());
    }
}
