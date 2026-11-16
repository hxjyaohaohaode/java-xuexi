# 知识体系、人工资料加工与正式发布

本章对应 v0.6 的真实内容工作台，已增加前端来源、卡片/题目、审核与发布控制台。M07 登记来源、人工采集收据及有界 TXT/MD/DOCX 批量预览与原子采集；M10 追加知识/题目版本、执行独立编辑审核、构建并发布课程，并支持完整修订对比与独立审核的 TEXT 精确规则。流水线可运行，但最终课程数量、逐项语义验证和真实学生的人工审阅仍需两位作者持续完成。

## 1. 来源优先级

以 [JLS 17 类型章节](https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html)、[JLS 17 类与泛型方法](https://docs.oracle.com/javase/specs/jls/se17/html/jls-8.html)、[javac 17 文档](https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html) 为规则依据，以 Oracle 教程帮助教学表达。教程标注为 JDK 8，教学行为另用本项目 JDK 17 实测，不把教程当作“最新 Java”说明。后续博客只能作辅助来源，冲突回到规范和可复现代码。

`knowledge/sources.json` 是原始来源索引，**不是原始全文数据集**；当前未下载或发布整套教材/官方文档。`knowledge/concepts.json` 是原创概念种子，`knowledge/runtime-lessons.json` 含 16 张原创知识卡、12 道客观题与补充规范来源。显式 `demo` Profile 把这些内容写入实际发布成员表，供本地学习、检索和判分开发使用；它没有真实用户的独立审核记录。

正式内容可以从当前工作台录入：先登记来源，再提交实际原稿、人工清洗稿和处理说明，随后建立知识/题目版本并由另一位已授权编辑审核。服务器对实际提交内容计算哈希，保存不可换绑的采集收据，发布后由 `LocalKnowledgeSearch` 从该 release 的真实 SQL 语料执行 BM25。**已实现人工录入、TXT/MD/DOCX 批量解析与清洗确认，没有自动网页抓取、PDF 解析、自动语义清洗或向量建库。** 登记 URL 不发出外部请求；手工原稿的哈希不证明服务器曾下载过整个来源页面。

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
| 所有带类型实参的 instanceof 在 Java 17 都非法 | Java 16 起放宽部分合法细化；静态类型为 `List<Integer>` 时可检查 `ArrayList<Integer>`，不能据此宣称运行时逐项核验泛型实参 | Java 15/17 对照编译与 [JLS 17 §15.20.2](https://docs.oracle.com/javase/specs/jls/se17/html/jls-15.html#jls-15.20.2) |
| `@SafeVarargs` 会自动检查类型安全 | 是作者责任声明且有适用限制；不能靠注解修复堆污染 | JLS/API 与反例 |
| 捕获能让两个 `List<?>` 的元素任意互换 | 每次捕获有自身未知类型，必须证明关系 | helper 成功与跨列表失败例 |
| 重载只要泛型实参不同就可区分 | 相同擦除签名会冲突，例如 List<String>/List<Integer> | 编译失败例 |

相关教学依据：[通配符使用准则](https://docs.oracle.com/javase/tutorial/java/generics/wildcardGuidelines.html)、[捕获与辅助方法](https://docs.oracle.com/javase/tutorial/java/generics/capture.html)、[桥接方法](https://docs.oracle.com/javase/tutorial/java/generics/bridgeMethods.html)、[泛型限制](https://docs.oracle.com/javase/tutorial/java/generics/restrictions.html)。例子均应自己编写；只引用必要的短文本并明确出处。

## 4. 实际内容契约与加工凭据

完整字段以 [OpenAPI](../contracts/openapi.json) 和[数据字典](../database/data-dictionary.md)为准。当前知识正文采用 Markdown，尚未把所有教学栏目拆为独立字段；以下区分真实数据结构与写作要求。

| 对象 | 当前保存内容 | 约束与用途 |
|---|---|---|
| 来源 | `title / url / edition / usageNote` | HTTPS 元数据登记，拒绝含账号口令的 URL；需要变更时新建来源记录 |
| 采集收据 | `originalMarkdown / cleanedMarkdown / processingNote`、两份实际内容哈希、录入者与时间 | 原稿最多 32,000 字符、清洗稿最多 16,000 字符；换行规范化但保留代码缩进；同来源和两份哈希完全相同则去重 |
| 概念 | 稳定 UUID、唯一 code、title、前置概念列表 | 概念图供课程构建冻结；发布时验证完整前置闭包 |
| 知识版本 | `id / revision / baseRevision / conceptId / title / body / sources` | 每个来源项绑定 `sourceId / section / ingestionId`；不能拿另一来源的采集收据替代 |
| 题目版本 | `id / revision / baseRevision / familyId / kind / difficulty / prompt / conceptIds / answerSpec / knowledgeRefs` | `SINGLE / MULTIPLE` 客观选择、`SIGNATURE / REFACTOR / FILL` 代码契约与 `READING / DIAGNOSIS` 短文本精确规则；题目家族不随修改重置；知识精确引用 id+revision |
| 审核决定 | `targetKind / targetId / targetRevision / expectedContentHash / decision / note` | 服务端填写作者、真实会话审核人、角色证据与时间；不能由请求指定审核身份 |
| 发布清单 | 概念图快照、精确知识/题目版本、review hash、审核记录引用、检查器与检索版本 | 清单确定性哈希；不包含公开可读的标准答案明文 |

知识的 `contentHash` 保护实际正文；编辑界面的 `reviewHash` 还绑定标题、作者、来源/章节/收据，或题目完整私有答案、概念权重及支持知识。审核时必须提交当前 `reviewHash`，不能把只覆盖正文的 hash 当成完整审核身份。未实际采集的来源 hash 保持空，不能用网址哈希冒充原文哈希。

每张正式知识卡的正文仍应写明：规则主张、成立条件、Java 17 适用范围、解释、原创正例、原创反例、易错点、来源章节与验证方式。`positiveExampleId / negativeExampleId / misconceptionTags` 等未来结构化字段和逐卡编译证据关联还需要扩展；当前不声称这些字段或自动校验已经存在。

知识库是组织后的卡片及关系；资料集是原稿与清洗稿；索引是固定 release 的可检索表示。三者通过收据、版本和发布成员建立联系，不能以 URL 列表或一条 `READY` 标签代替实际内容。

## 5. 题目与内容覆盖

最终目标仍为 **64 张知识卡、48 个示例和 120 题**，不是 v0.6 已完成数量。题目目标分布：基础概念/语法 24、读写与赋值 28、编译诊断 24、签名补全 20、安全重构 16、综合设计 8。同一题换变量名不算独立题。每个概念至少有 1 正例、1 反例、1 练习；PECS、捕获、擦除和 raw 风险要有跨概念综合题。

当前编辑器和判分器支持单选/多选、受独立编译能力约束的代码题，以及有独立审核凭证的短文本精确规则。选择题提供 2–8 个可见选项；代码契约、文本接受/拒绝答案、标准选项、解释与误区标签只存服务端。每题最多 16 个概念，权重精确分配且合计 1。每题必须引用可解释其概念的知识版本，审核题目前这些知识必须已独立批准。公开课程 DTO 只选取题干和可见选项，不返回答案、私有解释或审核身份；判分后按本人结果展示反馈。

题目家族用于独立样本去重，避免同题反复作答刷高掌握度。编辑可从实际题目派生同题族新练习；新 ID 仍须独立审核并进入同一发布，才能成为反事实候选。代码题使用隐藏编译正反例和预期 warnings，经独立 COMPILE_ONLY worker 判定；未配置 worker 时明确不可用。TEXT 采用 NFKC、空白折叠及可选大小写规范化后的完整字符串匹配，不宣称开放语义理解。任意用户程序执行仍未开放。

## 6. 已实现的独立审核与发布事务

| 步骤 | 实际行为 | 失败后处理 |
|---|---|---|
| 创建草稿 | `revision = baseRevision + 1`，追加正文或题目内容 | 版本冲突保留本地稿，重新读取再合并 |
| 审核知识 | 当前 EDITOR/ADMIN 且不是作者；核对来源收据与完整 review hash | 自审拒绝；预览后内容变化拒绝 |
| 审核题目 | 除身份/hash 外，支持知识必须已有独立批准 | 标准答案或知识版本变化要新稿和新审核 |
| 给出决定 | `APPROVED` 把草稿转 `REVIEWED`；`REJECTED` 转 `RETIRED` | 每个目标版本只有一次终态决定；拒绝后修改须新 revision |
| 构建 release | 固定概念图，验证无环/前置知识覆盖、所有精确成员已批准、题目知识在本发布、权重合计 1 | 不生成半成品清单；demo 状态标签不满足审核证据 |
| 发布 | 复验审核/来源/答案/清单，验证 SQL 检索语料记录，再 CAS 活动课程指针 | 事务整体回滚；并发发布只有匹配指针版本的一方成功 |
| 历史激活 | 仅激活已发布的正式 release，再做同一完整性检查 | 不改原发布时间、原成员、旧 workspace 的 release |

当前“双人审核”指**一位作者与另一位授权编辑**。两人可交换作者/审核者角色，但同一修订不能自审；系统记录的是真实登录账户操作，不保证两账号背后一定是两位不同自然人。注册只获 LEARNER，编辑角色由明确的运维配置提升已存在 ACTIVE 账号；旧会话每次访问仍复查数据库角色。配置步骤见[后端运行说明](../backend/README.md)。

发布构建一次接受 1–1,000 个知识引用、1–1,000 个题目引用和最多 100 个概念闭包，不能把小范围正式发布误称为完整课程验收。发布清单的 `indexVersion` 对应当前词项 BM25；SQL corpus 的 `READY` 意味着已核对该清单与可读取成员，不意味经过向量构建、自动教学评测或第三方检索效果认证。

正式发布必须另由作者与审核者落实教学质量：条件表述完整、JDK 17 正反例和 warnings 正确、解释与判分一致。系统当前自动保证版本、身份、完整性和引用关系，**不会在每次发布时自动编译任意卡片示例，也不会替代人工判断教学结论**。模型输出不是审核结论；编译器与讲解冲突时应停止发布并定位版本、语法上下文及运行模式。

## 7. 验证与当前剩余范围

`GenericSemanticsTest`、`GenericEdgeCasesTest` 验证仓库固定案例和版本差异；有限类型实验另有与实际 javac 的固定样例对照，范围之外返回不支持。它们不等于所有用户输入和未来教材都通过语言语义验收。`KnowledgeRuntimeTest` 检验真实发布读取、检索、来源哈希及答案边界。

[ContentWorkbenchIntegrationTest](../backend/src/test/java/io/typeforge/publishing/ContentWorkbenchIntegrationTest.java) 的集成用例覆盖真实登录与权限、人工收据去重/换绑、自审拒绝、审核 hash、非法私有答案、demo 不可冒充人审、前置覆盖、并发 CAS、完整性失败回滚、历史激活和旧课程不被覆盖。默认全套使用隔离 H2；显式 MySQL 门禁在专用数据库重跑同一组事务，核验实际 JDBC 引擎并恢复原活动指针。具体引擎、次数和结果统一见[本轮验收记录](../evidence/runtime-review.md)，不以测试类存在推定通过。

演示课程明示 `origin=demo-authored-code-reviewed`、`formalHumanReview=false`，不生成 `content_review`。正式工作台虽已可用，仓库并没有因此凭空新增两位学生的真人审核、完整 120 题或最终课程质量证据。当前已支持 TXT/MD/DOCX 批量导入和知识/练习字段级修订对比，新增回归覆盖解析边界、原子回滚、版本及隐私。自动网页采集、PDF/OCR、模型清洗、语义近重复识别、批量审核和全量教材自动验证仍待完成；接下来的开发应沿真实收据和版本链扩展这些能力。
