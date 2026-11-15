# 架构研究依据与采用决策

资料研究日期：2026-11-13；本章保留 2026-11-14 的 v0.4 研究与验证对照，正文的 v0.4、6 条迁移、69 张表和旧测试数均是历史基线，不是当前统计。v0.5 已扩展为11条迁移、79张表、受限专家学习任务、独立编译、账户/素材及原生文件导出，当前实现见 [模块指南](02-modules.md)、[新增实现](23-study-and-exports.md)与[本轮实测](../evidence/runtime-review.md)。只采用官方仓库、官方文档与论文作者资料；没有运行所列第三方完整平台，也没有把上游宣传效果当作 TypeForge 的实测结果。以下固定提交用于复核具体文件，main/latest 链接只注明当时阅读入口。

## 1 主要结论

初始 v0.2 设计包含 16 个模块、专家角色、任务图和作品协议，缺口主要是可执行一致性。v0.4 已把 Java 模块化服务、持久事件、受限运行器、可编辑作品源、正式内容发布和实际前端贯通。设计仍按任务决定是否需要多专家并行，以真实学习结果作为反馈，不增加与目标无关的角色和中间件。

当前研究不要求新增 LangGraph、Spring AI、Spring Modulith、MCP 或 A2A 运行依赖。它们提供可复核机制与未来适配边界；新增库必须和当前 Java 17/Spring Boot 3.5 技术栈匹配，并经过真实构建与行为测试。

| 研究机制 | v0.4 实际落点 | 尚不能据此宣称 |
|---|---|---|
| 持久事实与恢复 | V001–V006、69 张业务表，JDBC/Flyway、事件/outbox、回执和真实重启验证 | 任意保留表已有完整业务、已完成高并发容量评测 |
| 版本与证据 | 人工原稿/清洗收据、知识和题目版本、独立编辑审核、不可变发布清单及历史激活 | 已自动抓全网资料、已完成 64 卡/120 题的真人审阅 |
| 可恢复执行 | 计划 revision、租约/epoch、预算预留与结算；实际执行链按注册能力准入 | 模型会自主规划全部任务、所有专家和媒体已接通 |
| 模型适配 | `TutorAnswerProvider`、明确的证据整理模式和有配置前提的 HTTP 模型模式 | 模型质量已通过正式教学评测、一个配置开关即代表智能集群 |
| 统一源作品 | 版本化 DSL、真实输入闭包、编辑冲突、撤回/到期复验、Markdown/HTML/SVG 文件 | PPTX/DOCX/PDF、图像、音视频已有生产能力 |
| 学习反馈 | 实际判分、题目家族独立样本、辅助标记、错题动作和进度投影 | 阅读时间等于掌握、无样本也能输出精确能力概率 |

具体 API、证据与限制统一见[模块指南](02-modules.md)、[数据一致性](03-data.md)和[验收记录](../evidence/runtime-review.md)。这里保留研究选择及其代价，不建立第二套会与接口状态漂移的功能清单。

## 2 三个指定项目的具体迁移

### OpenMAIC

已读 main README，并通过 GitHub 插件复读固定提交 `65d5ac408301a2916d1d7e81b037855760a286ab` 的 `lib/server/agent-runtime/mutation-fence.ts` 与 `runner-contract.ts`。前者将取消信号传入变更过程并在前后检查，后者提供工具装配和课程 DSL 提示边界。

当前 README 描述了持久 Agent 会话、租约执行、server-side Pi director、作品场景和多种导出。其课程生成、播放、动作和存储分离，适合作为“共享源作品→多种表现”的结构参考。不能把历史文件中的 LangGraph 路径自动当成当前架构。[README](https://github.com/THU-MAIC/OpenMAIC/blob/main/README.md) · [取消屏障](https://github.com/THU-MAIC/OpenMAIC/blob/65d5ac408301a2916d1d7e81b037855760a286ab/lib/server/agent-runtime/mutation-fence.ts) · [运行装配](https://github.com/THU-MAIC/OpenMAIC/blob/65d5ac408301a2916d1d7e81b037855760a286ab/lib/server/agent-runtime/runner-contract.ts)

采用：固定上下文、可编辑源作品、局部变更、取消后再验证、能力按配置装配。TypeForge 在此之外仍需数据库 epoch/lease/CAS，单独的进程内取消信号不能覆盖崩溃、多个 worker 或学生并发编辑。

不照搬：Next/PostgreSQL 全栈、全套课堂界面和通用模型判题。本项目按课程使用 Java/MySQL，客观泛型问题由规范和真实编译证据裁决。根许可证为 MIT，但 README 明确 `mathml2omml` 等子包保有独立许可；直接复用文件时需逐项登记，而非只复制根 LICENSE。

### dsh-superagent-prototype

已读 main 的 README、`docs/001-domain.md`、`002-authorization.md`、`003-execution.md`、`007-architecture.md`。既有固定参考为 `8c32a9dc5d72c033816c6a8110b37f40cf94fba4`，本轮对公开文档做补充核对。

领域中 Source 产生 Trigger，Eval 依据 Policy 决定工作，Mandate 固定授权范围，Ticket 与 Assignment 分别管理任务和执行实例。上下文订阅可以向任务传递消息，但不会因此授予新权限。执行文档区分等待人、排队、执行、失败和恢复。[领域](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/main/docs/001-domain.md) · [授权](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/main/docs/002-authorization.md) · [执行](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/main/docs/003-execution.md)

采用：语义事件去重、已有任务接续、任务与模型会话分离、持久收件箱、并发限制和用户跟进优先。学习任务的命令范围由平台身份/资源权限决定，不由模型阅读资料中的指令决定。

不照搬：GitHub/飞书部署身份和以操作者 OS 用户执行命令的环境。上游明确工作目录与命令 wrapper 不是安全边界，并说明项目是研究原型、部分文章方向尚未实现；不能直接成为面向学生的代码执行系统。[项目声明](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/main/README.md)

### DeepSeek Harness

已读 main/master 公开 README 与 `docs/architecture.md`，并复读固定提交 `39163dd30f703a79079e2d2446545cff3c453738` 的 `docs/subsystems/tools.md` 前 140 行。架构把会话、工具、模型和 agent loop 分为可替换能力；区分持久 Session events、运行中 Agent events 与能力接入事件。

工具契约不仅描述输入，还强制 canonical output；host-only 的超时、执行和并发信息不会自动作为模型可见字段。只有显式声明并发安全的工具可参与并行；协作式取消无法硬杀同进程代码。[架构](https://github.com/deepseek-ai/deepseek-harness/blob/39163dd30f703a79079e2d2446545cff3c453738/docs/architecture.md) · [工具契约](https://github.com/deepseek-ai/deepseek-harness/blob/39163dd30f703a79079e2d2446545cff3c453738/docs/subsystems/tools.md)

采用：`AgentTool<I,O>` 静态组合与输入输出 Schema 双验证、模型可见字段白名单、并发默认排他、可回放的结构化结果。角色和工具能力是不同维度。

不照搬：整个 Cordis 插件运行时、开发者终端工具和动态安装能力。TypeForge 的工具注册只开放审核过的教学/作品操作，预算、身份和作品归属不能靠工具描述文字执行。

## 3 持久执行与事件一致性

### Spring Modulith

官方事件文档明确：把消费移到异步事务之后可以减小原事务，但监听器失败或进程在调用前崩溃会造成丢事件。Event Publication Registry 在原业务事务中为消费目标写记录，成功后完成，未完成可恢复。结构验证检查模块依赖无环、只访问公开接口以及允许的依赖关系。[事件](https://docs.spring.io/spring-modulith/reference/events.html) · [模块验证](https://docs.spring.io/spring-modulith/reference/verification.html)

采用：业务写入+outbox 原子性、消费回执+投影原子性、依赖规则可测试。先通过现有 Java/JDBC 体系落实；使用库时再选择和 Boot 3.5 兼容的版本。当前官方页面的 2.x 生命周期 API 不应未经兼容验证直接复制。

代价：每个事件增加存储与投递工作，需要重试、积压指标、保留和清理策略。收益是可恢复性与模块解耦；不能承诺网络层“恰好一次”。

### LangGraph

官方 Persistence 把单线程 checkpoint 与跨线程 store 分开；内存 saver 在重启后丢失。Fault tolerance 说明重试会重跑整个节点，checkpoint 不保护节点内外部副作用；幂等键必须在副作用前持久化，未收到确认可能是结果未知。[持久化](https://docs.langchain.com/oss/python/langgraph/persistence) · [恢复与幂等](https://docs.langchain.com/oss/python/langgraph/fault-tolerance)

采用：可恢复步骤边界、明确输入快照、有限重试、错误分类、外部作业对账。不要在一个节点里混合收费、保存作品、更新学习证据，再以“节点可重试”替代幂等设计。

不照搬：再增加一个 Python 控制面持有用户权限和任务真相。需要外部 Python/Node worker 时只暴露有界作业协议，任务、权限和账本仍归 Java 服务。

### MySQL 的读取隔离与准入锁

MySQL 8.4 文档区分一致性普通读与锁定读：默认 REPEATABLE READ 的普通读可以继续使用第一次普通读建立的快照，而 READ COMMITTED 的每次一致性读取得新的快照。[一致性读取](https://dev.mysql.com/doc/refman/8.4/en/innodb-consistent-read.html) · [锁定读取](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)

本轮代码审查发现一个实际适用点：资源归属查询可能发生在获取用户/工作空间行锁之前；即使后续成功取得锁，仍用旧普通读快照检查剩余额度，就可能重复准入。因此业务 TransactionTemplate 明确配置 READ_COMMITTED，并继续用统一行锁保护读改写边界。用两个独立事务和同步屏障验证锁后新读，不把 H2 默认设置或配置文本当成 MySQL 行为证明。这一选择要求未来新增写路径仍遵守同一锁协议，不能独立去掉锁。

v0.4 的真实 MySQL 8.4.13 门禁包含两项隔离回归：实际事务连接为 READ_COMMITTED，以及先普通读、再等待持锁者提交、最后普通读能看见新值；显式 REPEATABLE READ 负对照复现旧值。独立原始迁移检查覆盖 6 次迁移、69 张业务表及 119 项检查；内容服务另在该引擎运行 14 项业务集成测试。建表约束、隔离语义、业务事务和 HTTP 重启是不同证据，不能相互替代。结果和命令见[验收记录](../evidence/runtime-review.md)。

### 人工内容发布与授权锁

选择明确的人工原稿/清洗收据和独立审核，而不是让模型输出自动升级为正式课程。作者与另一位当前 EDITOR/ADMIN 账户分别写入不可变版本及审核决定；review hash 同时绑定标题、私有答案、来源与具体采集收据。release 构建冻结概念图和精确成员，发布时再次验证清单与 SQL 检索语料，再原子更新活动指针。学生空间继续固定原 release，历史激活不覆盖旧内容。实现及测试见[知识工作台](05-knowledge.md)。

代价是编辑流程需要真实人工劳动，当前粗粒度 `content_workbench_lock` 使写操作串行；收益是两人团队可以审查清楚版本和审核准入。启动授权也先取该编辑锁再取用户锁，避免多实例启动授权与审核插入作者外键形成反向等待。没有默认密码或默认编辑账户，运维配置只提升已有 ACTIVE 账号；真实会话与数据库角色决定权限，不接受模型或请求体声明身份。

## 4 模型、记忆与检索

Spring AI 的 ChatMemory 用于预算内上下文，不等于完整聊天历史；默认内存实现不提供重启持久性，官方还说明部分中间工具消息需单独保存。Structured Output 仍是 best effort，格式验证不能证明事实正确。RAG 支持元数据过滤和结果后处理；RelevancyEvaluator 测相关性，不是泛型语义裁判。[记忆](https://docs.spring.io/spring-ai/reference/api/chat-memory.html) · [结构化输出](https://docs.spring.io/spring-ai/reference/api/structured-output.html) · [RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html) · [评测](https://docs.spring.io/spring-ai/reference/api/testing.html)

采用四个边界：完整会话记录独立保存；任务状态来自运行库；学习事实带支持事件；明确偏好可更正删除。检索先按 owner/workspace/release 过滤，再做术语、符号与相似度排序。用户代码和私有材料不能因缓存或向量索引变为全站可见。

当前实际模型端口是 `TutorAnswerProvider`，检索端口为 `KnowledgeRetriever`。`HttpTutorModelProvider` 接收已准入的上下文与固定发布证据；M09 有持久排队、取消/租约、按用户 UTC 日 token 额度、派发标记和返回引用校验。未配置供应商时可用的是明确标识的 `EVIDENCE_ONLY`，`MODEL` 必须通过服务器配置与运行时能力检查；私密密钥不进入浏览器。未来采用 Spring AI 也应只替换适配层，领域对象不依赖具体模型 SDK。

实际 HTTP 协议适配与教学效果是两个验收对象。本地受控 HTTP 服务器可以验证请求、错误边界、用量、引用、重启和取消行为，不能证明商业模型在真实课程中的可靠性。已派发但结果未知的请求保留额度，不盲目重发；token 用量也不冒充核对后的货币账单。

来源有效性不是一次性检索完成就永久成立。v0.4 把 `ARTIFACT` 的直接清单、每级祖先索引、传递闭包、实际 JSON/hash 和非作品叶子状态统一放到工作空间层复验，最多 256 个源；因此 MEMORY 自然到期、目标修改或源版本变化不能经派生作品绕过检查。旧上下文、导师保存、复练动作及导出都复用该规则；文档/文件生成成功不代表未来一直允许下载。

## 5 多专家何时有收益

Google Research 与论文《Towards a Science of Scaling Agent Systems》比较多种拓扑，指出协作收益与可并行性、工具负担和错误传播有关；更多 Agent 不是单调提升。其测试结果只能说明研究条件，不能直接预测 TypeForge 成绩或学习效果。[作者研究说明](https://research.google/blog/towards-a-science-of-scaling-agent-systems-when-and-why-agent-systems-work/) · [论文](https://arxiv.org/abs/2512.08296)

采用：普通概念解释走短路径；独立证据检索和多媒介表达可并行；强依赖的类型推导保留连续上下文；由确定性证据和明确验收条件裁决分歧。专家之间传结构化引用与结论，避免全部对话广播给全部角色。

评测固定模型版本、任务输入与预算，比较单 Agent、固定协作、动态编排的任务成功率、事实错误、外部工具浪费、首个可用产物时间、总费用和独立复练结果。没有实际模型评测就不宣称动态编排更聪明。

## 6 学习记忆与复练算法

FSRS 将复习状态表示为难度、稳定性和可提取性，适合安排卡片复习。它不自动测量程序设计迁移能力；当前版本及参数需要固定，并基于实际复习历史校准。[算法固定页面](https://github.com/open-spaced-repetition/fsrs4anki/wiki/The-Algorithm/bccece8e7888c8121784ef9d9e8022d4961baf83) · [项目](https://github.com/open-spaced-repetition/fsrs4anki)

simpleKT 研究说明知识追踪方法的复杂度不直接等于预测优势，也指出评测协议不一致会使比较失真。[作者页面](https://pykt.org/simplekt)

采用：先保存独立/辅助作答、题目版本、概念映射、真实结果和时间，展示可解释的证据。复练建议从错误、近期独立样本与前置知识形成；样本不足时不输出未经校准的精确能力概率。未来比较 KT 模型时按学生和时间隔离，避免同题变式及未来行为泄漏。FSRS、KT 和模型生成解释是不同能力，分别评测。

## 7 Java事实与安全编译

JLS 规定泛型擦除、可具体化类型、类型变量及堆污染等语义。教学内容应绑定本项目 JDK 17，固定样例以实际 javac 编译正例、反例与警告验证。Oracle `javac` 文档说明 annotation processing 可通过 `-proc:none` 禁用。[JLS17](https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html) · [javac17](https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html)

采用：规范来源→可复现样例→结构化诊断→解释的证据链。编译通过不等于答案正确；有 warning 不等于编译失败；运行异常和类型检查错误分开。`? extends`、`? super` 的可写/可读限制必须结合捕获和实际操作，不能只背口诀。v0.4 的有限类型实验只处理明确支持的类型/操作，使用固定样例与真实 javac 对照；范围外返回不支持，不把有限规则引擎标成完整 Java 编译器。

在线编译和运行用户输入属于不可信任务，不能套用测试中的 JavaCompiler 帮助器。Docker 官方明确默认容器没有资源上限；无网络、rootless、seccomp 等是不同控制面，需显式配置和测试。[资源限制](https://docs.docker.com/engine/containers/resource_constraints/) · [无网络](https://docs.docker.com/engine/network/drivers/none/) · [Rootless](https://docs.docker.com/engine/security/rootless/) · [Seccomp](https://docs.docker.com/engine/security/seccomp/)

要求：独立 worker、固定工具链和 classpath、禁止注解处理器、源文件/输出/时间上限、非 root、无主服务凭据、禁外网、只读基础环境与受限临时目录。仅 `ProcessBuilder`、线程超时或工作目录隔离不能被标为安全沙箱。没有完成隔离验收前不开放任意用户代码运行。

## 8 MCP和A2A的边界

MCP 面向工具与资源接入；官方安全文档讨论 token audience、confused deputy、SSRF 与状态句柄劫持。工具 annotations 是行为提示，不能由不可信服务的自报信息直接授予权限。2026 年协议仍在演进，集成时固定版本与实现能力。[安全实践](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices) · [Annotations 边界](https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/)

A2A 定义跨 Agent 的消息、任务、状态与产物协议，适合未来独立 Agent 服务互操作。[规范](https://a2a-protocol.org/latest/specification/) · [固定版本参照](https://a2a-protocol.org/v0.3.0/specification/)

采用：能力发现、显式任务句柄、结构化状态与产物的设计思想。不把内部 Java 方法调用全部变成 MCP/A2A 网络调用，也不宣称协议本身提高智能。第三方工具调用仍走用户归属、任务权限、预算、schema、幂等与输出证据检查。未实现协议握手和互操作测试时仅保留适配端口。

## 9 可观测性与成本

Spring AI 的观测覆盖 ChatClient、模型、向量存储等调用；指标与 trace 对基数字段的处理不同。OpenTelemetry GenAI registry 当前提示部分约定已迁移，接入时应固定库与语义约定版本，避免盲复制过时字段。[Spring AI 观测](https://docs.spring.io/spring-ai/reference/observability/) · [OpenTelemetry registry](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/)

TypeForge 优先记录 run/step/event/revision 的关联、错误类型、调用耗时、token 与费用。完整私人材料和模型输入输出默认不进入日志。M13 的整数微美元预算与 M09 的日 token 准入分别记账，不能从 token 总数直接声称货币账单已结清。多模型、多媒体和广泛工具调用使成本更难预测，后续继续使用有界预算预留、局部重做和已有证据/素材复用；预算不是可放任任意重试的目标消耗量。

## 10 采用决策汇总

| 决策 | 采用内容 | 暂不引入或不照搬 | 验证证据 |
|---|---|---|---|
| 模块化 Java 控制服务 | 公开端口、依赖规则、事务协调 | 按角色拆微服务 | 架构检查、业务集成测试 |
| 持久事件 | outbox、回执、可重放投影 | 只依赖内存广播、承诺 exactly-once | 重复消费与失败恢复 |
| 持久执行 | lease/epoch、有限重试、外部对账 | 仅静态 DAG、无边界模型循环 | 旧租约/取消/重规划/预算竞争 |
| 多专家 | 按任务选择、结构化事实共享的编排原则 | 每次激活全部角色 | 同预算真实模型对照评测仍待开展 |
| 内容发布 | 人工收据、作者与独立编辑、完整清单、活动指针 CAS | demo 标签或模型文本冒充真人批准 | 14 项内容集成在 H2/真实 MySQL；并发发布及历史引用 |
| 统一作品源 | 不可变 revision、完整来源闭包、手工编辑 | 各媒介重新编造结论 | 版本冲突、自然到期、缺失索引、JSON 篡改、受支持格式真实导出 |
| 学习记忆 | 可解释证据、可撤回偏好 | 从停留时长推断能力、无样本精确打分 | 独立/辅助/失败分类 |
| 工具协议 | 泛型端口、双 schema、host 执行控制 | 工具注释充当权限或沙箱 | 越权/非法输出/取消负例 |
| 第三方框架 | 针对实际需求选择适配器 | 同时引入全部流行框架 | 新增前须锁版本、兼容构建与真实调用；当前没有引入这些框架 |

本轮没有把目标降为静态设计，也没有把已实现子能力扩张为完整终局。自主多专家规划、任意源码安全沙箱、完整媒体生产、更多二进制导出、全面教材与模型效果评测仍有明确剩余工作；可运行服务、真实文件、来源校验和人工发布则已有各自实现与验证入口。

本次研究没有直接复制三仓库实现。后续复用源码应记录仓库、commit、文件、许可证和修改说明；根许可、子包许可、字体图像及模型服务使用条件分别核查。可读到源码不代表第三方全部功能通过审计。
