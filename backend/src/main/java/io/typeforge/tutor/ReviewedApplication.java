package io.typeforge.tutor;

import io.typeforge.curriculum.LessonActivityRequests;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Binds a model-selected teaching activity to the exact worked case verified with the learner's edition. */
record ReviewedApplication(String scenario,String modeling,String prediction,String rationale,String transfer,String requirements) {
    static final List<String> MOVES=LessonActivityRequests.ACTIVITIES;
    LessonActivityRequests requests() { return LessonActivityRequests.parse(requirements); }
    static Optional<ReviewedApplication> from(CoursewareSlideSource.Edition edition) {
        var cards=new java.util.HashMap<String,CoursewareSlideSource.Card>();
        edition.cards().forEach(c->cards.put(c.key(),c));
        var scene=cards.get("application");
        var test=cards.get("application-check");
        var answer=cards.get("application-check-solution");
        if(scene==null || test==null || answer==null) return Optional.empty();
        var next=cards.get("application-transfer");
        return Optional.of(new ReviewedApplication(scene.title(),scene.body(),test.body(),answer.body(),
            next==null?"将原例中一个类型换成同一层级的另一个类型。预测编译器的判断，并用原题的规则检验。":next.body(),edition.requirements()));
    }

    Map<String,Object> input() {
        return Map.of("kind","REVIEWED_APPLICATION","scenario",scenario,"modeling",modeling,"prediction",prediction,
            "rationale",rationale,"transfer",transfer,"requirements",requirements);
    }
    static Optional<ReviewedApplication> from(TutorAnswerProvider.Prompt prompt) {
        return prompt.learnerContext().stream().filter(c->"REVIEWED_APPLICATION".equals(c.get("kind"))).findFirst().map(c->
            new ReviewedApplication((String)c.get("scenario"),(String)c.get("modeling"),(String)c.get("prediction"),
                (String)c.get("rationale"),(String)c.get("transfer"),(String)c.getOrDefault("requirements","")));
    }
    String activity(String move) {
        return switch(move) {
            case "TRACE_TYPES" -> scenario+" · 类型推导\n"+modeling
                +"\n\n在本例每个变量旁标出声明类型，再画出同一对象经过哪些变量被访问。\n"+rationale;
            case "CHECK_COMPILE" -> scenario+" · 编译预测\n"+prediction
                +"\n\n先在编辑器中单独检验待判断的一行，再逐项核对。\n"+rationale;
            case "COUNTEREXAMPLE" -> scenario+" · 对照反例\n"+prediction+"\n\n"+rationale
                +"\n\n比较两行中发生变化的类型标记。保留原始赋值，解释为什么同一结论不能同时适用于两行。";
            case "TRANSFER" -> scenario+" · 迁移练习\n"+transfer+"\n\n把新情境中的对象与原例的一一对应。"
                +"用下面的已审判断检验你采用的规则，然后预测改变一个条件后的结果。\n"+rationale;
            case LessonActivityRequests.OMIT -> "已按你的要求省略补充教学活动。";
            default -> throw new IllegalArgumentException("Unrecognized teaching activity");
        };
    }
}
