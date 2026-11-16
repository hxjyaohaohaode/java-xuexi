# v0.6 当前运行验收记录

这是当前实现的实测记录，不是最终课程成绩或完整产品验收。生成时间：2026-11-16T03:11:18.168795+00:00。机器证据见 [runtime-check-results.json](runtime-check-results.json)，由 [record_verification.py](../scripts/record_verification.py)读取实际测试输出生成；源码摘要变化必须重新核对相关门禁。

## 实际结果

| 门禁 | 已观察的结果 | 证明边界 |
|---|---|---|
| Java/Maven | 455 项，失败/错误/跳过均为 0 | 固定语言语义、账户/会话、SQL/HTTP、编译协议、学习任务/模型协议夹具、发布、素材与原生文件导出回归 |
| 契约与架构 | 755 项通过；129 个 implemented / 0 个 planned 操作 | 真实路由、schema、模块依赖、索引和文档对应；数量不是产品完成率 |
| MySQL 8.4 | 195 项；88 张业务表；14 条迁移 | 真实 MySQL 建表、复合外键、唯一性、CHECK、JSON及评测/导入/审核/状态约束 |
| MySQL 隔离 | 2 项 | 实际 READ COMMITTED、锁后可见已提交写入，并有 REPEATABLE READ 对照 |
| MySQL 内容服务 | 15 项 | 实际 JDBC 引擎证明；人工录入、审核hash、发布/激活及并发服务在真实 MySQL 上复跑 |
| MySQL 账户与素材 | 22 项 | 实际 JDBC 引擎证明；账户关闭/会话/并发身份、UTC统计、上传/撤回/配额及下载隔离 |
| MySQL v0.6 业务链 | 75 项 | 实际 JDBC 引擎证明；批量导入/原子回滚、修订对比、私有评测/容量/取消、TEXT独立审核判分、六类错题块与管理统计 |
| 编译 broker Python | 13 项 | 离线协议/边界/清理归属；真实容器隔离在远端 CI 独立执行 |
| 验收脚本回归 | 20 项 | 临时 Git/进程夹具验证旧 PASS 复用、缺失测试、源码/JAR变化与驱动失败；不是实际 Maven、浏览器或数据库执行 |
| H2 实际 HTTP/重启 | 93 项；169 次请求 | 单独进程、真实会话/CSRF、业务链、真实下载、重启保留与撤回后失效 |
| MySQL 实际 HTTP/重启 | 108 项；172 次请求 | 同一应用包在真实 MySQL 上运行，独立 SQL 查询验证已写入事实 |
| 前端类型/构建/单元 | 60 项单元通过；TypeScript 与 Vite 构建成功 | 编译与组件/状态逻辑；不等于真实浏览器布局验收 |
| 前端 DOM + 真实 HTTP | 24 条链通过 | 无服务端响应 mock；jsdom 驱动真实后端；不验证浏览器布局/原生下载 |
| Chromium 浏览器 | 本地被环境阻止；精确提交由 GitHub Actions 运行 | 本地 AF_UNIX 创建受限，不记成通过；远端 Playwright失败会阻断CI |

Java 各测试类的实际汇总：

| 测试类 | 测试数 | 失败 / 错误 / 跳过 |
|---|---:|---|
| `AccountLifecycleIntegrationTest` | 14 | 0 / 0 / 0 |
| `AgentPlanTest` | 25 | 0 / 0 / 0 |
| `ArtifactFileExportTest` | 10 | 0 / 0 / 0 |
| `BatchImportIntegrationTest` | 16 | 0 / 0 / 0 |
| `CodeRubricSemanticsTest` | 4 | 0 / 0 / 0 |
| `CompileHttpProtocolTest` | 8 | 0 / 0 / 0 |
| `CompileQueueTest` | 14 | 0 / 0 / 0 |
| `ContentComparisonIntegrationTest` | 7 | 0 / 0 / 0 |
| `DurableAgentRuntimeTest` | 26 | 0 / 0 / 0 |
| `EvaluationWorkbenchIntegrationTest` | 27 | 0 / 0 / 0 |
| `EvidenceWorkflowRecoveryTest` | 2 | 0 / 0 / 0 |
| `ExerciseHintCapabilityIntegrationTest` | 3 | 0 / 0 / 0 |
| `FiniteTypeEvaluatorTest` | 42 | 0 / 0 / 0 |
| `FrameworkHttpTest` | 3 | 0 / 0 / 0 |
| `FullLearningLoopIntegrationTest` | 9 | 0 / 0 / 0 |
| `GenericEdgeCasesTest` | 24 | 0 / 0 / 0 |
| `GenericSemanticsTest` | 34 | 0 / 0 / 0 |
| `InteractiveLearningTest` | 14 | 0 / 0 / 0 |
| `KnowledgeRuntimeTest` | 11 | 0 / 0 / 0 |
| `LocalLearningToolsTest` | 8 | 0 / 0 / 0 |
| `NotebookInteractionIntegrationTest` | 15 | 0 / 0 / 0 |
| `PrivateAssetsIntegrationTest` | 8 | 0 / 0 / 0 |
| `RootArchitectureInvariantsTest` | 24 | 0 / 0 / 0 |
| `StudyModelWorkflowTest` | 8 | 0 / 0 / 0 |
| `StudyWorkflowIntegrationTest` | 11 | 0 / 0 / 0 |
| `TextLearningIntegrationTest` | 10 | 0 / 0 / 0 |
| `TransactionIsolationTest` | 2 | 0 / 0 / 0 |
| `TutorHttpBoundaryTest` | 3 | 0 / 0 / 0 |
| `TutorHttpProviderTest` | 17 | 0 / 0 / 0 |
| `TutorRuntimeTest` | 16 | 0 / 0 / 0 |
| `WorkspaceArtifactReferenceTest` | 1 | 0 / 0 / 0 |
| `WorkspaceContextSelectionTest` | 3 | 0 / 0 / 0 |
| `TextRubricTest` | 7 | 0 / 0 / 0 |
| `BoundedDocumentTextTest` | 14 | 0 / 0 / 0 |
| `ContentWorkbenchIntegrationTest` | 15 | 0 / 0 / 0 |

## 三轮审查与关键联动

完整审查记录见 [development-review-v06.md](development-review-v06.md)：第一轮对齐16模块、接口与数据边界并补齐评测/导入/文本/错题链；第二轮交叉审查并修复上传准入内存、DOCX重复分支、评测中间结果容量、旧块兼容、长题/引用上限与跨题提示问题；第三轮完整回归、MySQL、重启与前端DOM验证，精确提交再由远端图形浏览器核验。每轮包含实际发现及修改，不以同一条命令重复三次代替审查。

真实作答经 outbox 和幂等 receipt 进入独立题目家族证据与复习；错题块调用同一判分服务。阅读/预测/作品生成只记活动，不给虚假的掌握分。

导师会话绑定本人空间和固定课程；请求固定上下文、租约、epoch 与额度。MODEL协议使用本地真实 HTTP 服务夹具测试响应、超时、异常与引用，EVIDENCE_ONLY真实检索。夹具不是付费供应商教学质量证明。取消、账号禁用、归档、deadline、自然过期、来源撤回及未知计量均有独立边界。

回答转文档保存完整来源；作品保存、上下文读取、错题动作、导出与下载复核有界祖先图、实际内容hash和叶子来源。自然到期不依赖提前产生撤回事件。导出固定 renderer 与源 revision，真实字节与终态同事务；HTML/SVG转义、非法XML字符、失效来源和越权均不会变成可用下载。

人工来源录入、独立作者/审核人、完整审核hash、发布manifest、历史激活CAS在真实应用服务中完成。演示内容的REVIEWED标记不被当作正式审核；被损坏的答案结构受控拒绝。

## 可重跑命令

```sh
python -m pip install -r requirements-dev.txt
python scripts/sync_contract_index.py --check
python -m unittest discover -s scripts -p 'test_*.py'
npm ci --prefix frontend
python scripts/verify_frontend.py
python scripts/verify_java.py
python scripts/verify_mysql.py
python scripts/smoke_runtime.py --profile h2
# 已准备专用 MYSQL_TEST_CONNECTION 后：
python scripts/smoke_runtime.py --profile mysql
python scripts/verify_transaction_isolation.py
python scripts/verify_content_mysql.py
python scripts/verify_private_modules_mysql.py
python scripts/verify_v06_modules_mysql.py
python scripts/browser_runtime.py --driver dom --jar backend/target/typeforge-backend-0.6.0-SNAPSHOT.jar
# Chromium 需按 frontend/README.md 安装：
python scripts/browser_runtime.py --jar backend/target/typeforge-backend-0.6.0-SNAPSHOT.jar
# 文档与源码均冻结后，静态检查和最终汇总必须最后执行：
python scripts/verify.py
python scripts/record_verification.py
python scripts/record_verification.py --check-snapshot
```

`verify_frontend.py` 和 `verify_java.py` 会先移走旧构建/测试结果，再实际执行完整检查，分别输出 `.work/frontend-build-verification.json` 与 `.work/java-build-verification.json`。后者保存本轮全部 JUnit 方法及每个参数样例的结果，后续 MySQL 定向测试改写 Surefire XML 不会混入完整 Java 汇总；不能跳过执行后为旧报告补摘要。

每个门禁记录其实际输入的运行前后摘要：后端包含代码、测试、资源、契约与迁移，前端包含页面、测试和构建锁文件，SQL 门禁包含迁移与种子；各门禁自身脚本及公共摘要实现也受绑定。HTTP 与 DOM 还必须使用相同的最终 JAR，DOM 步骤报告必须匹配本轮驱动输出 hash。最终汇总拒绝旧源码、旧构建、缺少样例或没有新输出的 PASS。仅修改接手文档时，只需重新执行静态检查与汇总；实现、测试、契约或迁移变化必须重跑其相关门禁。

## 当前包与源码

- JAR SHA-256：`507c8c96dc952ee2fdf61dc8688331b7cf84ec0e2704678a91e9cbd289076592`。
- 源码文件数：374；SHA-256 摘要：`774f474cace25807f833177b3275766cd1f7aa9db8e7ceb0ffa17d48bcc12df0`。
- 只有本记录与机器报告自身从源码摘要排除，避免循环hash；迁移、代码、契约、页面、锁文件、文档及CI均包含。
- 发布提交的远端结果在 [GitHub Actions](https://github.com/hxjyaohaohaode/java-xuexi/actions)核对，不能把本地环境结果当成Java17/Ubuntu/Chromium结果。

## 仍未完成和未验证

真实供应商模型的教学效果、费用账单、通用任意工具调度、隔离编译服务的生产部署、生成式图像/音频/视频、全课程人工审核、生产负载与备份恢复、正式答辩和个人贡献均不在本轮PASS范围内。真实 Docker 编译检查由远端 CI 独立证明，不等于生产部署。逐模块剩余事项及代码入口见 [接手指南](../docs/02-modules.md)。最终40项课程验收保持独立，不因自动测试通过而自动升级。

旧基线报告和重复字段字典已清理；历史保留在Git。V001–V014为合法升级链，由 `database/migrations.lock.json`锁定，不能为清理旧版本而删除。
