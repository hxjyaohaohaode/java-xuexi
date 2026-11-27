package io.typeforge.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.typeforge.curriculum.CurriculumService.ConceptView;
import io.typeforge.curriculum.CurriculumService.EvidenceView;
import io.typeforge.learning.PersonalCourseware.Brief;
import io.typeforge.learning.PersonalCourseware.Portable;
import io.typeforge.learning.PersonalCourseware.Scene;
import io.typeforge.learning.PersonalCourseware.SelfCheck;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.typeforge.workspace.LearningContextJournal.Recall;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Builds alternate, checkable practice from a concept's typed example and the student's brief. */
@Component
final class InterestApplications {
    record Recipe(UUID conceptId,String code,String declarations,String setup,String allowed,String rejected,
                  String allowedReason,String rejectedReason,String transfer,String rule,String mechanism,String observation) {
        /** Only reviewed fragments and fixed Java identifiers enter the executable unit. */
        String javaSource(Theme theme,boolean accepted) {
            String declarationsBlock=declarations.isBlank()?"":declarations.indent(4)+"\n";
            String selected=accepted?allowed:rejected;
            String body=(setup.isBlank()?"":setup+"\n")+"// 先预测下一行的编译结果，再试着只改一个类型。\n"+selected
                +"\n\n"+observation;
            return theme.fill("""
                import java.util.*;

                public class Main {
                    static class $Base {
                        String label() { return "$Base"; }
                        @Override public String toString() { return label(); }
                    }
                    static class $One extends $Base {
                        @Override String label() { return "$One"; }
                    }
                    static class $Other extends $Base {
                        @Override String label() { return "$Other"; }
                    }
                """+declarationsBlock+"    public static void main(String[] args) {\n"+body.indent(8)+"    }\n}\n");
        }
    }
    record Theme(Pattern words,String situation,String base,String one,String other,String task,boolean producer) {
        String fill(String text) { return text.replace("$Base",base).replace("$One",one).replace("$Other",other); }
        String hierarchy() {
            return "本例中 "+one+" 和 "+other+" 都继承 "+base+"，两者互不继承。"+base+" 声明 String label()。";
        }
    }
    private static Theme theme(String words,String situation,String base,String one,String other,String task,boolean producer) {
        return new Theme(Pattern.compile(words,Pattern.CASE_INSENSITIVE),situation,base,one,other,task,producer);
    }
    private static final List<Theme> THEMES=List.of(
        theme("音乐|歌单|歌曲|乐队|钢琴|演唱|吉他|music|playlist","音乐节曲库","Track","LiveTrack","StudioTrack","演出编辑要从现场录音清单挑选曲目交给播放器，并保留录音类型。",true),
        theme("篮球|投篮|球员|NBA|basketball","篮球队训练名单","Player","Guard","Center","教练要在后卫训练名单上登记队员，并阻止把中锋误写入这份名单。",false),
        theme("足球|守门员|soccer|football","足球俱乐部阵容","Player","Keeper","Striker","教练需要为守门员安排训练，还要将训练名单交给读取全部球员的报表。",false),
        theme("游戏|电竞|道具|原神|玩家|game|gaming","游戏背包","Item","Potion","Shield","玩家的药剂栏只接受药剂，但交易界面需要展示不同道具的名字。",false),
        theme("天文|行星|星空|星系|天体|astronomy|planet","天文观测档案","Observation","Transit","Eclipse","观测员要从凌日记录提取名称，并把结果交给能处理多类观测记录的工具。",true),
        theme("摄影|拍照|照片|胶片|photo|camera","摄影作品库","Photo","Portrait","Landscape","策展人从人像集读取作品，编辑还要能独立维护风景集。",true),
        theme("电影|影视|动画|剧本|剪辑|视频|cinema|film|video","电影节片库","Film","Documentary","Animation","策展人从纪录片单读取影片信息，排片系统允许维护不同门类的影片。",true),
        theme("烘焙|做饭|美食|甜品|烹饪|奶茶|咖啡|cooking|recipe","厨房配方库","Recipe","DrinkRecipe","DessertRecipe","饮品研发需要收集饮品配方；甜品配方另存，展示页可读取二者。",false),
        theme("生物|生态|动物|植物|自然|ecology|nature","自然观察记录","Sighting","BirdSighting","TreeSighting","观鸟队要维护鸟类观察清单，展览从各种自然记录中读取作品。",false),
        theme("书|阅读|小说|文学|图书馆|book|library","图书馆专题书架","Book","Novel","Essay","读书会挑选小说，借阅展示页需要统一读取不同图书资料。",true));
    private static final Theme PUBLIC=theme("(?!)","专题资料库","Entry","Feature","Note","栏目编辑维护专题条目，公共目录展示多种条目。",true);
    private static final List<String> OPTIONS=List.of("可以通过 Java 17 类型检查","编译器会拒绝这行代码");
    private final Map<UUID,Recipe> recipes;

    InterestApplications(ObjectMapper mapper) throws IOException {
        try(var input=new ClassPathResource("knowledge/interest-applications.json").getInputStream()) {
            recipes=Arrays.stream(mapper.readValue(input,Recipe[].class)).collect(Collectors.toUnmodifiableMap(Recipe::conceptId,Function.identity()));
        }
    }

    PersonalLessonOutline.Draft adapt(PersonalLessonOutline.Draft base,ConceptView topic,Brief brief) {
        Recipe recipe=recipes.get(topic.id());
        if (recipe==null || topic.evidence().isEmpty()) return base;
        Theme chosen=choose(brief);
        String request=brief.requirements();
        boolean counter=asks(request,"反例|不能编译|编译错误|边界|陷阱|counterexample|invalid");
        boolean depth=asks(request,"深入|原理|底层|推导|为什么|详细|深度|depth|in.depth|explain why");
        boolean transfer=asks(request,"迁移|挑战|变式|扩展|拓展|设计|项目|transfer|challenge");
        boolean firstQuestion=asks(request,"先(?:做题|练习|预测|考我)|自测优先|quiz first|practice first");
        boolean concrete=asks(request,"通俗|画面|例子|入门|直观|图示|生活|analogy|beginner|example");
        boolean accepted=!counter && chosen.producer();
        var themed=make(chosen,recipe,brief,depth,transfer,concrete,accepted);
        var portable=make(PUBLIC,recipe,new Brief("",""),depth,transfer || !request.isBlank(),concrete,accepted);
        EvidenceView card=topic.evidence().get(0);
        var entries=new ArrayList<Scene>();
        for (int i=0;i<themed.size();i++) {
            var local=themed.get(i);
            entries.add(new Scene(local.key(),local.kind(),local.text().title(),local.text().body(),local.text().prompt(),
                card.chunkId(),card.revision(),null,null,null,null,null,local.check(),portable.get(i).text()));
        }
        if(firstQuestion) java.util.Collections.swap(entries,0,1);
        var scenes=new ArrayList<>(base.scenes());
        // Preserve the real work that prompted remediation; keep custom practice beside its explanation.
        int position=base.strategy().equals("REPAIR") && scenes.get(0).attemptId()!=null?1:0;
        scenes.addAll(position,entries);
        var reasons=new ArrayList<>(base.reasons());
        reasons.add("本节使用你选择的应用情境设计演练；演练可自行核对，正式掌握记录仍来自课程判分。");
        return new PersonalLessonOutline.Draft(base.strategy(),base.pace(),List.copyOf(reasons),List.copyOf(scenes));
    }

    private static final String CLAUSES="[。！？.!?，,；;\n]";
    private static final Pattern NEGATIVE=Pattern.compile("不要|不想|不喜欢|不希望|避免|不需要|不用|别用|don't|do not|avoid|without",Pattern.CASE_INSENSITIVE);
    private static final Pattern INTEREST=Pattern.compile("喜欢|爱好|兴趣|球迷|希望用|请用|用.+(?:讲解|举例)|I (?:like|love|enjoy)|interested in",Pattern.CASE_INSENSITIVE);

    /** Only the explicitly named subject of an admitted preference may become a classroom theme. */
    static Brief profileInterest(List<Recall> facts) {
        for (var fact:facts) if (fact.kind().equals("MEMORY")) {
            for (String clause:fact.text().split(CLAUSES)) {
                if (!INTEREST.matcher(clause).find() || NEGATIVE.matcher(clause).find()) continue;
                for (var theme:THEMES) {
                    var subject=theme.words().matcher(clause);
                    if (subject.find()) return new Brief(subject.group(),"");
                }
            }
        }
        return null;
    }

    private static boolean asks(String request,String expression) {
        var pattern=Pattern.compile(expression,Pattern.CASE_INSENSITIVE);
        for(String clause:request.split(CLAUSES))
            if (!NEGATIVE.matcher(clause).find() && pattern.matcher(clause).find()) return true;
        return false;
    }

    private static Theme choose(Brief brief) {
        String source=brief.interest().isBlank()?brief.requirements():brief.interest();
        for(var t:THEMES) if(asks(source,t.words().pattern())) return t;
        if (brief.interest().isBlank() || NEGATIVE.matcher(brief.interest()).find()) return PUBLIC;
        // Unknown interests stay learner-defined: no external domain facts are inferred from their words.
        String label=brief.interest().replaceAll("\\s+"," ");
        String shortLabel=label.codePointCount(0,label.length())>20?label.substring(0,label.offsetByCodePoints(0,20))+"…":label;
        return theme("(?!)","自选项目「"+shortLabel+"」","Entry","FeaturedEntry","ReferenceEntry",
            "为「"+label+"」设计资料册。把一种需要重点处理的记录命名为 FeaturedEntry，另一种仅供参照的记录命名为 ReferenceEntry；为两类各举一个符合你兴趣的实例，再检查接口如何接收它们。",false);
    }

    private record Part(String key,String kind,Portable text,SelfCheck check) {}
    private static List<Part> make(Theme theme,Recipe r,Brief brief,boolean depth,boolean transfer,boolean concrete,boolean accepted) {
        String line=theme.fill(accepted?r.allowed():r.rejected());
        String other=theme.fill(accepted?r.rejected():r.allowed());
        String reason=theme.fill(accepted?r.allowedReason():r.rejectedReason());
        String reverse=theme.fill(accepted?r.rejectedReason():r.allowedReason());
        String setup=theme.fill(String.join("\n",List.of(r.declarations(),r.setup()).stream().filter(t->!t.isBlank()).toList()));
        String context=theme.task()+"\n"+theme.hierarchy();
        String caseBody=context+"\n\n"+theme.fill(r.rule())+(setup.isEmpty()?"":"\n\n本例声明\n"+setup);
        if(concrete) caseBody+="\n\n你可以画三张记录卡：在最上方写 "+theme.base()+"，下方并排写 "+theme.one()+" 和 "+theme.other()+"。在代码中逐个圈出对应的类型名，再用箭头标明哪一类记录交给哪个位置。";
        if(depth) caseBody+="\n\n推理顺序："+theme.fill(r.mechanism());
        String exercise=context+(setup.isEmpty()?"":"\n"+setup)+"\n\n待判断的一行\n"+line;
        String explanation=reason+"\n换成下面这一行，结论会改变：\n"+other+"\n"+reverse;
        var parts=new ArrayList<Part>();
        parts.add(new Part("application","READ",new Portable(theme.situation()+" · 类型建模",caseBody,
            "结合本例目标，指出需要保留的类型信息，再做紧接着的一题。"),null));
        parts.add(new Part("application-check","PREDICT",new Portable(theme.situation()+" · 判断编译结果",exercise,
            "在本页选择结果，核对理由后再进入课程练习。"),new SelfCheck(OPTIONS,accepted?0:1,explanation,r.javaSource(theme,accepted))));
        if(depth || transfer || !brief.requirements().isBlank()) {
            String task=theme.fill(r.transfer());
            if(!brief.requirements().isBlank()) task+="\n\n结合你写下的本节要求「"+brief.requirements()+"」，选定一个具体改动，把旧声明、新声明和预期的编译结果并排解释。";
            parts.add(new Part("application-transfer","REFLECT",new Portable(theme.situation()+" · "+(transfer?"设计变式":"解释边界"),
                task,depth?"写明成立条件、反例和判断阶段，最后指出这个例子的局限。":"写出改动前后的判断，并以本节知识卡核对。"),null));
        }
        return List.copyOf(parts);
    }
}
