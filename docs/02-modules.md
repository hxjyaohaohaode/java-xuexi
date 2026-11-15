# 模块接手指南：已实现、未实现与下一步

本文件是继续开发的主入口。**当前为 v0.5 联动学习工作台：109 个公开操作都有实际路由实现，当前没有仅占位的 planned 操作。** 具体请求、响应和操作状态由[契约索引](../contracts/README.md)生成；最后一次真实运行结果见[验收记录](../evidence/runtime-review.md)。接口实现数量不等于完整产品完成率，运行时仍明确 `businessReady=false`。

`contracts/modules.json` 的 `partial` 表示模块已有实际子能力、产品范围仍有剩余；M11 的业务评测后台仍为 `planned`，已有文件型验证证据不等于该后台已经完成。A/B 是待两位真实成员认领的职责标签，不能当作已发生的个人贡献。外部模型和编译 worker 必须按部署能力启用，不能用协议夹具冒充真实服务验收。

## 一、逐模块当前范围

| 模块 | 当前实际能力 | 仍需完成的具体范围 | 首先读的实现 |
| --- | --- | --- | --- |
| M01 身份、权限、偏好 | 注册登录、CSRF、UUID/epoch 会话绑定；显示资料 CAS、改密、设备会话撤销、账号关闭；数据库原子认证限流、ETag 偏好；关闭经领域端口撤销任务与素材 | 邮件等可核验找回流程、面向运维的角色撤权界面、完整隐私擦除策略与多实例 Servlet 会话部署 | `identity/AccountService`、`AccountClosureService`、`AuthenticationLimit`、`PreferenceService` |
| M02 知识地图与阅读 | 固定 release 的概念、前置、知识卡、练习关联；阅读事件与按空间的历史恢复，旧真实事件回填 | 大规模地图交互、完整人工审核语料与教学可用性评测 | `curriculum/CurriculumService`、`learning/ReadingService` |
| M03 类型实验 | ASSIGNMENT/READ_WRITE 有限类型推演；边界、推断、捕获、擦除、原始类型改造、API 设计六组可编辑正反例；配置编译 worker 后运行真实编译检查与诊断 | 更广泛类型系统建模、反编译/桥接字节码与运行时实验、实际教学效果验证 | `lab/FiniteTypeEvaluator`、`CodeLabCatalog`、`LabService` |
| M04 编译诊断与沙箱 | 持久编译队列、配额、源码 hash、租约/超时/取消、公开诊断；独立 HTTP broker 每次任务用受限 Docker 容器执行固定 Java 17 编译，API JVM 不编译用户代码；独立 CI 已通过八项真实容器检查 | 生产宿主部署、持续容量与运维验收；任意程序执行、算法输出及运行时性能判定尚未开放 | `compiler/CompileService`、`CompileWorker`、`HttpCompileProvider`、`workers/compiler/` |
| M05 练习与判分 | 客观选项集合精确判分、版本及幂等、作答历史；CODE 题经审核的正向/反向编译契约检查后异步判分；多层提示及同题族受助记录 | TEXT 自由文本的可审核判分规则、运行时算法题、完整综合重构题集与人工校准 | `exercise/LearningService`、`CodeGradeConsumer`、`compiler/CodeRubric` |
| M06 学习证据与复习 | 独立题族证据、复习项、阅读活动、投影新鲜度；UTC 每日真实统计、相邻时间窗比较，空数据不伪造时长或掌握度 | 校准学习模型、个性化复习间隔和有实验证据的推荐策略 | `learning/LearningEvidenceProjection`、`operations/LearningAnalyticsService` |
| M07 数据集与加工 | 实际来源登记、原稿/清洗稿、规范化 hash 去重、采集详情与知识来源链；前端可录入和查看 | 批量文档解析、经过授权的自动抓取、相似去重评测及失败恢复工具 | `ingestion/ManualIngestionService`、前端 `Content` |
| M08 检索与证据 | 中英文边界 BM25、发布与概念过滤、实际片段/来源/hash、导师引用复验 | 向量混合检索、大语料索引维护、人工 gold set 和召回/排序效果评估 | `retrieval/LocalKnowledgeSearch` |
| M09 教学专家 | 持久会话/请求、EVIDENCE_ONLY 整理、显式配置 HTTP 模型；快照/租约/取消/配额/未知用量；回答转 DRAFT；教学工作流复用独立专家与审阅请求 | 真实外部模型教学质量、供应商兼容性与账单验收；任意工具调用和独立人工事实核验 | `tutor/TutorService`、`TutorWorker`、`StudyWorkflowService`、`TutorDocumentService` |
| M10 编辑审核与发布 | 图形内容工作台贯通来源、知识/题目草稿、编译题 rubric、独立审核、固定 hash、发布 CAS、历史激活与审计 | 批量审阅和内容差异展示、完整人工课程覆盖、发布前自动教学评测集 | `publishing/ContentDraftService`、`ReleaseWorkbenchService`、前端 `Content` |
| M11 协作评测与答辩 | 文件型测试/运行证据、三轮审查、CI、需求追踪和 AI 协作说明 | 产品内业务评测后台、真实对照教学实验、两位成员可核验贡献和最终答辩材料 | `evidence/`、`agent/*evaluation-cases.json` |
| M12 运行与安全 | V001–V011 升级链、错误与跟踪号边界、真实 health/capabilities、顺序 outbox、认证限流；管理员聚合队列与当前进程延时直方图；独立 MySQL 和 HTTP 重启验证入口 | 生产负载/SLO、告警、备份与恢复演练、完整隐私保留/擦除与运营流程 | `operations/`、`scripts/`、GitHub Actions |
| M13 专家编排与运行 | 持久 DAG、24 节点/3 并行、epoch/lease、预算和暂停/恢复/取消；EVIDENCE_ONLY 或已配置 MODEL 的受限 planner → 1–3 项 expert → review → DRAFT 链 | 任意工具的通用自治、动态权限与跨媒介调度、独立多专家质量对照、外部计费对账 | `orchestration/DurableAgentRuntime`、`PlanValidator`、`tutor/StudyWorkflowService` |
| M14 多模态作品工坊 | DSL 版本和传递来源；文档编辑、幻灯片画布/尺寸/键盘与预览；真实 MD/HTML/SVG/DOCX/PPTX/PDF；PNG/JPEG 内嵌；私有 PNG/JPEG/WAV/MP3/MP4 上传、预览、范围下载、配额和撤回 | 对象存储和 AV 扫描部署、生成式图像/TTS/视频、完整时间线渲染、局部智能重做与跨设备版式验收 | `artifacts/ArtifactService`、`ArtifactFileRenderer`、`ArtifactExportService`、`AssetService` |
| M15 工作空间与上下文 | 固定 release、目标编辑/归档、记忆撤回、顺序事件和跨页同步；显式选取较早的 1–8 个本人作答加入固定上下文 | 自动上下文策略、可检索长期记忆、多人协作、SSE/推送与大规模同步 | `workspace/WorkspaceService`、前端工作空间状态 |
| M16 交互错题与复练 | 从真实错误/辅助作答构建；服务端块版本检查；预测、引用、复练判分、多层提示与编译请求；可通过受限教学工作流生成复练草稿；静态 HTML | 六类块全部教学交互、经过审核的反事实变式、运行时修复和自由专家块编排；离线文件不记录平台成绩 | `notebook/NotebookService`、`NotebookActions`、`tutor/StudyWorkflowService` |

Java 路径相对于 `backend/src/main/java/io/typeforge/`，前端位于 `frontend/src/`。`/api/v1/capabilities` 的 `enabled`/`unavailable` 进一步报告当前部署配置；端点存在不能代替 worker、模型或静态前端资源真正可用。

## 二、接手时必须理解的跨模块约束

### 1. 发布版本和人工审核

工作空间创建时固定 `releaseId`。知识、题目、判分、检索、导师引用和错题块使用同一发布；新 revision 不覆盖已发布的行。新发布改变 `active_release` 指针，旧空间继续读取旧内容；历史激活不修改学生成绩。

来源登记不等于已下载或获授权抓取。M07 保存编辑实际提交的原稿与清洗稿；M10 作者不能自审，审核固定完整内容、答案、rubric 和来源 hash，发布时重新计算。demo 课程的代码审阅标签不能当作两位真人审核证据。发布由编辑锁和 `expectedActiveRevision` 控制，旧幂等请求不能重新激活旧版本。

### 2. 活动、辅助练习和独立掌握证据

阅读、预测、查看提示、类型实验、导师解释和生成作品都是活动，不会直接加掌握分。只有 M05 的服务端判分产生 `ATTEMPT_GRADED`。同题族首次独立作答形成一个样本；重复、反馈后和领取提示后的作答保留练习/辅助属性，不能覆盖首次错误或刷高独立样本。

CODE 判分验证经审核的 2–8 个正反编译检查，公共源码编译成功不等于题目通过。负例必须来自对应检查文件，并匹配审核允许的编译错误码；基础设施失败不等于学生答错。Notebook 的 `submitAnswer`、`requestHint`、`requestCompile` 复用同一业务服务，来源版本与归属重新验证。尚未配置编译 worker 或无审核 rubric 时明确返回不可用，不产生假成绩。

UTC 统计只聚合真实记录，包含重复/辅助题的正确率不是掌握率；有记录天数不是在线学习时长。阅读历史按空间保存，不把重新打开页面后的本地状态当事实来源。

### 3. 私人身份、关闭和可撤回来源

私人资源从真实账号 UUID 与会话 epoch 定位，不能仅凭可复用用户名认领历史数据。改密撤销全部会话；关闭通过 `AccountClosureParticipant` 在同一事务完成各模块撤回。历史学习证据和发布审计保留，上传素材和可再生成导出缓存移除；这是明确的保留策略，不声称全量物理擦除。曾获编辑/管理权限的用户名保持保留，防止旧启动配置误授权给新用户。

目标、记忆、作答、知识、作品和 ASSET 引用固定 kind/id/revision/hash。每版最多 64 条直接引用、256 条传递来源，每个来源最多 256 个仍有效的依赖版本；历史 STALE 版本不继续占有效额度。保存时限定容量，让后续撤回能在有界事务内完整传播。

修改目标、撤回记忆或素材、作品换版时，同事务标记受影响修订失效。自然过期在读取、动作、输出提交和下载时复验。旧来源闭包不完整返回 `LINEAGE_REBUILD_REQUIRED`，不能绕过检查；旧 Agent epoch/lease 无法覆盖学生新稿。

### 4. 受限教学工作流与真实模型验收

EVIDENCE_ONLY 从实际课程证据整理，不调用模型。MODEL 必须有服务端 URL、模型和密钥；HTTP 在事务外执行，禁止重定向，有正文和时间上限。浏览器不能选择任意 endpoint 或提高额度。

当前教学工作流的规划者只能选择 1–3 项 CONCEPT/EXAMPLE/REPAIR 分工，专家和审阅通过独立持久导师请求执行，最后形成 DOCUMENT/SLIDES/NOTEBOOK 草稿。模型文本没有新增工具权限。专家和审阅者可能使用同一模型，因此审阅不等于独立人工核验，作品保持 DRAFT；用户编辑优先于迟到生成稿。

请求固定上下文并预留额度。发送后无法确认的结果保留 UNKNOWN 与预留，不自动重发付费调用。暂停、取消和恢复使用新 epoch；不能承诺供应商已停止处理或退费。Token/时间预算已经执行，供应商金额账单还需真实价格与回执对账。外部教学质量、多专家收益和付费验收必须另做真实实验，不能用本地协议服务替代。

### 5. 用户代码只进入独立容器编译边界

API JVM 从不编译或运行用户输入。独立 broker 每个检查启动新的 Docker 容器，非 root、无网络、只读基础系统、丢弃 capabilities，限定 CPU/内存/PIDs/输出/时间并禁用注解处理。当前只允许 `COMPILE_ONLY`，不运行生成字节码；没有 Docker 时拒绝启动，不退回主机 `javac`。

队列、源码 hash、worker 协议、检查清单、lease、超时和取消共同保护结果。仓库内固定语义测试可在构建时使用 JavaCompiler，但不能把它当线上沙箱。本地执行环境没有 Docker；本轮已在独立 GitHub Actions 宿主通过八项真实容器检查，核对资源与隔离配置，并实际验证编译、取消、超时、输出限制和零残留清理，见[对应 CI 记录](https://github.com/hxjyaohaohaode/java-xuexi/actions/runs/43743602623/job/132901926094)。该结果不等于生产宿主部署、容量压测或通用逃逸认证；部署见 [worker 说明](../workers/compiler/README.md)。

### 6. 成功导出必须对应真实且仍可用的文件

文档可导出 Markdown、HTML、DOCX、PDF；幻灯片可导出 HTML、PPTX、PDF 并真实内嵌已授权 PNG/JPEG；关系图可导出受控 SVG；错题/交互作品支持静态 HTML。标题、内容、位置均来自固定源稿修订。无相应媒体生成器时返回明确不可用，不用空文件或假 URL 代替。

队列固定源版本、renderer 版本、输入 hash 与输出 SHA-256。领取及提交为短事务，渲染在事务外；成功状态和内容入库同事务。输出流、图片总分辨率、重复图片字节、待处理任务、日任务和账号缓存均有限额。过时缓存可回收，历史任务元信息保留；取消阻止迟到提交，不承诺瞬间打断本地库的计算。

下载重新鉴权并核对当前源稿和完整来源；旧源不再下载。动态文字转义，不执行源稿脚本、任意 URL 或命令。上传仅接受实际结构通过的格式，明确标注未做 AV 扫描。离线 HTML 不记录成绩，已下载文件不能由服务端追回。细节见[账户与素材](22-account-assets-operations.md)、[教学工作流与导出](23-study-and-exports.md)。

### 7. 页面不产生第二套业务状态

前端以服务端 workspace/release/revision/epoch 为范围，按持久事件刷新授权快照。轮询等待前次请求结束，切换空间取消旧请求并核对返回范围。编辑草稿独立保存，409/412 保留内容并要求合并，后台刷新不能覆盖输入；202 只表示接受任务，成功状态和真实结果到达后才展示下载或成绩。

交互需要兼顾键盘、触屏、小屏、焦点和减少动态设置。界面演示、单元测试与真实后端端到端验证是不同证据，不能互相替代。

## 三、下一阶段按可验收链路认领

| 优先级/建议责任 | 工作包与具体起点 | 通过门槛 |
| --- | --- | --- |
| P0 · A+B | 从干净 clone 复现当前 README，各自审查一条跨模块链 | 构建、真实浏览器、SQL/HTTP 重启、会话越权和来源撤回可复现，并记录各自真实判断 |
| P0 · A | 对已实现 MODEL 工作流配置真实供应商，建立人工标准问题集 | 记录规划/专家/审阅来源、引用、失败、取消、用量与供应商账单；教学效果不混同协议测试 |
| P0 · B | 在拟部署的生产 Docker 宿主部署现有独立编译 broker，复验隔离与容量 | 固定镜像 digest；网络、文件、CPU/内存/PIDs、超时、进程清理及生产负载实测；不开放主机编译降级，CI 结果不代替生产宿主验收 |
| P0 · A+B | 评估受限教学分工后，再扩展通用工具调度 | 每个新增工具具备权限、输入输出、预算、撤回与迟到结果门禁；与单 Agent/固定工作流做真实比较 |
| P1 · A | 扩充人工审核课程、CODE 正反例与检索 gold set | 实际第二编辑审核、正确/错误/边界解、题族独立性和检索质量可复算 |
| P1 · B | 在已有私有素材与文件导出之上实现图像/TTS/视频 worker | 真实文件与音轨/字幕/版式；幂等、取消、清理、费用及来源撤回全链贯通，不以静态预览冒充成品 |
| P1 · A+B | 补齐剩余实验与交互块、审核后的反事实变式 | 不泄露答案、不把辅助题当独立样本；键盘/触控恢复及跨版本/空间拒绝用例 |
| P1 · B，A 复核 | 生产会话、监控告警、数据保留/擦除、备份恢复和负载测量 | 明确环境与数据规模，给出错误率/P95/SLO、恢复演练与真实结果；不外推本地小样本 |
| P1 · A+B | 完成 M11 业务评测、八类课程材料和个人答辩 | 提交 SHA、两人真实贡献、AI 协作说明、失败修复和实际模型/媒体轨迹一致 |

优先级说明下一阶段应闭合的产品链路，不把尚未开放的任意执行能力描述成当前上线接口的安全缺陷。媒体生成仍是完整目标的一部分，必须以实际实现与验收完成，不能用本文替代。

## 四、改动与交付

先核对本模块写入责任及[数据规则](03-data.md)，再改实现；接口变更同步 OpenAPI、领域端口、前端 DTO、工具协议和消费者。只有具备真实行为及失败路径证据后，才更新状态与 capabilities。

执行 `python scripts/sync_contract_index.py` 刷新索引、模块标签、累计字段字典和操作追踪，然后完成静态、前后端、数据库及受影响端到端检查。V001–V011 是当前升级链；已经应用的迁移不回写，后续变更继续追加。计划、测试存在、测试通过、部署验收和最终教师验收须分别陈述。
