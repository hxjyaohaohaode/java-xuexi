# 后续开发约定

- 先读 README、docs/00-blueprint.md 与当前模块设计，再修改实现。
- 当前为 v0.6 联动学习工作台，135 个公开操作有实际实现；接口数不等于完整产品完成率，businessReady 仍为 false。先读 docs/02-modules.md 的逐模块实际与剩余范围，以 OpenAPI x-status、运行 capabilities 及 evidence/runtime-review.md 为准。
- 已有账号/会话生命周期、私有素材、真实阅读统计、编译队列与CODE编译契约判分、多层提示、所选作答上下文、受限教学工作流和真实DOCX/PPTX/PDF及图片内嵌。MODEL需要显式配置；通用任意工具自治、真实外部模型教学/账单验收、生成式图像/TTS/视频、生产性能SLO与备份恢复仍有剩余。不能把接口、协议测试或草稿审阅冒充这些验收。
- 不在 API JVM 编译或运行用户输入。独立 broker 的 COMPILE_ONLY 和显式启用的 COMPILE_AND_RUN 都在受限 Docker 容器中执行；没有 Docker 时拒绝启动，不能降级为主机编译或运行。代码评分保持只编译语义。测试中的JavaCompiler仅用于仓库内固定案例；无真实Docker宿主验收时不得声称隔离实测通过。
- 知识、题目、判分与引用都保存版本；不得覆盖历史成绩或伪造来源/模型输出/个人贡献。
- 用户身份来自会话；每个私人资源验证归属；公共 DTO 不返回标准答案、隐藏测试或密钥。
- 修改接口同时更新 contracts、领域端口、数据库映射、消费者与测试。
- 契约与迁移改动后执行 python scripts/sync_contract_index.py；CI 用 --check 阻止旧接口索引/字段字典混入。V001–V018是当前完整升级链，已应用的迁移不允许回写或删除，后续继续追加；旧说明通过Git历史查阅。
- 集群是产品主架构；先读docs/10-agent-cluster.md、11-learning-artifacts.md及23-study-and-exports.md。当前StudyWorkflow是EVIDENCE_ONLY或配置MODEL的受限planner→expert→review→DRAFT链。任务图、工具、作品DSL、权限、预算、epoch、lease必须共同校验；不将PlanValidator当作持久调度器或安全边界，不让模型文本自行授予工具权限。
- 账户关闭通过AccountClosureParticipant领域端口执行，保持owner→workspace锁序与同事务撤回。素材ASSET是固定版本来源，撤回须使直接与间接作品失效；导出提交和下载都复验来源与实际字节hash。关闭保留历史证据，不声称全量物理擦除。
- 新增作品类型先补schema和正反例；API/模型/渲染不能把未核验结果标为事实；学生编辑优先于旧Agent回写。
- 前端遵循 docs/07-frontend.md 和209条参考台账；未运行源码/演示不得声称完成高保真迁移。
- npm ci --prefix frontend 后执行 check/test/build；正式 JAR 需先生成 frontend/dist。浏览器工作以实际后端为准，不使用假模型或模拟成功页面代替端到端测试。
- 正式 Word/PDF 文档使用用户统一格式：先读 `C:/Users/LENOVO/.codex/document-templates/user-standard/FORMAT.md` 并复用该目录模板，渲染核对；不得覆盖原件。
- 检查：Python scripts/verify.py、Maven verify、必要时独立 MySQL 验证；新功能还需对应模块的真实集成测试。正式验收用 scripts/verify_frontend.py 与 scripts/verify_java.py 实际重建并绑定源码/完整用例/最终包；各门禁报告不能借用旧 PASS，汇总后必须检查源码快照。
