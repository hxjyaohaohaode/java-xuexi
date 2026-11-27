package io.typeforge;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.curriculum.CurriculumService;
import io.typeforge.exercise.LearningService;
import io.typeforge.identity.IdentityService;
import io.typeforge.learning.PersonalCourseware;
import io.typeforge.learning.ReadingService;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:personal-courseware;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DEFAULT_NULL_ORDERING=HIGH")
@AutoConfigureMockMvc @ActiveProfiles({"test","demo"})
class PersonalCoursewareTest {
    @Autowired IdentityService users;
    @Autowired WorkspaceService workspaces;
    @Autowired PersonalCourseware courseware;
    @Autowired CurriculumService course;
    @Autowired LearningService grading;
    @Autowired ReadingService reading;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired MockMvc mvc;
    private String key(){return UUID.randomUUID().toString();}
    private IdentityService.Session user(){return users.register("plan-"+key(),"fixture-password-strong-123");}
    private UUID room(UUID owner){return workspaces.create(owner,course.activeRelease(),"类型实验","ON_DEMAND",key()).id();}
    private CurriculumService.ConceptView topic(){return course.concepts(course.activeRelease()).stream().filter(c->c.code().equals("G06")).findFirst().orElseThrow();}
    private JsonNode tree(Object value){return json.tree(json.write(value));}
    private static List<String> kinds(PersonalCourseware.Plan p){return p.scenes().stream().map(PersonalCourseware.Scene::kind).toList();}

    @Test void explicitPreferencesCreateDifferentRealLessonsAndWithdrawalInvalidatesOnlyDependentVersions() {
        var account=user();UUID owner=account.userId(),group=room(owner),other=room(owner);var concept=topic();
        var first=courseware.generate(owner,group,concept.id(),null,"AUTO",key());
        assertEquals("FOUNDATION",first.strategy());assertEquals("READ",kinds(first).get(0));
        String privateString="仅供我参考";
        var memory=workspaces.stateMemory(owner,group,"我想先看代码。"+privateString,key());
        String idem=key();
        var example=courseware.generate(owner,group,concept.id(),null,"AUTO",idem);
        assertEquals("EXAMPLE_FIRST",example.strategy());assertEquals("EXAMPLE",kinds(example).get(0));
        var scene=example.scenes().get(0);assertEquals(json.sha256(scene.body()),scene.exampleSha256());
        assertEquals(concept.companion().examples().stream().filter(e->e.id().equals(scene.exampleId())).findFirst().orElseThrow().code(),scene.body());
        assertEquals(first.revision()+1,example.revision());assertNotEquals(first.contentHash(),example.contentHash());
        assertFalse(json.write(example).contains(privateString));assertFalse(json.write(example).contains("answer_spec"));
        assertEquals(example,courseware.generate(owner,group,concept.id(),null,"AUTO",idem));
        assertEquals(example,courseware.generate(owner,group,concept.id(),null,"AUTO",key()));
        assertEquals(409,assertThrows(DomainException.class,()->courseware.generate(owner,group,concept.id(),null,"CHALLENGE",idem)).status());
        assertEquals("FOUNDATION",courseware.generate(owner,other,concept.id(),null,"AUTO",key()).strategy());
        assertEquals(404,assertThrows(DomainException.class,()->courseware.get(user().userId(),group,concept.id(),example.id())).status());
        assertEquals(404,assertThrows(DomainException.class,()->courseware.get(owner,other,concept.id(),example.id())).status());
        workspaces.revokeMemory(owner,group,memory.id(),memory.revision());
        assertEquals(409,assertThrows(DomainException.class,()->courseware.get(owner,group,concept.id(),example.id())).status());
        assertEquals(first,courseware.get(owner,group,concept.id(),first.id()));
        var refreshed=courseware.generate(owner,group,concept.id(),null,"AUTO",key());
        assertEquals("FOUNDATION",refreshed.strategy());assertTrue(refreshed.revision()>example.revision());
    }

    @Test void frozenReadingBoundaryAndAdmittedRealGradeChangeQuestionAndScaffoldingWithoutLeakingAnswers() {
        UUID owner=user().userId(),space=room(owner);var concept=topic();
        var saved=workspaces.createContext(owner,space);
        reading.record(owner,tree(Map.of("workspaceId",space,"releaseId",course.activeRelease(),"conceptId",concept.id(),"eventType","READ_COMPLETED")),key());
        var fresh=courseware.generate(owner,space,concept.id(),null,"AUTO",key());
        assertEquals("TRANSFER",fresh.strategy()); assertEquals("PRACTICE",kinds(fresh).get(0));
        var before=courseware.generate(owner,space,concept.id(),saved.id(),"AUTO",key());
        assertEquals("FOUNDATION",before.strategy());assertEquals(saved.eventSequence(),before.contextSequence());
        var choices=course.exercises(course.activeRelease(),concept.id(),null,100).items();
        var original=choices.stream().filter(q->q.kind().equals("SINGLE")).findFirst().orElseThrow();
        var graded=submitWrong(owner,space,original);
        var repair=courseware.generate(owner,space,concept.id(),null,"AUTO",key());
        assertEquals("REPAIR",repair.strategy());assertEquals("GUIDED",repair.pace());
        var reminder=repair.scenes().get(0);assertEquals(graded.id(),reminder.attemptId());assertEquals(original.prompt(),reminder.body().lines().findFirst().orElseThrow());
        var rule=concept.companion().reviews().get(0);
        assertTrue(reminder.body().contains(rule.criterion()));assertTrue(reminder.body().contains(rule.correction()));
        assertTrue(reminder.body().contains(concept.companion().version()+" · "+rule.id()));
        assertTrue(concept.evidence().stream().anyMatch(card->card.chunkId().equals(reminder.chunkId()) && card.revision()==reminder.chunkRevision()));
        assertFalse(reminder.body().contains("optionIds"));assertFalse(reminder.body().contains("correctOptionIds"));
        var next=repair.scenes().stream().filter(s->s.kind().equals("PRACTICE")).findFirst().orElseThrow();
        assertNotEquals(original.id(),next.exerciseId());assertEquals(course.exercise(course.activeRelease(),next.exerciseId(),next.exerciseRevision()).prompt(),next.body());
        assertTrue(repair.scenes().stream().anyMatch(s->s.key().equals("scaffold")));
        assertEquals(before,courseware.get(owner,space,concept.id(),before.id()));
        var replay=courseware.generate(owner,space,concept.id(),saved.id(),"AUTO",key());
        assertEquals("FOUNDATION",replay.strategy());assertTrue(replay.scenes().stream().noneMatch(s->s.attemptId()!=null));
    }

    private LearningService.Attempt submitWrong(UUID owner,UUID space,CurriculumService.ExerciseView original) {
        JsonNode spec=json.tree(jdbc.queryForObject("SELECT answer_spec FROM exercise WHERE id=? AND revision=?",String.class,original.id().toString(),original.revision()));
        String wrong=original.options().stream().filter(o->!o.id().equals(spec.path("optionIds").get(0).asText())).findFirst().orElseThrow().id();
        return grading.submit(owner,space,course.activeRelease(),original.id(),original.revision(),tree(Map.of("kind","CHOICE","optionIds",List.of(wrong))),key());
    }

    @Test void repairWithoutCompanionReviewQuotesTheActualPublishedKnowledgeRevision() {
        UUID owner=user().userId(),space=room(owner),release=course.activeRelease();
        var topic=course.concepts(release).stream().filter(c->!c.evidence().isEmpty() &&
            (c.companion()==null || c.companion().reviews().isEmpty())).findFirst().orElseThrow();
        var question=course.exercises(release,topic.id(),null,100).items().stream().filter(q->q.kind().equals("SINGLE")).findFirst().orElseThrow();
        var attempt=submitWrong(owner,space,question);
        var reminder=courseware.generate(owner,space,topic.id(),null,"AUTO",key()).scenes().get(0);
        var card=course.concept(release,topic.id()).evidence().stream().filter(c->c.chunkId().equals(reminder.chunkId())).findFirst().orElseThrow();
        assertEquals(attempt.id(),reminder.attemptId());assertEquals(question.prompt(),reminder.body().lines().findFirst().orElseThrow());
        assertEquals(card.revision(),reminder.chunkRevision());
        assertTrue(reminder.body().contains("发布知识卡 · "+card.title()+" · 修订 "+card.revision()));
        String excerpt=reminder.body().lines().reduce((left,right)->right).orElseThrow().replaceFirst("…$","");
        assertFalse(excerpt.isBlank());
        assertTrue((card.fullText()==null?card.excerpt():card.fullText()).contains(excerpt));
    }

    @Test void actualHttpEntryPointsAdmitOnlyPublishedConceptsAndStrictInputs() throws Exception {
        var account=user();UUID owner=account.userId(),space=room(owner);var concept=topic();
        var auth=HttpSessionFixture.login(mvc,json,account.username(),"fixture-password-strong-123");
        String url="/api/v1/workspaces/"+space+"/courseware/"+concept.id();
        mvc.perform(get(url+"?optional=true").session(auth)).andExpect(status().isNoContent());
        mvc.perform(get(url).session(auth)).andExpect(status().isNotFound());
        mvc.perform(get(url+"?optional=true&planId="+UUID.randomUUID()).session(auth)).andExpect(status().isNotFound());
        mvc.perform(post(url).with(csrf()).session(auth).header("Idempotency-Key",key()).contentType("application/json")
            .content("{\"sources\":[]}")).andExpect(status().isBadRequest());
        var goal=workspaces.createGoal(owner,space,"通过代码解释泛型不变性",List.of("用自己的话解释赋值约束"),key());
        var response=mvc.perform(post(url).with(csrf()).session(auth).header("Idempotency-Key",key()).contentType("application/json")
            .content("{}")).andExpect(status().isCreated()).andReturn().getResponse();
        var plan=json.read(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8),PersonalCourseware.Plan.class);
        assertEquals("EXAMPLE_FIRST",plan.strategy());
        workspaces.updateGoal(owner,goal.id(),goal.revision(),"分步学习泛型不变性",List.of("逐步预测"),"ACTIVE");
        mvc.perform(get(url+"?planId="+plan.id()).session(auth)).andExpect(status().isConflict());
        var guided=courseware.generate(owner,space,concept.id(),null,"AUTO",key());
        assertEquals("GUIDED",guided.pace());assertTrue(guided.scenes().stream().anyMatch(s->s.key().equals("scaffold")));
    }
}
