# Agent 与导师能力边界

当前有两条可运行路径：

- `EvidenceWorkflow` 使用持久 DAG 和两个已注册 `AgentTool<I,O>`：retrieveContext、searchEvidence，确定性执行，不调用模型。
- `TutorService` / `TutorWorker` 提供持久会话与请求，EVIDENCE_ONLY 整理真实检索片段；MODEL 经显式服务器配置调用 HTTP 模型。引用、范围、上下文、租约、取消和 token 配额由 Java 服务控制。结果可转为有来源的 DOCUMENT/DRAFT，再进入编辑/导出链。

两者都不代表十四个专家已经自主协同。通用模型规划、动态专家路由、完整工具分派、媒体与编译工具仍待实现。`roles.json` 的14个角色是目标职责；`tools.json` 与 `capability-matrix.json` 的状态不得仅因相似 HTTP 功能出现而改成工具已接入。

| 文件 | 用途与真实边界 |
|---|---|
| roles.json | 专家职责与允许工具；角色自治仍 planned |
| tools.json / capability-matrix.json | 工具协议、是否已有实际 Agent 适配器、作品格式边界 |
| run-policy.json | 已落实的 DAG 节点/并发/重试/预算约束；外部媒介计费另需适配 |
| example-plan.json | 可校验的复杂任务设计样例，不能冒充实际模型计划 |
| conductor-prompt.md | 未来总协调提示词设计；当前 provider 使用源码中的实际受控提示 |
| system-prompt.md | 教学原则与最终目标，运行时模型提示以 HttpTutorModelProvider 为准 |
| evaluation-cases.json / cluster-evaluation-cases.json | 评测设计集；真实外部模型效果与多专家对照尚未验收 |
| artifact-examples.json | 各 DSL 的 schema 示例，不是生成完成的媒体 |

实际实现、调用链、下一步和风险见 [模块接手指南](../docs/02-modules.md)、[导师与证据](../docs/06-agent.md)、[集群设计](../docs/10-agent-cluster.md)与[验收](../evidence/runtime-review.md)。
