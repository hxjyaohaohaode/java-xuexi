# 知识体系、正确性与资料加工

## 1. 来源优先级

以 [JLS 17 类型章节](https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html)、[JLS 17 类与泛型方法](https://docs.oracle.com/javase/specs/jls/se17/html/jls-8.html)、[javac 17 文档](https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html) 为规则依据，以 Oracle 教程帮助教学表达。教程标注为 JDK 8，教学行为另用本项目 JDK 17 实测，不把教程当作“最新 Java”说明。后续博客只能作辅助来源，冲突回到规范和可复现代码。

`knowledge/sources.json` 是原始来源索引，**不是原始全文数据集**；当前未下载或发布整套教材/官方文档。`knowledge/concepts.json` 是本项目原创的精简种子；扩充时分别保存 raw（在许可允许范围内）、cleaned、cards、indexes，不能把来源网址列表充当已构建的检索知识库。

## 2. 概念组织

G01 类型安全动机 → G02 泛型类 / G03 泛型接口 → G04 泛型方法 → G05 类型推断。

G02 → G06 不变性 → G09 无界通配符 → G10 extends / G11 super → G12 PECS → G13 捕获。

G04 → G07 上界 → G08 多重边界。G02/G06 → G14 擦除与桥接 → G15 可具体化类型与数组 → G16 原始类型、堆污染与可变参数。图中前置关系用于学习排序，不表示语言定义只能以这一顺序理解。

## 3. 必须纠正的误讲

| 误讲 | 正确教学约束 | 验证方式 |
|---|---|---|
| Integer 是 Number，所以 List 也可直接转 | 泛型参数不变；相应 wildcard 关系另外判断 | 编译正反例 |
| `? extends T` 就是不可变集合 | 通常不能写入新非 null 元素；仍可 remove/clear，且取决于集合实现 | 可变 ArrayList 实例与编译验证 |
| `? super Integer` 能直接读成 Integer | 未额外缩窄时安全读取类型为 Object；可以写入 Integer 及子类型 | 编译正反例 |
| `List<?>` 与原始 List 一样 | `?` 保留类型安全约束，raw 会绕过部分检查并产生警告 | unchecked 警告与失败例 |
| 擦除后所有信息都消失 | 对象通常不保留具体实参，但声明可有 Signature 等元数据；擦除不是源码文本删尖括号 | `javap` / 反射声明元信息 |
| 所有 T 都擦除为 Object | 有界类型变量按最左界擦除 | 多重界与字节码 |
| 泛型能彻底消除 ClassCastException | raw/unchecked、反射等可突破边界；泛型不替代运行时输入校验 | raw 污染固定反例 |
| 泛型代码编译无错误就是安全 | 同时检查 unchecked/rawtypes 警告以及行为；不要掩盖 warning | `-Xlint` 与负向用例 |
| `new List<String>[n]` 可用 | 非可具体化元素的泛型数组创建受限；`new List<?>[n]` 情况不同 | 编译正反例 |
| `@SafeVarargs` 会自动检查类型安全 | 是作者责任声明且有适用限制；不能靠注解修复堆污染 | JLS/API 与反例 |
| 捕获能让两个 `List<?>` 的元素任意互换 | 每次捕获有自身未知类型，必须证明关系 | helper 成功与跨列表失败例 |
| 重载只要泛型实参不同就可区分 | 相同擦除签名会冲突，例如 List<String>/List<Integer> | 编译失败例 |

相关教学依据：[通配符使用准则](https://docs.oracle.com/javase/tutorial/java/generics/wildcardGuidelines.html)、[捕获与辅助方法](https://docs.oracle.com/javase/tutorial/java/generics/capture.html)、[桥接方法](https://docs.oracle.com/javase/tutorial/java/generics/bridgeMethods.html)、[泛型限制](https://docs.oracle.com/javase/tutorial/java/generics/restrictions.html)。例子均应自己编写；只引用必要的短文本并明确出处。

## 4. 知识卡字段

`id/conceptId/revision/title/claim/conditions/explanation/positiveExampleId/negativeExampleId/misconceptionTags/sourceRefs/javaRelease/reviewStatus/author/reviewer/contentHash`。来源包含 URL、章节、版本、访问日期、许可/使用方式、原始文件哈希（如实际保存）。未采集的哈希保持 null，不能用网址哈希冒充文件哈希。

知识库是经过精炼和组织的卡片及关系；数据集是加工前后材料；索引是卡片的可检索表示。三者有明确映射和可重建的流水线。

## 5. 题目与内容覆盖

最终目标 120 题：基础概念/语法 24、读写与赋值 28、编译诊断 24、签名补全 20、安全重构 16、综合设计 8。同一题的换变量名不算独立题。每个概念至少有 1 正例、1 反例、1 练习；PECS、捕获、擦除和 raw 风险要有跨概念综合题。

每题记录题型、concept 权重、家族 ID、前置、难度、版本、标准解释、隐藏测试、预期编译状态、warnings 策略、来源依据和 reviewer。通过 `questionFamily` 防止同题重复抬高掌握度。

## 6. 发布质量门禁

引用有效 → 概念图无环 → 条件表述完整 → 示例在指定 JDK 上验证 → 正反向判分测试 → 两人交叉核查 → 索引覆盖测试 → 发布快照。模型输出不是审核结论。编译器与讲解冲突时停止发布并定位版本、语法上下文和运行模式。

本次 `GenericSemanticsTest` 只验证固定案例，覆盖关键易错边界；无法替代未来所有教材和题库的逐项校验。种子也不能代替最终 64 卡、48 示例与全量题库。
