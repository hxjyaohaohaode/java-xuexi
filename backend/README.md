# Java工程的当前边界

Java 17 / Spring Boot 3.5.16 / Maven，编译启用 `-Xlint:all -Werror`。当前能构建、启动、返回两个框架接口，并执行可信固定案例和计划内核测试。没有配置数据库、模型、媒体worker或登录业务。

| 已有代码 | 当前作用 | 下一步 |
|---|---|---|
| operations/FrameworkController | 明确返回16模块planned及businessReady=false | 随真实能力完成更新，不改成假成功 |
| operations/ScaffoldSecurity | 仅允许health/framework，其他路由拒绝 | 接入会话/CSRF/对象权限后逐项开放 |
| shared/Page、Transfers | 防御性分页值、`extends/super`的PECS组合 | 保留实际语义测试，不为展示泛型强套每个对象 |
| 各领域公开端口 | 确立类型实验、编译、练习、知识、检索、导师、发布等边界 | 按contracts和数据库设计补应用服务与adapter |
| orchestration/AgentTool&lt;I,O&gt; | 泛型工具输入/输出关系及执行上下文 | 增加工具schema、能力准入、预算/lease与适配器 |
| orchestration/TaskPlan、PlanValidator | 不可变计划、DAG/角色工具/预算验证、无冲突就绪候选 | 接入事务调度、epoch、持久事件、外部作业对账 |
| artifacts/ArtifactStudio&lt;T&gt; | 有类型的作品版本端口 | 八类DSL解析、版本保存、renderer与质量门禁 |
| workspace/WorkspaceContextStore、notebook/NotebookCompiler | 快照与可撤回记忆、错题构建边界 | 实现本人资源归属、事件和跨模块完整链路 |

后续目录遵循`模块/application/domain/adapter`，公开端口不要依赖Controller或特定模型SDK。未实现端口没有返回假数据的默认bean。worker不能复用主服务凭据。

测试分为真实javac编译正/反/警告案例及泛型运行/反射行为、任务计划与调度候选的确定性负例、HTTP框架边界。JavaCompiler只处理仓库内人工审核的测试字符串；不能把此测试辅助代码当成线上编译沙箱。

```sh
mvn -f backend/pom.xml verify
mvn -f backend/pom.xml spring-boot:run
```

详细任务顺序见[开发计划](../docs/09-team-plan.md)，类型边界与知识正确性见[知识说明](../docs/05-knowledge.md)，最终验收见[质量要求](../docs/08-quality.md)。
