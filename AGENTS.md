# 后续开发约定

- 先读 README、docs/00-blueprint.md 与当前模块设计，再修改实现。
- 当前为架构基线；除 health/framework，业务模块均 planned。新增能力只有经过真实验证才改状态。
- 不在 API JVM 编译或运行用户输入。测试中的 JavaCompiler 仅用于仓库内固定案例。
- 知识、题目、判分与引用都保存版本；不得覆盖历史成绩或伪造来源/模型输出/个人贡献。
- 用户身份来自会话；每个私人资源验证归属；公共 DTO 不返回标准答案、隐藏测试或密钥。
- 修改接口同时更新 contracts、领域端口、数据库映射、消费者与测试。
- 集群是产品主架构；先读docs/10-agent-cluster.md与11-learning-artifacts.md。任务图、工具、作品DSL、权限、预算、epoch、lease必须共同校验；不将PlanValidator当作持久调度器或安全边界。
- 新增作品类型先补schema和正反例；API/模型/渲染不能把未核验结果标为事实；学生编辑优先于旧Agent回写。
- 前端遵循 docs/07-frontend.md 和209条参考台账；未运行源码/演示不得声称完成高保真迁移。
- 正式 Word/PDF 文档使用用户统一格式：先读 `C:/Users/LENOVO/.codex/document-templates/user-standard/FORMAT.md` 并复用该目录模板，渲染核对；不得覆盖原件。
- 检查：Python scripts/verify.py、Maven verify、必要时独立 MySQL 验证；新功能还需对应模块的真实集成测试。
