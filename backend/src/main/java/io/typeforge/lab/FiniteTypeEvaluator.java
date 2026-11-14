package io.typeforge.lab;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** JLS 17 reference-type teaching subset, not a compiler and never executes learner input. */
@Component
public final class FiniteTypeEvaluator implements TypeRelationEvaluator {
    public static final String VERSION="jls17-finite-types-v1";
    private static final Set<String> NAMED=Set.of("Object","Number","Integer","Double","Float","Long","Short","Byte","String","CharSequence","Boolean","Character");
    private static final Set<String> GENERIC=Set.of("ArrayList","List","Collection","Iterable");
    private static final Map<String,String> PARENT=Map.ofEntries(Map.entry("Integer","Number"),Map.entry("Double","Number"),Map.entry("Float","Number"),Map.entry("Long","Number"),Map.entry("Short","Number"),Map.entry("Byte","Number"),Map.entry("Number","Object"),Map.entry("String","CharSequence"),Map.entry("CharSequence","Object"),Map.entry("Boolean","Object"),Map.entry("Character","Object"),Map.entry("ArrayList","List"),Map.entry("List","Collection"),Map.entry("Collection","Iterable"),Map.entry("Iterable","Object"));
    private static final Pattern PARAMETERIZED=Pattern.compile("([A-Za-z]+)\\s*<\\s*([^<>]+)\\s*>");
    public Evaluation assignment(String source,String target) {
        TypeExpression from=parse(source),to=parse(target);
        if(from==null || to==null || to instanceof Named n && n.name().equals("null")) return unsupported();
        return evaluate(from,to);
    }
    @Override public Evaluation evaluate(TypeExpression source,TypeExpression target) {
        if(!supported(source,true) || !supported(target,false)) return unsupported();
        if(source instanceof Named s && s.name().equals("null")) return result(true,"NULL_REFERENCE","null 可以赋给此引用类型；这不表示容器能写入其他值。");
        if(target instanceof Named t) {
            String sourceName=source instanceof Named s?s.name():((Parameterized)source).name();
            return result(subtype(sourceName,t.name()),"REFERENCE_WIDENING","依据固定名义类型层级判断引用拓宽；本实验不处理基本类型、自动装箱、强制转换或用户自定义继承。");
        }
        if(!(source instanceof Parameterized from)) return result(false,"REFERENCE_NOT_PARAMETERIZED","源引用没有目标参数化类型所需的具体约束，不能隐式向下转换。");
        Parameterized to=(Parameterized)target;
        boolean hierarchy=subtype(from.name(),to.name());
        boolean containment=contains(to.arguments().get(0),from.arguments().get(0));
        return result(hierarchy && containment,containment?"GENERIC_SUPERTYPE":"INVARIANCE_OR_CONTAINMENT","先检查容器层级，再检查类型实参包含关系。具体参数不随继承协变；通配符按上界或下界的包含方向判定。依据 JLS 4.5.1、4.10.2、5.2。");
    }
    public Evaluation readWrite(String container,String value,String operation) {
        TypeExpression from=parse(container),type=parse(value);
        if(!(from instanceof Parameterized p) || !(type instanceof Named n) || !supported(p,false) || !supported(n,true) || !Set.of("READ","WRITE").contains(operation)) return unsupported();
        if(operation.equals("WRITE") && p.name().equals("Iterable")) return result(false,"NO_COLLECTION_WRITE","Iterable 本身没有 add 操作；请使用 Collection、List 或 ArrayList。");
        TypeExpression argument=p.arguments().get(0);
        if(operation.equals("READ")) {
            if(n.name().equals("null")) return unsupported();
            String upper=argument instanceof Named a?a.name():((Wildcard)argument).bound()==Bound.EXTENDS?((Wildcard)argument).type().name():"Object";
            return result(subtype(upper,n.name()),"READ_UPPER_BOUND","读取结果只能保证其上界。? super T 与无界 ? 读取时保证 Object；? extends T 读取时保证 T。Collection 的读取指迭代器 next()。");
        }
        if(n.name().equals("null")) return result(true,"NULL_WRITE","类型系统允许向此容器传入 null；具体集合实现仍可能在运行时拒绝 null。本实验只判断静态类型。");
        if(argument instanceof Named a) return result(subtype(n.name(),a.name()),"CONCRETE_WRITE","写入值必须能赋给确定的元素类型；运行时是否可修改不属于本实验。");
        Wildcard wildcard=(Wildcard)argument;
        boolean allowed=wildcard.bound()==Bound.SUPER && subtype(n.name(),wildcard.type().name());
        return result(allowed,"WILDCARD_WRITE","? super T 接受 T 及其子类型；? extends T 与无界 ? 的未知捕获类型不能接收任意非 null 元素。依据 JLS 5.1.10。");
    }
    private static boolean supported(TypeExpression expression,boolean allowNull) {
        if(expression instanceof Named n) return NAMED.contains(n.name()) || allowNull && n.name().equals("null");
        if(expression instanceof Parameterized p) {
            if(!GENERIC.contains(p.name()) || p.arguments().size()!=1) return false;
            TypeExpression arg=p.arguments().get(0);
            if(arg instanceof Named n) return NAMED.contains(n.name());
            if(arg instanceof Wildcard w) return w.bound()!=null && (w.bound()==Bound.UNBOUNDED?w.type()==null:w.type()!=null && NAMED.contains(w.type().name()));
        }
        return false;
    }
    private static TypeExpression parse(String text) {
        if(text==null || text.length()>500) return null;
        String value=text.strip();
        if(NAMED.contains(value) || value.equals("null")) return new Named(value);
        var matcher=PARAMETERIZED.matcher(value);
        if(!matcher.matches() || !GENERIC.contains(matcher.group(1))) return null;
        String arg=matcher.group(2).strip();TypeExpression parsed;
        if(NAMED.contains(arg)) parsed=new Named(arg);
        else if(arg.equals("?")) parsed=new Wildcard(Bound.UNBOUNDED,null);
        else {
            var bound=Pattern.compile("\\?\\s+(extends|super)\\s+([A-Za-z]+)").matcher(arg);
            if(!bound.matches() || !NAMED.contains(bound.group(2))) return null;
            parsed=new Wildcard(bound.group(1).equals("extends")?Bound.EXTENDS:Bound.SUPER,new Named(bound.group(2)));
        }
        return new Parameterized(matcher.group(1),List.of(parsed));
    }
    private static boolean contains(TypeExpression target,TypeExpression source) {
        // Capture of ? super Object has both lower and upper bound Object for these
        // unbounded element declarations; javac treats it as the fixed Object argument.
        if(source instanceof Wildcard w && w.bound()==Bound.SUPER && w.type().name().equals("Object")) source=new Named("Object");
        if(target instanceof Wildcard w && w.bound()==Bound.SUPER && w.type().name().equals("Object")) target=new Named("Object");
        if(target.equals(source)) return true;
        if(!(target instanceof Wildcard t)) return false;
        if(t.bound()==Bound.UNBOUNDED || t.bound()==Bound.EXTENDS && t.type().name().equals("Object")) return true;
        if(source instanceof Named s) return t.bound()==Bound.EXTENDS?subtype(s.name(),t.type().name()):subtype(t.type().name(),s.name());
        Wildcard s=(Wildcard)source;
        if(t.bound()==Bound.EXTENDS && s.bound()==Bound.EXTENDS) return subtype(s.type().name(),t.type().name());
        return t.bound()==Bound.SUPER && s.bound()==Bound.SUPER && subtype(t.type().name(),s.type().name());
    }
    private static boolean subtype(String source,String target) {
        for(String current=source;current!=null;current=PARENT.get(current)) if(current.equals(target)) return true;
        return false;
    }
    public static Evaluation unsupported() {return new Evaluation(Verdict.UNSUPPORTED,"OUTSIDE_TEACHING_SUBSET","本版仅支持已列出的引用类型、单层 ArrayList/List/Collection/Iterable 与 ?/extends/super。数组、原始类型、嵌套参数、类型变量、推断、捕获修复及代码执行需后续编译沙箱；这里没有运行 javac。",VERSION);}
    private static Evaluation result(boolean allowed,String code,String explanation) {return new Evaluation(allowed?Verdict.ALLOWED:Verdict.DENIED,code,explanation,VERSION);}
}
