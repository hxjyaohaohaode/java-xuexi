# Agent设计资产

| 文件 | 用途 | 当前状态 |
|---|---|---|
| [roles.json](roles.json) | 14类专家和允许工具 | 设计；没有运行中的模型 |
| [tools.json](tools.json) | 16项能力的输入/输出schema、超时、取消和幂等边界 | schema可解析；适配器待实现 |
| [run-policy.json](run-policy.json) | 短问答与复杂任务的执行上限 | 计划内核验证部分上限；持久预算尚未实现 |
| [example-plan.json](example-plan.json) | 三道PECS错题→互动册/PPT/微课的10节点计划 | 结构、依赖和能力静态检查通过；非真实模型轨迹 |
| [artifact-examples.json](artifact-examples.json) | 八类作品的最小协议样例 | schema示例；非完整可用作品；UUID为虚构契约样例 |
| [system-prompt.md](system-prompt.md) | M09局部教学专家规则 | 尚未实测 |
| [conductor-prompt.md](conductor-prompt.md) | M13总协调的计划提案边界 | 尚未实测 |
| [evaluation-cases.json](evaluation-cases.json) | 16个教学起始案例 | NOT_RUN |
| [cluster-evaluation-cases.json](cluster-evaluation-cases.json) | 30个集群任务与异常案例 | NOT_RUN；同题比较单Agent/固定多Agent/动态集群 |

工具schema位于 [OpenAPI components](../contracts/openapi.json)，这些内部工具类型不自动成为公共HTTP端点。任何一个适配器都必须经过owner/workspace、角色能力、输入schema、run epoch、租约、预算、输出schema和证据校验。

`concurrencySafe=true`只标注纯读候选；写工具标为false，只有运行时证明资源范围不冲突并实际取得租约后才可与其他节点并行。Java `PlanValidator`是确定性候选校验，不是权限系统、事务调度器或模型运行器。示例中的artifact:slides等是讲解用别名，执行前必须解析成当前用户空间内真实稳定ID。
