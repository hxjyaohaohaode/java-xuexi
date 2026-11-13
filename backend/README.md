# Java 后端运行时与当前边界

Java 17 / Spring Boot 3.5.16 / Maven。工程版本为 `0.3.0-SNAPSHOT`，编译启用 `-Xlint:all -Werror`。本版已实现 JDBC 持久化服务、Flyway 迁移、会话认证与部分业务 HTTP 链路；操作和模块状态分别以 [OpenAPI](../contracts/openapi.json) 与 [modules.json](../contracts/modules.json) 为准。准确的验证范围、测试结果和实现操作数量统一记录在 [v0.3 验收记录](../evidence/runtime-review.md)，不从源码数量推断完整产品完成度。

## 1. 当前实现与边界

| 服务 | 当前实际作用 | 仍待实现 |
|---|---|---|
| `IdentityService`、`IdentityController`、`ScaffoldSecurity` | 账户注册、密码散列、会话登录/退出、CSRF、会话轮换、业务路由认证；后台与内部路由默认拒绝 | 完整偏好界面、运维限流、账号管理与生产部署验证 |
| `CurriculumService`、`LocalKnowledgeSearch` | 查询已发布概念/卡片/题目；词项 BM25；release/概念过滤；来源与哈希；公开题目不含标准答案 | 向量/混合索引、大规模检索评测、正式内容审核发布 |
| `LearningService`、`LearningEvidenceProjection` | 选择题服务端判分、幂等提交、同题家族防刷独立样本、复习项与投影滞后标志 | 代码判分、完整题型、已校准学习模型与智能复练计划 |
| `WorkspaceService`、`WorkspaceAccess` | 本人空间与目标、资源归属、固定 release、快照、记忆保存/撤回、引用 revision/hash 校验 | 正式前端、自动上下文路由、长期记忆向量索引 |
| `EventJournal`、`OutboxDispatcher` | 持久顺序事件、事务 outbox、按消费方去重、有限重试、投影与可重放游标 | SSE、运维面板、生产负载与恢复演练 |
| `DurableAgentRuntime`、`PlanValidator` | 计划准入、持久 DAG、节点租约、epoch、预算预留/结算、执行结果准入、命令控制 | LLM 动态规划、外部供应商副作用对账和完整专家自治 |
| `EvidenceWorkflow` | 固定的本地检索工作流，通过真实运行时记录检索证据；没有模型调用 | 自然语言导师、跨媒介规划与生产 |
| `ArtifactService`、`ArtifactInvalidation`、`NotebookService` | 作品 DSL 的 schema/语义校验、不可变版本、输入血缘、失效传播、由真实提交构建错题源稿 | 可视化编辑器、媒体生成、浏览器交互执行、DOCX/PPTX/PDF/音视频等导出 |
| `FrameworkController` | 进程/数据库健康、契约驱动的模块状态、已启用/不可用能力；`businessReady=false` | 完整依赖 readiness 与生产 SLO |

现有领域端口继续表达未实现模块的边界；接口声明、工具登记和 DSL 通过不等于对应适配器可用。没有向客户端开放任意工具执行或服务端代码执行入口。

业务 `TransactionTemplate` 显式使用 `READ_COMMITTED`，并保持用户→工作空间→具体资源的短事务行锁顺序。它避免 MySQL 默认可重复读在等待锁前建立旧快照、获锁后仍读取过时额度的问题；不是以降低隔离级别替代并发控制。模型、网络、编译或媒体执行不得占用这些事务锁等待。

## 2. 构建与本地启动

在仓库根目录，使用正常安装的 JDK 17 和 Maven 3.9.x：

```sh
mvn -f backend/pom.xml verify
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=demo
```

这些命令不依赖会话中临时下载的 JDK、Maven 或私有 settings 文件。默认监听 `127.0.0.1:8080`；默认持久化为 H2 文件库，URL 为 `jdbc:h2:file:./.work/typeforge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`，相对路径基于服务进程的工作目录。设置 `TYPEFORGE_DB_URL` 可以改到独立数据目录。H2 的 MySQL 兼容模式服务于本地开发和快速测试，不代表与 MySQL 的锁、SQL 和 JSON 行为完全等价。

启动时 Flyway 按顺序应用 H2 适配迁移；正常关闭再启动会读取同一文件库。不要把 `test` Profile 用于需要保留数据的运行：它使用内存 H2 并关闭定时 outbox 派发。

### 演示课程与无课程状态

`demo` Profile 显式启用 `DemoContentInitializer`，一次事务保存 16 个概念、16 张原创知识卡和 12 道客观题。重复启动相同版本不会重复插入；清单变化时拒绝覆盖已发布演示版本，须建立新的内容版本。

该 fixture 使用禁用的系统作者，`origin=demo-authored-code-reviewed`，并明确 `formalHumanReview=false`。没有可登录默认账户，没有学生提交、成绩、模型答案或 `content_review` 伪记录。存储状态 `REVIEWED` 表示此演示 fixture 已经代码核对，不代表通过两位真实成员的正式发布审核；其初始化不是正式内容发布管线。

不带 Profile 的启动命令为 `mvn -f backend/pom.xml spring-boot:run`。它只迁移数据库，不自动发布课程；`/api/v1/releases/active` 在没有发布指针时返回明确不可用状态。历史 `database/seed-demo.sql` 是独立草稿建库样例，启动器不会自动执行它。

## 3. 认证与 API 使用

1. GET `/api/v1/auth/csrf`，保存会话 cookie 与响应的 `token/headerName`。
2. 带该 cookie 和 CSRF header POST `/api/v1/auth/register`；按当前契约提交用户名、密码。注册成功只创建账户。
3. POST `/api/v1/auth/session` 登录。会话 ID 轮换，旧 CSRF token 清除；随后重新 GET csrf。
4. 保留认证 cookie，读取 `/api/v1/capabilities`、`/api/v1/releases/active` 和课程资源，按契约创建工作空间、提交练习或创建源作品。
5. 写请求继续带 CSRF；要求幂等的命令带 `Idempotency-Key`，超时后用同一键和同一正文核对结果。登出使用 DELETE session，并清理客户端私人缓存。

账户身份从服务端会话解析。私人工作空间、作答、记忆、运行和作品都检查当前用户归属。管理与内部路由尚不开放；不配置跨域放行。错误使用 `application/problem+json`；不要从 HTTP 超时推断命令未落库，也不要把 `202` 当任务成功。

JSON 写请求在解析前最多接受 262144 字节，已知长度与分块正文均受限，超出返回 413。Jackson 限制嵌套 64 层、数值文本长度 64，并与 Schema 的字符/集合约束共同生效。缺少必需 query/header 也返回统一 ProblemDetail。revision 与 epoch 的数值边界按契约执行；数据库 BIGINT 不表示浏览器可以无损接收任意 64 位整数。

## 4. MySQL 配置

为当前服务准备一个**空的专用 MySQL 8.4 数据库**及其专用账户，然后提供：

| 变量 / 属性 | 作用 |
|---|---|
| `TYPEFORGE_DB_URL` | JDBC URL，例如 `jdbc:mysql://127.0.0.1:3306/typeforge?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8`；按真实环境配置 TLS |
| `TYPEFORGE_DB_USER` | 仅访问该专用数据库的账户 |
| `TYPEFORGE_DB_PASSWORD` | 通过环境或部署密钥服务提供，不写入仓库 |
| `TYPEFORGE_SECURE_COOKIE` | 默认 `false` 便于回环 HTTP 开发；HTTPS 部署设 `true` |
| `typeforge.events.dispatch-enabled` | 正常服务默认 `true`；定时派发 outbox |
| `typeforge.events.poll-millis` | 当前默认派发间隔 1500 ms，需结合真实负载调整 |

环境已配置后启动：

```sh
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=mysql
```

在专用演示 MySQL 库中需要课程 fixture 时使用 `-Dspring-boot.run.profiles=mysql,demo`。`mysql` Profile 指定 MySQL JDBC 驱动和 `classpath:database` Flyway SQL 迁移；不要只把默认 Profile 的 URL 换成 MySQL，否则仍会选择 H2 迁移位置。Flyway 负责 V001–V003 及 schema history，默认不替已有手工建库做 baseline。迁移账户需要相应 DDL 权限；生产迁移职责与日常最小权限账户的拆分仍需部署阶段落实。

## 5. 验证与限制

测试覆盖固定 javac 语义案例、运行/反射行为、计划负例、迁移后的 SQL 服务、身份与对象归属、事件重放、幂等、防刷学习证据、作品版本和持久任务准入。JavaCompiler 仅处理仓库中的固定案例，API JVM 不接收或执行用户代码。测试版本差异须用包含 `ct.sym` 的完整 JDK，不能把 JRE 或裁剪运行时当编译工具链。

有 Docker 条件时，在仓库根目录另执行 `python scripts/verify_mysql.py` 验证真实 MySQL 迁移与数据库约束。是否已执行、失败或受环境限制，均以本轮验收记录为准；不能用 H2 通过替代 MySQL 结果。

当前没有浏览器 UI 验收、真实 LLM 效果评测、媒体渲染验收或用户代码隔离证明。后续任务见 [两人计划](../docs/09-team-plan.md)、[知识正确性](../docs/05-knowledge.md)和[质量要求](../docs/08-quality.md)。
