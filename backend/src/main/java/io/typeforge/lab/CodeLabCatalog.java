package io.typeforge.lab;

import java.util.List;

/** Original, fixed teaching fixtures. Build-time tests compile both sides; learner exploration never changes mastery. */
public final class CodeLabCatalog {
    public record Example(String id,String labType,String title,String description,String positiveSource,String negativeSource,String observation,String sourceUrl) {}
    private CodeLabCatalog() {}
    public static List<Example> examples(){return List.of(
        new Example("bounds","BOUNDS","多重边界","观察类边界与接口边界的顺序，以及调用时必须同时满足的约束。",
            """
            public class Main {
                static <T extends Number & Comparable<T>> T max(T a, T b) {
                    return a.compareTo(b) >= 0 ? a : b;
                }
                static Integer example() { return max(1, 2); }
            }
            """,
            """
            public class Main {
                static <T extends Comparable<T> & Number> T max(T a, T b) {
                    return a.compareTo(b) >= 0 ? a : b;
                }
            }
            ""","把类边界 Number 移到首位，再解释为什么接口可以跟在类之后。这里观察编译约束，不执行 max。","https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html#jls-4.4"),
        new Example("inference","INFERENCE","类型推断","比较实参约束与赋值目标是否能由同一个类型参数同时满足。",
            """
            public class Main {
                static <T> T identity(T value) { return value; }
                static Integer example() { return identity(1); }
            }
            """,
            """
            public class Main {
                static <T> T identity(T value) { return value; }
                static String example() { return identity(1); }
            }
            ""","正例保留 Integer。反例试图把同一个调用结果赋给 String；对照诊断中的推断边界。","https://docs.oracle.com/javase/specs/jls/se17/html/jls-18.html"),
        new Example("capture","CAPTURE","通配符捕获修复","把未知元素类型绑定到辅助方法的类型参数，让一次操作共享同一个类型。",
            """
            public class Main {
                static void replaceFirst(java.util.List<?> values) { replace(values); }
                private static <T> void replace(java.util.List<T> values) {
                    values.set(0, values.get(0));
                }
            }
            """,
            """
            public class Main {
                static void replaceFirst(java.util.List<?> values) {
                    values.set(0, values.get(0));
                }
            }
            ""","将 get 与 set 放到同一个 <T> 辅助方法，观察捕获问题如何消失；空列表等运行时条件不在本次编译核验范围。","https://docs.oracle.com/javase/specs/jls/se17/html/jls-5.html#jls-5.1.10"),
        new Example("erasure","ERASURE","擦除后的签名冲突","比较仅靠泛型实参区分的方法与显式使用不同名称的方法。",
            """
            public class Main {
                static void acceptStrings(java.util.List<String> values) { }
                static void acceptIntegers(java.util.List<Integer> values) { }
            }
            """,
            """
            public class Main {
                static void accept(java.util.List<String> values) { }
                static void accept(java.util.List<Integer> values) { }
            }
            ""","两个 List 类型实参擦除后都留下 List，不能仅靠 String/Integer 实参实现这种重载。这里核验签名冲突，不声称已经展示反编译或桥接字节码。","https://docs.oracle.com/javase/specs/jls/se17/html/jls-8.html#jls-8.4.8.3"),
        new Example("raw-refactor","RAW_REFACTOR","原始类型安全改造","将原始容器恢复成有元素约束的声明，保留编译器对写入与读取的检查。",
            """
            public class Main {
                static String first() {
                    java.util.List<String> values = new java.util.ArrayList<>();
                    values.add("generic");
                    return values.get(0);
                }
            }
            """,
            """
            public class Main {
                static String first() {
                    java.util.List values = new java.util.ArrayList();
                    values.add(1);
                    return (String) values.get(0);
                }
            }
            ""","反例含 raw/unchecked 警告；本工作台使用 -Xlint:all -Werror，因此严格编译会拒绝。编译拒绝不等同于已观察到运行时异常。","https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html#jls-4.8"),
        new Example("api-design","API_DESIGN","PECS API 签名","让来源提供 T，让目标接收 T；对照颠倒方向后的读写限制。",
            """
            public class Main {
                static <T> void copy(java.util.List<? extends T> source,
                                     java.util.List<? super T> target) {
                    for (T value : source) target.add(value);
                }
            }
            """,
            """
            public class Main {
                static <T> void copy(java.util.List<? super T> source,
                                     java.util.List<? extends T> target) {
                    for (T value : source) target.add(value);
                }
            }
            ""","分别解释读取 source 保证的上界与写入 target 需要的下界，修改签名后核验诊断。集合是否可修改属于另一层运行时约束。","https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html#jls-4.5.1")
    );}
}
