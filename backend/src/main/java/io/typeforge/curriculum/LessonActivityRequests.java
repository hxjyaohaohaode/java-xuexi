package io.typeforge.curriculum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Closed instructional vocabulary, derived solely from the learner's frozen requirements. */
public record LessonActivityRequests(List<String> required,List<String> declined) {
    public static final List<String> ACTIVITIES=List.of("TRACE_TYPES","CHECK_COMPILE","COUNTEREXAMPLE","TRANSFER");
    public static final String OMIT="NO_EXTRA_ACTIVITY";
    private static final Pattern CLAUSE=Pattern.compile("[。！？.!?，,；;\\n]|但(?:是)?|而是|不过|\\b(?:but|instead)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD=Pattern.compile(
        "(?<no>不(?:要|想|喜欢|希望|需要|用|必)|别(?:再|让|给|用|讲|做|出)?|避免|跳过|省略|取消|无需|拒绝(?:做|看)|\\b(?:no|not|don't|skip|omit|exclude|avoid|without)\\b)"
        +"|(?<yes>改为|换成|然后(?:请|给|让)|再(?:请|给|让我)|只(?:要|想看|需要))"
        +"|(?<trace>类型推导|推导|类型推理|逐步分析类型|type tracing|type inference)"
        +"|(?<predict>预测|预判|先猜|猜一猜|判断(?:能否|是否|能不能)编译|predict(?:ion|ing|s)?|quiz first)"
        +"|(?<counter>反例|不能编译|编译错误的(?:例子|示例)|counter[ -]?examples?)"
        +"|(?<transfer>迁移|变式|换个情境|拓展题|扩展题|挑战题|transfer|variations?)",Pattern.CASE_INSENSITIVE);

    public LessonActivityRequests {
        required=List.copyOf(required);declined=List.copyOf(declined);
    }

    public static LessonActivityRequests parse(String text) {
        var mentions=new LinkedHashMap<String,Boolean>();
        for(String clause:CLAUSE.split(text==null?"":text)) {
            boolean negative=false;
            var words=WORD.matcher(clause);
            while(words.find()) {
                if(words.group("no")!=null) {negative=true;continue;}
                if(words.group("yes")!=null) {negative=false;continue;}
                String move=words.group("trace")!=null?"TRACE_TYPES":words.group("predict")!=null?"CHECK_COMPILE":
                    words.group("counter")!=null?"COUNTEREXAMPLE":"TRANSFER";
                Boolean before=mentions.get(move);
                if(before!=null && before!=negative) mentions.remove(move);
                mentions.put(move,negative);
            }
        }
        var required=new ArrayList<String>();
        var declined=new ArrayList<String>();
        mentions.forEach((move,negative)->(negative?declined:required).add(move));
        return new LessonActivityRequests(required,declined);
    }

    /** Explicit activities keep learner order. Remaining slots share the other available moves. */
    public List<String> optionsFor(int index,int slots) {
        if(index<0 || index>=slots) throw new IllegalArgumentException("Invalid review slot");
        if(index<required.size()) return List.of(required.get(index));
        var allowed=ACTIVITIES.stream().filter(a->!declined.contains(a)).toList();
        if(allowed.isEmpty()) return List.of(OMIT);
        var other=allowed.stream().filter(a->!required.contains(a)).toList();
        if(other.isEmpty()) return allowed;
        int spare=slots-required.size(),offset=index-required.size();
        var choices=new ArrayList<String>();
        for(int i=offset;i<other.size();i+=spare) choices.add(other.get(i));
        if(choices.isEmpty()) choices.add(other.get(offset%other.size()));
        return List.copyOf(choices);
    }
}
