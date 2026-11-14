# Java 后端：启动、配置与接手

Java 17、Spring Boot 3.5.16、Maven 3.9.x；版本 `0.4.0-SNAPSHOT`，`-Xlint:all -Werror`。当前能力与下一步集中在 [模块接手指南](../docs/02-modules.md)，操作状态见 [契约索引](../contracts/README.md)，实际验证见 [当前验收](../evidence/runtime-review.md)。

## 1. 构建与本地运行

从仓库根目录执行：

```sh
npm ci --prefix frontend
npm run build --prefix frontend
mvn -B -ntp -f backend/pom.xml clean verify
java -jar backend/target/typeforge-backend-0.4.0-SNAPSHOT.jar --spring.profiles.active=demo
```

访问 http://127.0.0.1:8080/。前端dist作为静态资源随JAR打包；仅构建后端而没有dist时业务API可用，但capabilities会明确FRONTEND不可用。开发模式用前端Vite代理连接同源语义的API，不开放任意CORS。

默认绑定loopback，H2文件位于启动工作目录的 `.work/typeforge`。`TYPEFORGE_DB_URL` 可显式指定绝对文件位置；路径变化就是另一份数据库，不能把临时测试库误当生产数据。`demo` 只加演示课程，不提供可登录默认账号。

标准Profile没有课程时，先由真实编辑走正式工作台；读取活动发布可能返回 `COURSE_NOT_CONFIGURED`。不要通过把未审核数据手工标REVIEWED来绕过发布门禁。

## 2. 身份和角色

HTTP顺序是 GET `/api/v1/auth/csrf` → 带Cookie和返回CSRF header注册 → 登录 → 重新取CSRF token。登录旋转session并使旧token失效。每个写请求都需CSRF；失效返回403/`CSRF_INVALID`，无权限返回403/`FORBIDDEN`，未登录返回401。注册始终只授予LEARNER。

编辑角色只接受运维显式启动配置，**账户必须已经真实注册且ACTIVE**：

```sh
TYPEFORGE_EDITOR_USERS=editor-a,editor-b java -jar backend/target/typeforge-backend-0.4.0-SNAPSHOT.jar --spring.profiles.active=demo
```

这里 `editor-a/editor-b` 是示例用户名，须替换为你们已注册并核对的账号。`TYPEFORGE_ADMIN_USERS` 同样可指定维护者。配置缺失默认不授予；账号不存在或格式无效会启动失败，不偷偷创建账号或密码。操作写审计、重复启动不重复授予；配置完成可移除环境项，角色仍持久保存。新角色登录后生效，内容服务每次复查数据库角色，旧session不能保留已撤销编辑权限。完整账号管理/密码找回/撤权界面尚未提供。

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

## 4. MySQL

设置 `TYPEFORGE_DB_URL` 为真实 MySQL JDBC URL、`TYPEFORGE_DB_USER` 与 `TYPEFORGE_DB_PASSWORD`，运行 `--spring.profiles.active=mysql`（演示验证可加 `,demo`）。连接信息仅放本地安全配置。MySQL Profile执行V001–V006原始迁移，默认H2使用对应兼容迁移。

共享短事务采用READ_COMMITTED；学习写入锁序是user→workspace→具体资源。编辑事务另以content_workbench_lock串行化两人编辑，并锁定实际编辑账户；该锁内没有网络或workspace调用。模型调用不占数据库锁，文本导出是有输入/输出大小上限的确定性本地转换。

outbox效果、receipt与published同事务，每空间按完整流头顺序投递；失败5次后形成阻塞本空间的死信，其他空间仍可继续。运维重试使用内部维护方法，未开放普通学习者接口。

## 5. 正式内容工作台的操作顺序

1. 两人分别注册，运维配置编辑角色后重新登录。
2. 来源登记：真实网址、版本、使用范围；手工采集提交实际原稿、清洗稿和加工说明，查看服务器哈希与去重结果。
3. 创建概念及前置关系；创建知识draft绑定 sourceId/ingestionId/section；创建题目draft绑定知识版本和当前实际支持的SINGLE/MULTIPLE答案结构。
4. 另一编辑读取完整内容，亲自审核并提交 `expectedContentHash`。作者自审、过期hash、损坏答案结构均拒绝。
5. 选择确切知识/题目revision构建release。检查清单与概念图，再带 `expectedManifestHash` 和 `expectedActiveRevision` 发布。
6. 新建学习空间读取新release并实际检索/做题；旧空间继续读旧release。需要回退时激活已发布正式release，仍校验当前指针revision。

准确字段、所有列表/详情和响应见OpenAPI；`ContentWorkbenchIntegrationTest` 包含完整真实session HTTP流程，可作为开发时的调用样例。该测试使用隔离测试作者和明确夹具，不计作两位学生的人类审核记录。

## 6. 验证命令

```sh
python scripts/sync_contract_index.py --check
python scripts/verify.py
mvn -B -ntp -f backend/pom.xml clean verify
python scripts/smoke_runtime.py --profile h2
python scripts/verify_mysql.py
```

真实MySQL HTTP重启、隔离级别和浏览器检查采用专用配置与同一构建；详见验收记录和GitHub Actions。所有可用模块仍有生产化与完整产品范围内的后续工作；不得将测试夹具、确定性证据、有限类型规则分别冒充真实模型效果、全部专家自治或用户源码沙箱。
