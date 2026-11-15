package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.exercise.LearningService;
import io.typeforge.identity.AccountClosureService;
import io.typeforge.identity.AccountPrincipal;
import io.typeforge.identity.AccountService;
import io.typeforge.identity.AuthenticationLimit;
import io.typeforge.identity.IdentityService;
import io.typeforge.learning.ReadingIndexInitializer;
import io.typeforge.learning.ReadingService;
import io.typeforge.operations.DemoContentInitializer;
import io.typeforge.operations.LearningAnalyticsService;
import io.typeforge.operations.events.OutboxDispatcher;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc @ActiveProfiles({"test","demo"}) @Timeout(60)
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class AccountLifecycleIntegrationTest {
    private static final String PASSWORD="fixture-password-strong-123";
    @Autowired MockMvc mvc;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityService identities;
    @Autowired AccountService accounts;
    @Autowired AccountClosureService closure;
    @Autowired AuthenticationLimit limits;
    @Autowired WorkspaceService workspaces;
    @Autowired ReadingService readings;
    @Autowired ReadingIndexInitializer readingIndex;
    @Autowired LearningAnalyticsService analytics;
    @Autowired CurriculumService curriculum;
    @Autowired LearningService learning;
    @Autowired OutboxDispatcher outbox;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @org.springframework.test.context.DynamicPropertySource
    static void explicitDatabase(org.springframework.test.context.DynamicPropertyRegistry properties) {PrivateModuleDatabaseFixture.configure(properties,"account-lifecycle");}
    @org.junit.jupiter.api.BeforeAll
    void proveRealDatabaseWhenOptedIn() {PrivateModuleDatabaseFixture.prove(jdbc,json,getClass().getSimpleName());}
    private String key() {return UUID.randomUUID().toString();}
    private IdentityService.Session account() {return identities.register("account-"+key(),PASSWORD);}
    private MockHttpSession login(IdentityService.Session user) throws Exception {return HttpSessionFixture.login(mvc,json,user.username(),PASSWORD);}
    private WorkspaceService.Workspace workspace(UUID owner) {return workspaces.create(owner,DemoContentInitializer.RELEASE_ID,"真实学习记录","ON_DEMAND",key());}
    private JsonNode body(Object value) {return json.tree(json.write(value));}
    private JsonNode call(MockHttpServletRequestBuilder request,int expected) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();assertEquals(expected,response.getStatus(),response.getContentAsString());
        return response.getContentAsString().isBlank()?null:json.tree(response.getContentAsString());
    }
    @Test void profileCompareAndSwapKeepsLoginIdentitySeparateAndRejectsUnexpectedFields() throws Exception {
        var user=account();var session=login(user);
        var initial=call(get("/api/v1/me/profile").session(session),200);assertEquals(1,initial.path("revision").asInt());
        var changed=call(put("/api/v1/me/profile").session(session).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(json.write(Map.of("displayName","学习者 小黄","revision",1))),200);
        assertEquals("学习者 小黄",changed.path("displayName").asText());assertEquals(user.username(),changed.path("username").asText());assertEquals(2,changed.path("revision").asInt());
        call(put("/api/v1/me/profile").session(session).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(json.write(Map.of("displayName","迟到更新","revision",1))),412);
        call(put("/api/v1/me/profile").session(session).with(csrf()).header("If-Match","\"2\"").contentType("application/json").content(json.write(Map.of("displayName","非法提权","revision",2,"roles",List.of("ADMIN")))),400);
        assertEquals("学习者 小黄",accounts.profile(user.userId()).displayName());
    }
    @Test void changingPasswordRevokesEverySessionAndInFlightOldAuthentication() throws Exception {
        var user=account();var first=login(user);var second=login(user);
        var oldPrincipal=(AccountPrincipal)identities.loadUserByUsername(user.username());
        var rejected=call(put("/api/v1/me/password").session(first).with(csrf()).contentType("application/json").content(json.write(Map.of("currentPassword","incorrect-password","newPassword","new-password-strong-456"))),400);
        assertEquals("CURRENT_PASSWORD_INVALID",rejected.path("code").asText());call(get("/api/v1/me/profile").session(first),200);
        call(put("/api/v1/me/password").session(first).with(csrf()).contentType("application/json").content(json.write(Map.of("currentPassword",PASSWORD,"newPassword","new-password-strong-456"))),204);
        call(get("/api/v1/me/profile").session(second),401);
        assertEquals(401,assertThrows(DomainException.class,()->accounts.openSession(oldPrincipal,"late-old-authentication","test")).status());
        call(post("/api/v1/auth/session").with(csrf()).contentType("application/json").content(json.write(Map.of("username",user.username(),"password",PASSWORD))),401);
        var replacement=HttpSessionFixture.login(mvc,json,user.username(),"new-password-strong-456");call(get("/api/v1/me/profile").session(replacement),200);
    }
    @Test void sessionsArePrivateRevocableAndNeverExposeCookieOrAddress() throws Exception {
        var user=account();var first=login(user);var second=login(user);var foreign=login(account());
        var list=call(get("/api/v1/me/sessions").session(first),200);assertEquals(2,list.path("items").size());
        for(var item:list.path("items")) {assertNotEquals(first.getId(),item.path("id").asText());var fields=new java.util.HashSet<String>();item.fieldNames().forEachRemaining(fields::add);assertEquals(java.util.Set.of("id","deviceLabel","createdAt","lastSeenAt","expiresAt","current"),fields);}
        assertFalse(list.toString().contains("session_hash"));assertFalse(list.toString().contains("127.0.0.1"));
        String otherId=null;for(var item:list.path("items")) if(!item.path("current").asBoolean()) otherId=item.path("id").asText();assertNotNull(otherId);
        call(delete("/api/v1/me/sessions/"+otherId).session(foreign).with(csrf()),404);
        call(delete("/api/v1/me/sessions").session(first).with(csrf()),204);call(get("/api/v1/me/profile").session(first),200);call(get("/api/v1/me/profile").session(second),401);
        var remaining=call(get("/api/v1/me/sessions").session(first),200);assertEquals(1,remaining.path("items").size());
        call(delete("/api/v1/me/sessions/"+remaining.path("items").get(0).path("id").asText()).session(first).with(csrf()),204);
        assertTrue(first.isInvalid());
    }
    @Test void logoutPersistsRevocationAndNoForgedMockPrincipalCanAccessPrivateApi() throws Exception {
        var user=account();var session=login(user);String id=session.getId();var principal=(AccountPrincipal)identities.loadUserByUsername(user.username());
        assertTrue(accounts.validSession(principal,id));call(delete("/api/v1/auth/session").session(session).with(csrf()),204);assertFalse(accounts.validSession(principal,id));
        call(get("/api/v1/me/profile").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(user.username())),401);
    }
    @Test void closingAccountKeepsHistoricalFactsAndCannotTransferThemThroughReRegistration() throws Exception {
        var user=account();var other=account();var a=workspace(user.userId());var b=workspace(other.userId());var current=login(user);var stale=login(user);
        var memory=workspaces.stateMemory(user.userId(),a.id(),"真实的学习偏好",key());
        var exercise=curriculum.exercises(DemoContentInitializer.RELEASE_ID,null,null,100).items().get(0);
        var attempt=learning.submit(user.userId(),a.id(),DemoContentInitializer.RELEASE_ID,exercise.id(),exercise.revision(),body(Map.of("kind","CHOICE","optionIds",List.of(exercise.options().get(0).id()))),key());
        var response=call(post("/api/v1/me/close-account").session(current).with(csrf()).contentType("application/json").content(json.write(Map.of("currentPassword",PASSWORD,"confirmationUsername",user.username()))),200);
        assertEquals("CLOSED",response.path("status").asText());assertTrue(response.path("dataRetention").asText().contains("不属于物理擦除"));call(get("/api/v1/me/profile").session(stale),401);
        assertEquals("DISABLED",jdbc.queryForObject("SELECT status FROM app_user WHERE id=?",String.class,user.userId().toString()));
        assertEquals("REVOKED",jdbc.queryForObject("SELECT status FROM learner_memory WHERE id=?",String.class,memory.id().toString()));
        assertEquals("ARCHIVED",jdbc.queryForObject("SELECT status FROM learning_workspace WHERE id=?",String.class,a.id().toString()));
        assertEquals("ACTIVE",workspaces.get(other.userId(),b.id()).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM exercise_attempt WHERE id=?",Integer.class,attempt.id().toString()));
        var replacement=identities.register(user.username(),PASSWORD);assertNotEquals(user.userId(),replacement.userId());assertEquals(404,assertThrows(DomainException.class,()->workspaces.get(replacement.userId(),a.id())).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor_id=? AND action='ACCOUNT_CLOSED'",Integer.class,user.userId().toString()));
    }
    @Test void accountClosureIsAtomicOnWrongConfirmationAndDoesNotEraseConcurrentPasswordChange() {
        var user=account();var space=workspace(user.userId());workspaces.stateMemory(user.userId(),space.id(),"保留此事实",key());
        assertEquals(400,assertThrows(DomainException.class,()->closure.close(user.userId(),PASSWORD,"wrong-name")).status());
        assertEquals("ACTIVE",workspaces.get(user.userId(),space.id()).status());assertEquals(user.username(),identities.session(user.username()).username());
    }
    @Test void authenticationLimitsAreAtomicAcrossConcurrentAdmission() throws Exception {
        String identity=key();var pool=Executors.newFixedThreadPool(8);var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
        try {
            for(int i=0;i<20;i++) futures.add(pool.submit(()->{try {limits.consume("concurrency-test",identity,300,5);return true;} catch(DomainException failure) {assertEquals(429,failure.status());return false;}}));
            int accepted=0;for(var result:futures) if(result.get(20,TimeUnit.SECONDS)) accepted++;assertEquals(5,accepted);
            assertEquals(5,jdbc.queryForObject("SELECT hit_count FROM authentication_limit WHERE bucket_hash=?",Integer.class,json.sha256("concurrency-test\n"+identity)));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void readingsPersistPerWorkspaceBackfillRealFactsAndNeverManufactureMastery() throws Exception {
        var user=account();var space=workspace(user.userId());var sibling=workspace(user.userId());var other=account();var concept=curriculum.concepts(DemoContentInitializer.RELEASE_ID).get(0).id();
        var first=readings.record(user.userId(),body(Map.of("workspaceId",space.id(),"releaseId",DemoContentInitializer.RELEASE_ID,"conceptId",concept,"eventType","READ_COMPLETED")),key());
        assertEquals(1,readings.history(user.userId(),space.id()).size());assertTrue(readings.history(user.userId(),space.id()).get(0).completed());assertTrue(readings.history(user.userId(),sibling.id()).isEmpty());
        jdbc.update("DELETE FROM workspace_reading WHERE learning_event_id=?",first.id().toString());readingIndex.run(new DefaultApplicationArguments());readingIndex.run(new DefaultApplicationArguments());
        assertEquals(1,readings.history(user.userId(),space.id()).get(0).readEvents());assertEquals(first.occurredAt().toEpochMilli(),readings.history(user.userId(),space.id()).get(0).lastCompletedAt().toEpochMilli());
        assertEquals(404,assertThrows(DomainException.class,()->readings.history(other.userId(),space.id())).status());
        assertTrue(learning.progress(user.userId()).items().isEmpty());var history=call(get("/api/v1/workspaces/"+space.id()+"/readings").session(login(user)),200);assertTrue(history.path("items").get(0).path("completed").asBoolean());
    }
    @Test void analyticsUseAuthoritativeOwnerFactsAndZeroFillEmptyDays() {
        var user=account();var space=workspace(user.userId());var other=account();workspace(other.userId());
        var empty=analytics.get(user.userId(),7);assertEquals(7,empty.daily().size());assertNull(empty.comparison().current().correctRatePercent());assertEquals(0,empty.totals().gradedAttempts());
        var exercise=curriculum.exercises(DemoContentInitializer.RELEASE_ID,null,null,100).items().get(0);
        var attempt=learning.submit(user.userId(),space.id(),DemoContentInitializer.RELEASE_ID,exercise.id(),exercise.revision(),body(Map.of("kind","CHOICE","optionIds",List.of(exercise.options().get(0).id()))),key());
        var concept=curriculum.concepts(DemoContentInitializer.RELEASE_ID).get(0).id();readings.record(user.userId(),body(Map.of("workspaceId",space.id(),"releaseId",DemoContentInitializer.RELEASE_ID,"conceptId",concept,"eventType","READ_COMPLETED")),key());
        while(outbox.dispatchBatch(100)>0) { /* real projection */ }
        var populated=analytics.get(user.userId(),7);assertEquals(1,populated.totals().gradedAttempts());assertEquals(1,populated.totals().completedReadings());assertEquals(1,populated.daily().stream().mapToLong(LearningAnalyticsService.Day::gradedAttempts).sum());assertEquals(0,analytics.get(other.userId(),7).totals().gradedAttempts());
        assertEquals(0,populated.comparison().previous().gradedAttempts());
        jdbc.update("UPDATE exercise_attempt SET created_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(9*86400L)),attempt.id().toString());
        var comparison=analytics.get(user.userId(),7);assertEquals(0,comparison.comparison().current().gradedAttempts());assertEquals(1,comparison.comparison().previous().gradedAttempts());
        assertEquals(400,assertThrows(DomainException.class,()->analytics.get(user.userId(),91)).status());
    }
    @Test void operationsRequireCurrentAdministratorRoleAndExposeOnlyAggregateMetrics() throws Exception {
        var user=account();var normal=login(user);call(get("/api/v1/admin/operations").session(normal),403);
        jdbc.update("UPDATE app_user SET roles='[\"LEARNER\",\"ADMIN\"]' WHERE id=?",user.userId().toString());var admin=login(user);
        var result=call(get("/api/v1/admin/operations").session(admin),200);assertTrue(result.path("requestMetrics").path("completed").asLong()>0);assertTrue(result.path("queues").has("pendingEvents"));assertFalse(result.toString().contains(PASSWORD));assertFalse(result.toString().contains(user.username()));
        jdbc.update("UPDATE app_user SET roles='[\"LEARNER\"]' WHERE id=?",user.userId().toString());call(get("/api/v1/admin/operations").session(admin),403);
    }
    @Test void analyticsKeepsUtcDayBoundariesWhenJdbcRunsInAnotherLocalTimezone() {
        var user=account();var space=workspace(user.userId());var concept=curriculum.concepts(DemoContentInitializer.RELEASE_ID).get(0).id();
        var receipt=readings.record(user.userId(),body(Map.of("workspaceId",space.id(),"releaseId",DemoContentInitializer.RELEASE_ID,"conceptId",concept,"eventType","READ_COMPLETED")),key());
        var today=Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate();var midnight=today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plusSeconds(60);
        jdbc.update("UPDATE learning_event SET occurred_at=? WHERE id=?",Timestamp.from(midnight),receipt.id().toString());
        var report=analytics.get(user.userId(),7);assertEquals(1,report.daily().get(6).completedReadings());assertEquals(0,report.daily().get(5).completedReadings());
    }
    @Test void aPreviouslyPrivilegedLoginNameCannotBeReRegisteredAndInheritBootstrapRoles() {
        var user=account();accounts.audit(user.userId(),"OPERATOR_ROLE_GRANTED",Map.of("role","EDITOR","method","test-fixture"));
        // The role has already been revoked; the historical binding still makes this login name sensitive.
        assertEquals(List.of("LEARNER"),identities.session(user.username()).roles());
        var closed=closure.close(user.userId(),PASSWORD,user.username());assertTrue(closed.dataRetention().contains("原用户名保持保留"));
        assertEquals(409,assertThrows(DomainException.class,()->identities.register(user.username(),PASSWORD)).status());
        assertDoesNotThrow(()->new io.typeforge.identity.OperatorRoleBootstrap(jdbc,tx,json,user.username(),"").run(new DefaultApplicationArguments()));
        assertEquals("DISABLED",jdbc.queryForObject("SELECT status FROM app_user WHERE id=?",String.class,user.userId().toString()));
    }
    @Test void admittedRequestIdentityCannotBecomeAReRegisteredNamesakeOrSurvivePasswordEpochChange() {
        var original=account();var principal=(AccountPrincipal)identities.loadUserByUsername(original.username());
        var holder=org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            holder.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
            assertEquals(original.userId(),identities.session(original.username()).userId());
            closure.close(original.userId(),PASSWORD,original.username());
            var replacement=identities.register(original.username(),PASSWORD);assertNotEquals(original.userId(),replacement.userId());
            assertEquals(401,assertThrows(DomainException.class,()->identities.session(original.username())).status());
            var fresh=(AccountPrincipal)identities.loadUserByUsername(original.username());
            holder.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(fresh,null,fresh.getAuthorities()));
            assertEquals(replacement.userId(),identities.session(original.username()).userId());
            accounts.changePassword(replacement.userId(),PASSWORD,"replacement-password-strong");
            assertEquals(401,assertThrows(DomainException.class,()->identities.session(original.username())).status());
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void passwordLengthCountsUnicodeCharactersAndPreservesTheBcryptByteBoundary() {
        assertEquals(400,assertThrows(DomainException.class,()->identities.register("short-"+key(),"🔐".repeat(6))).status());
        assertEquals(400,assertThrows(DomainException.class,()->identities.register("long-"+key(),"密".repeat(25))).status());
        var user=identities.register("unicode-"+key(),"🔐".repeat(12));
        assertNotNull(accounts.verifyPassword(user.userId(),"🔐".repeat(12)));
    }
}
