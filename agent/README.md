# Agent 与导师能力边界

当前有三条可运行路径：

- `EvidenceWorkflow` 使用持久 DAG 和两个已注册 `AgentTool<I,O>`：retrieveContext、searchEvidence，确定性执行，不调用模型。
- `TutorService` / `TutorWorker` 提供持久会话与请求，EVIDENCE_ONLY 整理真实检索片段；MODEL 经显式服务器配置调用 HTTP 模型。引用、范围、上下文、租约、取消和 token 配额由 Java 服务控制。结果可转为有来源的 DOCUMENT/DRAFT，再进入编辑/导出链。
- `StudyWorkflowService` 以目标与快照运行有限规划→1–3 个允许专家→独立复核→DOCUMENT/SLIDES/NOTEBOOK 草稿组装，连接 planLearning、explainLearning、composeDocument、composeSlides、composeInteraction。MODEL 复用真实持久导师队列，本地模式明确不调用模型；计划和所有步骤均受 token/time、来源、epoch 与作用域约束。

这不代表十四个专家已经自由自治。任意工具计划、动态供应商路由、完整工具分派和生成媒体仍待实现。独立编译 API 已接通，但尚未作为模型任意选择的 AgentTool。`roles.json` 标记当前有限执行角色与剩余目标；`tools.json` 与 `capability-matrix.json` 的状态不得仅因相似 HTTP 功能出现而改成工具已接入。

| 文件 | 用途与真实边界 |
|---|---|
| roles.json | 专家职责与允许工具；限定工作流角色为 partial，通用自治仍未完成 |
| tools.json / capability-matrix.json | 工具协议、是否已有实际 Agent 适配器、作品格式边界 |
| run-policy.json | 已落实的 DAG 节点/并发/重试/预算约束；外部媒介计费另需适配 |
| example-plan.json | 可校验的复杂任务设计样例，不能冒充实际模型计划 |
| conductor-prompt.md | 未来总协调提示词设计；当前 provider 使用源码中的实际受控提示 |
| system-prompt.md | 教学原则与最终目标，运行时模型提示以 HttpTutorModelProvider 为准 |
| evaluation-cases.json / cluster-evaluation-cases.json | 评测设计集；真实外部模型效果与多专家对照尚未验收 |
| artifact-examples.json | 各 DSL 的 schema 示例，不是生成完成的媒体 |

实际实现、调用链、下一步和风险见 [模块接手指南](../docs/02-modules.md)、[导师与证据](../docs/06-agent.md)、[集群设计](../docs/10-agent-cluster.md)与[验收](../evidence/runtime-review.md)。
