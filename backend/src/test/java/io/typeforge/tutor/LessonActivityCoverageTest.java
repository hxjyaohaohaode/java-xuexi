package io.typeforge.tutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.typeforge.curriculum.LessonActivityRequests;
import io.typeforge.shared.JsonCodec;
import io.typeforge.workspace.WorkspaceContextStore.Reference;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LessonActivityCoverageTest {
    private static final String ASTRONOMY="深入解释编译期拒绝的反例，给我一个可以迁移的变式";
    private static final Reference SOURCE=new Reference("KNOWLEDGE_CHUNK",UUID.randomUUID(),1,"a".repeat(64));
    private static final JsonCodec JSON=new JsonCodec(new ObjectMapper());
    private static CoursewareSlideSource.Edition edition(String requirements) {
        return new CoursewareSlideSource.Edition("天文课堂",List.of(
            new CoursewareSlideSource.Card("application","天文观测","READ","Transit 与 Eclipse 都继承 Observation。",null,SOURCE),
            new CoursewareSlideSource.Card("application-check","判断编译","PREDICT","List<Observation> target = transits;",null,SOURCE),
            new CoursewareSlideSource.Card("application-check-solution","解析","READ","编译器在赋值处拒绝。改成 List<? extends Observation> 可以读取。",null,SOURCE),
            new CoursewareSlideSource.Card("application-transfer","迁移","REFLECT","改用 List<? super Transit> 的目的是什么？",null,SOURCE)
        ),"天文",requirements);
    }

    @Test void orderedRequestsRemainDistinctFromDepthAndFromNegativeOrUnrelatedClauses() {
        var request=LessonActivityRequests.parse(ASTRONOMY);
        assertEquals(List.of("COUNTEREXAMPLE","TRANSFER"),request.required());
        assertEquals(List.of("COUNTEREXAMPLE"),request.optionsFor(0,2));
        assertEquals(List.of("TRANSFER"),request.optionsFor(1,2));
        assertEquals(List.of("CHECK_COMPILE"),LessonActivityRequests.parse("用通俗例子说明一次传参和一次写入，再让我预测。").required());
        var mixed=LessonActivityRequests.parse("不要反例和迁移，但请先预测。再逐步分析类型");
        assertEquals(List.of("CHECK_COMPILE","TRACE_TYPES"),mixed.required());
        assertEquals(List.of("COUNTEREXAMPLE","TRANSFER"),mixed.declined());
        assertTrue(LessonActivityRequests.parse("深入说明为什么，最好通俗一些").required().isEmpty());
        assertEquals(List.of("TRANSFER"),LessonActivityRequests.parse("先不要迁移；最后改为迁移练习").required());
        var english=LessonActivityRequests.parse("skip counterexamples and transfer, but predict");
        assertEquals(List.of("COUNTEREXAMPLE","TRANSFER"),english.declined());
        assertEquals(List.of("CHECK_COMPILE"),english.required());
        assertEquals(List.of(LessonActivityRequests.OMIT),LessonActivityRequests.parse("不要类型推导、预测、反例和迁移。").optionsFor(0,1));
        assertThrows(IllegalArgumentException.class,()->request.optionsFor(2,2));
    }

    @Test void openBriefKeepsChoiceWithOneExpertAndOffersDifferentMovesAcrossSeveralExperts() {
        var open=LessonActivityRequests.parse("");
        assertEquals(LessonActivityRequests.ACTIVITIES,open.optionsFor(0,1));
        assertTrue(java.util.Collections.disjoint(open.optionsFor(0,2),open.optionsFor(1,2)));
        var negative=LessonActivityRequests.parse("不需要迁移");
        for(int i=0;i<3;i++) assertFalse(negative.optionsFor(i,3).contains("TRANSFER"));
    }

    @Test void shortExpertPlanCompletesEveryRequestedActivityUsingFrozenCodeAndLocalEditionAlsoHonorsIt() {
        var plan=edition(ASTRONOMY);
        var application=ReviewedApplication.from(plan).orElseThrow();
        String accepted=application.activity("COUNTEREXAMPLE");
        var emptySlides=JSON.tree("{\"slides\":[]}");
        var model=CoursewareSlides.render(JSON,plan,emptySlides,TutorService.Mode.MODEL,List.of(accepted));
        var local=CoursewareSlides.render(JSON,plan,emptySlides,TutorService.Mode.EVIDENCE_ONLY,List.of());
        assertFalse(model.toString().contains("requested-COUNTEREXAMPLE"));
        assertTrue(local.toString().contains("requested-COUNTEREXAMPLE-1"));
        for(var deck:List.of(model,local)) {
            var transfer=new StringBuilder();int parts=0;
            for(var page:deck.path("slides")) if(page.path("id").asText().startsWith("requested-TRANSFER-")) {
                assertEquals("requested-TRANSFER-"+(++parts),page.path("id").asText());
                assertEquals(SOURCE.id().toString(),page.at("/evidenceRefs/0/id").asText());
                assertTrue(page.path("title").asText().startsWith("本节指定活动"));
                transfer.append(page.at("/elements/0/text").asText());
            }
            assertTrue(parts>0);assertEquals(application.activity("TRANSFER"),transfer.toString());
            assertTrue(transfer.toString().contains("List<? super Transit>"));
        }
        for(String brief:List.of("只要通俗说明","不要迁移和反例")) {
            var deck=CoursewareSlides.render(JSON,edition(brief),emptySlides,TutorService.Mode.EVIDENCE_ONLY,List.of());
            assertFalse(deck.toString().contains("requested-"));
        }
    }
}
