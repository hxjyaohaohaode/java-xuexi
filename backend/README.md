# Java 后端：启动、配置与接手

Java 17、Spring Boot 3.5.16、Maven 3.9.x；版本 `0.6.0-SNAPSHOT`，`-Xlint:all -Werror`。当前 130 个公开操作均有实际路由实现，但运行时继续报告 `businessReady=false`，不把接口数量当作完整产品完成率。当前能力与剩余范围集中在[模块接手指南](../docs/02-modules.md)，操作字段见[契约索引](../contracts/README.md)，实际验证见[当前验收](../evidence/runtime-review.md)。

## 1. 构建与本地运行

从仓库根目录执行：

```sh
npm ci --prefix frontend
npm run build --prefix frontend
mvn -B -ntp -f backend/pom.xml clean verify
java -jar backend/target/typeforge-backend-0.6.0-SNAPSHOT.jar --spring.profiles.active=demo
```

访问 http://127.0.0.1:8080/。前端dist作为静态资源随JAR打包；仅构建后端而没有dist时业务API可用，但capabilities会明确FRONTEND不可用。开发模式用前端Vite代理连接同源语义的API，不开放任意CORS。

默认绑定loopback，H2文件位于启动工作目录的 `.work/typeforge`。`TYPEFORGE_DB_URL` 可显式指定绝对文件位置；路径变化就是另一份数据库，不能把临时测试库误当生产数据。`demo` 只加演示课程，不提供可登录默认账号。

标准Profile没有课程时，先由真实编辑走正式工作台；读取活动发布可能返回 `COURSE_NOT_CONFIGURED`。不要通过把未审核数据手工标REVIEWED来绕过发布门禁。

## 2. 身份和角色

HTTP顺序是 GET `/api/v1/auth/csrf` → 带Cookie和返回CSRF header注册 → 登录 → 重新取CSRF token。登录旋转session并使旧token失效。每个写请求都需CSRF；失效返回403/`CSRF_INVALID`，无权限返回403/`FORBIDDEN`，未登录返回401。注册始终只授予LEARNER。

编辑角色只接受运维显式启动配置，**账户必须已经真实注册且ACTIVE**：

```sh
TYPEFORGE_EDITOR_USERS=editor-a,editor-b java -jar backend/target/typeforge-backend-0.6.0-SNAPSHOT.jar --spring.profiles.active=demo
```

这里 `editor-a/editor-b` 是示例用户名，须替换为已注册并核对的账号。`TYPEFORGE_ADMIN_USERS` 同样可指定维护者。配置缺失默认不授予；账号不存在或格式无效会启动失败，不创建账号或密码。操作写审计、重复启动不重复授予；配置完成可移除环境项，角色仍持久保存。新角色重新登录后生效，内容服务每次复查数据库角色，旧 session 不能保留已撤销编辑权限。曾具有编辑/管理权限的关闭账号保留用户名，启动配置遇到该关闭账号会跳过授权并记录日志，防止同名新账号继承权限。

个人中心已支持显示名 CAS、改密、会话列表与撤销、关闭账号。改密成功撤销所有会话，包括当前会话；关闭通过各领域撤回端口取消未完成任务、归档空间、清除上传字节与导出缓存，同时保留历史学习和审核证据。关闭不是全量物理擦除。密码找回、角色撤权管理界面及完整隐私擦除流程仍需实现。

会话同时具有 Servlet 30 分钟空闲期限和持久记录 12 小时绝对期限，每人最多 10 个有效会话。账号 UUID 和认证 epoch 防止迟到登录或同名再注册误认身份。认证限流使用数据库原子计数，安全设置和配额见[账户与素材说明](../docs/22-account-assets-operations.md)。生产 HTTPS 设置 `TYPEFORGE_SECURE_COOKIE=true`；多实例部署还需明确 Servlet 会话黏性或复制方案，持久撤销表本身不等于完整会话存储。

内部worker路由始终denyAll；公开API不能领取lease、伪造完成工具或结算预算。编辑接口位于 `/api/v1/admin/**`，并有真实作者/审核者和版本检查。两人合作是作者加另一名独立编辑，不是用系统账号制造审核记录。

## 3. 导师配置

不需要密钥即可使用 `EVIDENCE_ONLY`。配置 `MODEL` 时由服务器设置：

| 配置 | 用途 |
|---|---|
| `TYPEFORGE_TUTOR_PROVIDER_URL` | 完整 `/chat/completions` HTTPS地址；本地协议测试才使用loopback HTTP |
| `TYPEFORGE_TUTOR_API_KEY` | 服务端凭据，不进仓库、不进前端、不作为启动命令明文参数 |
| `TYPEFORGE_TUTOR_MODEL` | 供应商实际模型名，项目不默认猜测 |
| `typeforge.tutor.timeout-millis` | 默认20000，100–60000ms；HTTP整体超时 |
| `typeforge.tutor.max-output-tokens` | 默认2048，64–8192 |
| `typeforge.tutor.daily-token-limit` | 默认1000000，UTC日额度，采用保守输入字节上界预留 |
| `typeforge.tutor.daily-request-limit` | 默认100，按账户计数 |
| `typeforge.tutor.max-pending` | 默认4，同时排队/执行上限 |
| `typeforge.tutor.worker-enabled` | 默认true；集成测试可关闭并显式推进worker |

后几项可通过Spring配置文件/环境提供，具体边界在 `TutorSettings`。浏览器不能选择endpoint、key、任意模型或提高配额。provider使用非流式Chat Completions及JSON结果协议；本仓库的loopback协议测试不是某个真实供应商已通过的证明。供应商兼容性与教学质量仍需真实key实测。

响应正文有上限、禁止重定向、结构和引用严格验证；不把模型输出直接作为执行指令。HTTP在事务外，回写重新检查owner/workspace/context/epoch/lease。发送后的未知结果保留UNKNOWN额度，不自动重试付费调用；取消只撤销平台结果提交权，不能承诺供应商退费。

## 4. 独立编译 worker 与代码题

代码运行可通过仓库的 [Java/Python 完整课堂启动器](../workers/compiler/README.md) 启动。服务分开部署时可通过 `typeforge.compiler.execution-enabled=true` 与 broker 侧 `TYPEFORGE_EXECUTION_ENABLED=true` 开启。单独启动 API 时，编译默认关闭，`/api/v1/capabilities` 会报告 `COMPILE_ONLY`/`CODE_GRADING` 当前是否可用。启用时先按[独立编译 worker 说明](../workers/compiler/README.md)在专用 Linux 主机部署 Python broker 和 Docker，再由 API 的服务端安全配置提供：

```properties
typeforge.compiler.enabled=true
typeforge.compiler.worker-url=http://127.0.0.1:8091/v1/compile
typeforge.compiler.worker-token=<至少32字符的服务端随机令牌>
```

跨主机使用 HTTPS 和受保护网络；浏览器不能选择 worker 地址、镜像、文件路径、编译参数或令牌。broker 启动时固定镜像 ID，任务在新建的非 root、无网络、只读基础系统 Docker 容器内执行 Java 17 编译，禁用注解处理并限制 CPU、内存、PIDs、输出和时间。**API JVM 从不编译或运行用户代码，broker 没有主机编译降级。** 启用执行开关后，普通课堂实验也可在独立镜像中运行 Java 17 和 Python 3，按题目契约判分的路径继续只编译。

| API 编译配置 | 默认值与含义 |
| --- | --- |
| `typeforge.compiler.timeout-millis` | 45,000；worker HTTP 整体期限 |
| `typeforge.compiler.max-pending` | 每账号 4 个等待或执行作业 |
| `typeforge.compiler.global-pending` | 全局 256 个等待或执行作业 |
| `typeforge.compiler.daily-limit` | 每账号每日 200 个作业 |
| `typeforge.compiler.dispatch-enabled` | `true`；测试可关闭并显式推进 |

普通编译返回公共诊断；代码题另执行审核后的 2～8 个正反编译契约检查，公共编译通过本身不能给成绩。隐藏检查、隐藏诊断和标准答案不返回学习者。基础设施失败产生 `FAILED`/不可用状态，不伪装成学生答错。多层提示持久记录，同题族后续作答保留辅助练习属性。

本地执行环境没有 Docker/Podman；独立 GitHub Actions 已在真实 Docker 宿主通过八项检查，包括隔离/资源配置、实际编译、取消、超时、输出限制和零残留清理，见[已执行的容器门禁](https://github.com/hxjyaohaohaode/java-xuexi/actions/runs/43743602623/job/132901926094)。协议测试与 CI 实际容器结果分别记录；生产宿主部署、容量压测及运维恢复仍需在目标环境验收。

## 5. 教学工作流、作品与私有素材

`EVIDENCE_ONLY` 教学工作流在默认环境可用。配置 MODEL 后，规划者只能选择 1～3 项教学分工；专家与审阅分别通过持久导师请求执行，最后生成 DOCUMENT、SLIDES 或 NOTEBOOK 草稿。用户选择的较早作答可加入固定上下文，创建时检查归属、课程、判分状态和快照包含关系。暂停、恢复、取消、未知用量和来源变化继续使用 epoch/lease 与预算控制。

这是受限的 planner → expert → review → DRAFT 链，不能执行任意模型生成工具。专家和审阅者可使用同一模型，不代表人工独立核验；生成稿不会覆盖用户修改。真实模型教学质量、外部金额账单和通用工具自治仍需独立实现或验收。

文档支持 Markdown/HTML/DOCX/PDF，幻灯片支持 HTML/PPTX/PDF 和授权 PNG/JPEG 实际内嵌；关系图支持受控 SVG，错题/交互作品支持静态 HTML。Office 文件可重新打开并编辑文字/代码，PDF 嵌入中文字体。导出持久队列固定源版本、内容 hash 和 renderer 版本；最终提交及下载都复验来源，重启后读取同一文件字节。

导出默认每人最多 8 个待处理任务、24 小时 200 个新任务、256 MiB 缓存；单文件 8 MiB。同版本/格式复用，过时缓存回收并保留作业元信息。单次渲染最多 32 MP 图片输入，输出流有实时限额；取消阻止迟到提交，本地库可能完成当前计算。调度由 `typeforge.export.dispatch-enabled` 和 `typeforge.study.dispatch-enabled` 控制，默认均为 `true`。

私有素材直接接收有 CSRF 和幂等键的 multipart 文件，支持 PNG/JPEG/PCM WAV/MP3/MP4。默认单项 8 MiB、每人总量 100 MiB，可配置 `typeforge.assets.max-owner-bytes`；Servlet 整体 multipart 请求为 9 MiB。素材保存真实字节和 SHA-256，支持授权 Range 下载；撤回删除字节并同步使依赖作品失效。当前是有界数据库 BLOB 存储及格式结构验证，没有 AV 扫描、生成式图像、TTS 或视频时间线渲染。细节见[账户与素材](../docs/22-account-assets-operations.md)、[教学工作流与导出](../docs/23-study-and-exports.md)。

## 6. MySQL 与运维

设置 `TYPEFORGE_DB_URL` 为真实 MySQL JDBC URL、`TYPEFORGE_DB_USER` 与 `TYPEFORGE_DB_PASSWORD`，运行 `--spring.profiles.active=mysql`（演示验证可加 `,demo`）。连接信息仅放安全配置。MySQL Profile 执行 V001–V014 原始迁移，默认 H2 使用对应兼容迁移；当前有 88 张领域表。已应用迁移不回写，已有库必须沿完整升级链前进。

共享短事务采用 READ_COMMITTED；学习写入锁序为 user → workspace → 具体资源。编辑事务以 content_workbench_lock 串行化编辑并锁定实际编辑账号，该锁内没有网络或 workspace 调用。模型 HTTP 和二进制渲染在数据库事务外执行，提交重新核对状态与来源。

outbox效果、receipt与published同事务，每空间按完整流头顺序投递；失败5次后形成阻塞本空间的死信，其他空间仍可继续。运维重试使用内部维护方法，未开放普通学习者接口。

`GET /api/v1/admin/operations` 要求数据库中的当前 ADMIN 角色，只返回真实队列聚合与当前进程的请求直方图。P50/P95 是固定桶上界，不是生产容量结论；该接口不包含私人请求内容。监控告警、生产 SLO、备份与恢复演练仍需部署侧验证。

## 7. 正式内容工作台的操作顺序

1. 两人分别注册，运维配置编辑角色后重新登录。
2. 来源登记：真实网址、版本、使用范围；手工采集提交实际原稿、清洗稿和加工说明，查看服务器哈希与去重结果。
3. 创建概念及前置关系；创建知识 draft 绑定 sourceId/ingestionId/section；题目绑定知识版本。客观题使用选择答案结构，SIGNATURE/REFACTOR/FILL 使用经审核的 `compileChecks` 和提示，公开练习 DTO 不泄露这些隐藏检查。
4. 另一编辑读取完整内容，亲自审核并提交 `expectedContentHash`。作者自审、过期hash、损坏答案结构均拒绝。
5. 选择确切知识/题目revision构建release。检查清单与概念图，再带 `expectedManifestHash` 和 `expectedActiveRevision` 发布。
6. 新建学习空间读取新release并实际检索/做题；旧空间继续读旧release。需要回退时激活已发布正式release，仍校验当前指针revision。

准确字段、所有列表/详情和响应见OpenAPI；`ContentWorkbenchIntegrationTest` 包含完整真实session HTTP流程，可作为开发时的调用样例。该测试使用隔离测试作者和明确夹具，不计作两位学生的人类审核记录。

## 8. 新增评测、导入与错题交互

M11 的私有检索评测由 `typeforge.evaluation.worker-enabled`（默认 true）和 `typeforge.evaluation.poll-millis`（默认 1000）调度。服务端执行真实检索，定义和报告包含固定发布/语料/算法/修订，案例及未完成结果均有额度。它不写学习成绩。

M07 批量导入接受 TXT/MD/DOCX，单文件 1 MiB、每批 4 MiB、最多 8 项，两个准入许可覆盖读取到持久化；清理周期由 `typeforge.ingestion.cleanup-delay-millis`（默认 60000）控制。TEXT 判分和六类错题交互默认可用；修复比较仍需 COMPILE_ONLY。精确公式、边界、保留和恢复语义见 [v0.6 新增实现](../docs/24-evaluation-import-and-notebook.md)。

## 9. 验证命令

```sh
python scripts/sync_contract_index.py --check
python scripts/verify.py
mvn -B -ntp -f backend/pom.xml clean verify
python scripts/smoke_runtime.py --profile h2
python scripts/verify_mysql.py
python -m unittest discover -s workers/compiler -p 'test_*.py'
```

真实 MySQL HTTP 重启、隔离级别和浏览器检查使用专用配置及同一最终构建。模式验证覆盖 V001–V014 完整升级链；账户/素材使用 `scripts/verify_private_modules_mysql.py`，新增评测/导入/版本比较/TEXT/Notebook 使用 `scripts/verify_v06_modules_mysql.py` 在显式隔离连接上复跑，并输出实际 JDBC 引擎证据。MySQL 专用门禁需要 `MYSQL_TEST_CONNECTION`，只接受标明用途的回环测试库，不能指向个人或生产数据库。

所有结果以验收记录和 CI 的实际执行为准。仍需完成的范围包括通用工具自治、外部模型教学与账单验收、生成式图像/TTS/视频、生产 Docker 宿主部署与容量验收、生产性能 SLO 及备份恢复；测试夹具、有限类型规则和配置入口不能代替这些实测。

正式整包验收先运行 `python scripts/verify_frontend.py`，再运行 `python scripts/verify_java.py`。两者实际清理旧输出并执行完整检查与构建，保存受检源码、完整测试集合与最终字节的对应关系；之后 HTTP 和 DOM 必须使用这个未变更的 JAR。开发中的直接 Maven 命令仍可使用，但旧测试/JAR不能经汇总脚本直接变成新源码的验收证明。

H2 文件库的连接 URL 必须保留 `DB_CLOSE_ON_EXIT=FALSE`，由 Spring Boot 管理后台任务、连接池和数据库的关闭顺序。自定义 `TYPEFORGE_DB_URL` 时也应保留此项；不要另加一个与容器竞争的 H2 JVM 退出钩子。该设置对应 [Spring Boot 3.5 的嵌入式数据库说明](https://docs.spring.io/spring-boot/3.5/reference/data/sql.html)，真实停止/重启和无后台异常由两引擎 HTTP 门禁检查。
