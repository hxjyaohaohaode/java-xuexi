package io.typeforge.learning;

import io.typeforge.curriculum.CourseCompanions.Example;
import io.typeforge.curriculum.CurriculumService.ConceptView;
import io.typeforge.curriculum.CurriculumService.EvidenceView;
import io.typeforge.workspace.LearningContextJournal.Recall;
import java.util.*;
import java.util.regex.Pattern;

/** Chooses a teaching outline first, then fills each scene from admitted course material. */
final class PersonalLessonOutline {
    record Question(UUID id, int revision, UUID family, int difficulty, String prompt) {}
    record Attempt(UUID id, UUID exercise, int revision, UUID family, java.time.Instant gradedAt, boolean correct, boolean assisted) {}
    record Draft(String strategy, String pace, List<String> reasons, List<PersonalCourseware.Scene> scenes) {}
    private static final Pattern EXAMPLES = Pattern.compile("先看(?:例子|示例|代码|案例)|先做实验|通过(?:例子|代码)|举例|用.{0,3}(例子|代码|案例)|examples? first|code first", Pattern.CASE_INSENSITIVE);
    private static final Pattern GUIDANCE = Pattern.compile("分步|逐步|一步一步|基础入门|零基础|慢一些|先读规则|先看概念|规则优先|step.by.step|beginner", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHALLENGE = Pattern.compile("挑战|迁移|独立解题|先做题|先练习|自测|进阶|challenge|transfer", Pattern.CASE_INSENSITIVE);
    private static boolean asks(String text, Pattern intent) {
        // Negative instructions may set a different preference; never interpret them as affirmative.
        for (String clause : text.split("[。！？.!?，,；;\n]"))
            if (!clause.matches("(?iu).*(不想|不要|不喜欢|不希望|别|避免|do not|don't|avoid|without).*") && intent.matcher(clause).find()) return true;
        return false;
    }

    private static String firstTeachingPreference(List<String> newestFirst) {
        for (String text : newestFirst) {
            if (asks(text, GUIDANCE)) return "GUIDED";
            if (asks(text, EXAMPLES)) return "EXAMPLE_FIRST";
            if (asks(text, CHALLENGE)) return "CHALLENGE";
        }
        return "AUTO";
    }

    static Draft build(ConceptView topic, ConceptView prerequisite, List<Question> questions,
            List<Attempt> attempts, List<Recall> facts, boolean previouslyRead, String approach) {
        var reasons = new ArrayList<String>();
        var latest = attempts.isEmpty() ? null : attempts.get(0);
        var preferences = facts.stream().filter(f -> f.kind().equals("MEMORY")).map(Recall::text).toList();
        var goals = facts.stream().filter(f -> f.kind().equals("GOAL")).map(Recall::text).toList();
        String preference = firstTeachingPreference(preferences);
        String stated = preference.equals("AUTO") ? firstTeachingPreference(goals) : preference;
        boolean gradual = stated.equals("GUIDED");
        boolean repair = latest != null && (!latest.correct() || latest.assisted());
        String strategy;
        switch (approach) {
            case "GUIDED" -> { strategy = repair ? "REPAIR" : "FOUNDATION"; reasons.add("你为这份课件选择了分步讲解。"); }
            case "CHALLENGE" -> { strategy = "TRANSFER"; reasons.add("你为这份课件选择了先接受挑战。"); }
            case "EXAMPLE_FIRST" -> { strategy = "EXAMPLE_FIRST"; reasons.add("你为这份课件选择了从实例开始。"); }
            default -> {
                if (repair) {
                    strategy = "REPAIR";
                    reasons.add(latest.correct() ? "本次纳入的最近作答借助了提示，本节先独立重建判断，再尝试变式。" : "本次纳入的最近同概念作答未获满分，本节先回到那一道题。");
                } else if (gradual) {
                    strategy = "FOUNDATION"; reasons.add("你记录了分步学习的目标或偏好，本节增加前置回顾和判断步骤。");
                } else if (stated.equals("EXAMPLE_FIRST")) {
                    strategy = "EXAMPLE_FIRST"; reasons.add(preference.equals("EXAMPLE_FIRST") ? "你记录了先看例子或代码的偏好。" : "本次目标要求通过实例解释规则。");
                } else if (stated.equals("CHALLENGE") || latest != null || previouslyRead) {
                    strategy = "TRANSFER";
                    reasons.add(stated.equals("CHALLENGE") ? "你的目标或偏好包含独立自测或迁移任务。" :
                        latest != null ? "纳入的最近作答独立获得了满分，本节先用另一道题检验迁移。" :
                        "已记录本节阅读完成，本节先用问题检验理解。阅读本身不计为掌握。");
                } else { strategy = "FOUNDATION"; reasons.add("本次上下文没有本节的已判分作答，从发布规则和第一次独立预测开始。"); }
            }
        }
        String pace = strategy.equals("TRANSFER") ? "COMPACT" : repair || gradual || approach.equals("GUIDED") ? "GUIDED" : "STANDARD";
        var scenes = new ArrayList<PersonalCourseware.Scene>();
        var evidence = topic.evidence().isEmpty() ? null : topic.evidence().get(0);
        var examples = topic.companion() == null ? List.<Example>of() : topic.companion().examples();
        var example = examples.stream().filter(e -> e.compiles() != strategy.equals("REPAIR")).findFirst().orElse(examples.isEmpty() ? null : examples.get(0));
        var seen = new HashSet<UUID>(); attempts.forEach(a -> seen.add(a.family()));
        var ordered = questions.stream().sorted(Comparator.<Question,Boolean>comparing(q -> seen.contains(q.family()))
            .thenComparingInt(q -> strategy.equals("TRANSFER") ? -q.difficulty() : q.difficulty()).thenComparing(Question::id)).toList();
        Question next = ordered.isEmpty() ? null : ordered.get(0);
        String prompt = topic.companion() != null && !topic.companion().reviews().isEmpty()
            ? topic.companion().reviews().get(0).question()
            : "对照「" + topic.title() + "」的材料，预测把一个类型实参替换后，哪些声明仍然成立。";
        var predict = question("prediction","PREDICT","先写下你的预测",prompt,"写下判断与理由，再打开本节依据。",null,null);
        var practice = next == null ? null : question("practice","PRACTICE",seen.contains(next.family()) ? "再验证一次" : "独立验证",next.prompt(),
            "进入这道已发布的练习，提交后查看真实判分。",next,null);
        var rule = evidence == null ? null : read("rule",evidence.title(),evidence);
        var snippet = example == null ? null : new PersonalCourseware.Scene("example","EXAMPLE",example.title(),example.code(),
            "先预测能否编译，再在实验中验证；把观察与知识卡对照。",null,null,example.id(),example.sha256(),null,null,null);
        if (strategy.equals("REPAIR")) {
            var origin = questions.stream().filter(q -> q.id().equals(latest.exercise()) && q.revision() == latest.revision()).findFirst().orElse(null);
            if (origin != null) scenes.add(repairRecall(topic,evidence,origin,latest.id()));
        }
        if (prerequisite != null && !prerequisite.evidence().isEmpty() && (strategy.equals("FOUNDATION") || strategy.equals("REPAIR")))
            scenes.add(read("prerequisite","先回顾 · "+prerequisite.title(),prerequisite.evidence().get(0)));
        if (pace.equals("GUIDED")) {
            String task = topic.companion()!=null && !topic.companion().reviews().isEmpty()
                ? topic.companion().reviews().get(0).question() : next!=null ? next.prompt() : prompt;
            scenes.add(question("scaffold","PREDICT","分三步检查类型",task,
                "① 写出表达式的声明类型。② 标出类型实参及其限制。③ 只改变一个类型，预测编译结果并说明理由。",null,null));
        }
        switch (strategy) {
            case "EXAMPLE_FIRST" -> append(scenes, snippet, predict, rule, practice);
            case "REPAIR" -> append(scenes, rule, snippet, practice);
            case "TRANSFER" -> append(scenes, practice == null ? predict : practice, snippet, rule);
            default -> append(scenes, rule, predict, snippet, practice);
        }
        String reflection = switch (strategy) {
            case "REPAIR" -> "回到刚才的判断：哪一对具体类型使原来的推理失效？用本节依据改写一步，再检验另一个输入。";
            case "TRANSFER" -> "从本节问题挑选一个类型条件，只改动这一项，设计一个结论相反的例子并说明原因。";
            case "EXAMPLE_FIRST" -> "从实例中选出决定结果的一行，解释它使用了哪一条发布规则。";
            default -> "用自己的话说出「" + topic.title() + "」的一条适用条件，再写一个能检验它的问题。";
        };
        scenes.add(question("reflection","REFLECT","写下你的解释",reflection,null,null,null));
        return new Draft(strategy,pace,List.copyOf(reasons),List.copyOf(scenes));
    }

    /** The first repair scene can be exported. Use only the public exercise prompt and published teaching material. */
    private static PersonalCourseware.Scene repairRecall(ConceptView topic, EvidenceView card, Question origin, UUID attemptId) {
        var body = new StringBuilder(origin.prompt());
        var supplement = topic.companion();
        if (supplement != null && !supplement.reviews().isEmpty()) {
            for (var rule : supplement.reviews()) {
                body.append("\n\n课程包核对依据 · ").append(supplement.version()).append(" · ").append(rule.id())
                    .append("\n").append(rule.criterion());
                if (rule.correction() != null && !rule.correction().isBlank())
                    body.append("\n课程包记录的模型勘误 · ").append(rule.correction());
            }
        } else if (card != null) {
            String text = card.fullText() == null ? card.excerpt() : card.fullText();
            String paragraph = Arrays.stream(text.strip().split("\\R\\s*\\R"))
                .filter(part -> !part.isBlank() && !part.startsWith("演示预置学习内容"))
                .findFirst().orElse(text.strip());
            int end = paragraph.offsetByCodePoints(0, Math.min(180, paragraph.codePointCount(0, paragraph.length())));
            body.append("\n\n发布知识卡 · ").append(card.title()).append(" · 修订 ").append(card.revision())
                .append("\n").append(paragraph,0,end);
            if (end < paragraph.length()) body.append("…");
        }
        return new PersonalCourseware.Scene("recall","PREDICT","重看这次作答",body.toString(),
            "先指出原题要检查的类型关系，再用课程依据核对理由。",card == null ? null : card.chunkId(),
            card == null ? null : card.revision(),null,null,origin.id(),origin.revision(),attemptId);
    }

    private static void append(List<PersonalCourseware.Scene> result, PersonalCourseware.Scene... scenes) {
        for (var scene: scenes) if (scene != null) result.add(scene);
    }
    private static PersonalCourseware.Scene read(String key, String title, EvidenceView card) {
        return new PersonalCourseware.Scene(key,"READ",title,card.fullText() == null ? card.excerpt() : card.fullText(),
            null,card.chunkId(),card.revision(),null,null,null,null,null);
    }
    private static PersonalCourseware.Scene question(String key, String kind, String title, String body, String prompt, Question question, UUID attempt) {
        return new PersonalCourseware.Scene(key,kind,title,body,prompt,null,null,null,null,
            question == null ? null : question.id(),question == null ? null : question.revision(),attempt);
    }
}
