# 三个开源项目的源码研究与迁移取舍

研究日期2026-11-13；仅阅读公开资料和指定源码，没有运行三个完整系统，也没有证明其宣传中的所有教学/自主能力。下表是“已读证据→本项目设计”的映射，不复制整个项目或借用其品牌。

| 仓库 | 固定参考提交 | 关键已读文件 |
|---|---|---|
| THU-MAIC/OpenMAIC | `65d5ac408301a2916d1d7e81b037855760a286ab` | README.md、lib/chat/pi/README.md、lib/server/agent-runtime/runner-contract.ts、mutation-fence.ts |
| deepseek-ai/dsh-superagent-prototype | `8c32a9dc5d72c033816c6a8110b37f40cf94fba4` | README.md、docs/001-domain.md、docs/003-execution.md、src/core/engine.ts |
| deepseek-ai/deepseek-harness | `39163dd30f703a79079e2d2446545cff3c453738` | README.md、docs/architecture.md、docs/subsystems/agent-team.md、tools.md（重点为工具契约、作用域、并行与取消段落） |

## OpenMAIC → 学习场景与可编辑作品

README描述了大纲→场景、课堂角色、材料处理、PPTX/HTML等导出和独立渲染服务。课堂context文档强调每轮读取当前白板状态、快照版本、失败不是空白、视觉预览与实际浏览器状态的区别；mutation-fence 源码在异步变更前后检查取消。

迁移到 TypeForge：类型化学习作品DSL、场景/素材/导出分离、当前画布快照、基于 revision 的局部重做、取消后的提交屏障。改为 Java 控制平面+渲染 worker，数据库按课程选择MySQL；不照搬其Postgres/Next全栈。其README中的模型判题不能直接迁入本项目客观泛型判分，保留javac/确定规则为事实裁决。

依据：[仓库说明](https://github.com/THU-MAIC/OpenMAIC/tree/65d5ac408301a2916d1d7e81b037855760a286ab)、[课堂上下文](https://github.com/THU-MAIC/OpenMAIC/blob/65d5ac408301a2916d1d7e81b037855760a286ab/lib/chat/pi/README.md)、[取消屏障](https://github.com/THU-MAIC/OpenMAIC/blob/65d5ac408301a2916d1d7e81b037855760a286ab/lib/server/agent-runtime/mutation-fence.ts)。

## Superagent 原型 → 主动事件与持久任务

领域模型将外部事件、上下文订阅、任务授权和执行实例分开；同一个事件去重，已有任务可接续；engine 通过事务入口协调领域变更与外部副作用。执行文档区分运行、等待、人工问题、失败和恢复，强调先保存要做的外部工作再执行。

迁移到 TypeForge：学习语义事件→确定性准入→意图研判→新建/接续学习任务；学生跟进要求进入运行收件箱；任务本身不跟随某一个模型会话生死。采用自己的用户/工作空间资源权限。原型中执行器以操作者OS权限运行及某些命令wrapper不能构成安全边界，不能照搬到面向学生的代码/媒体执行服务。它是研究原型，README明确并非生产成品，部分文章方向并未实现。

依据：[领域模型](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/docs/001-domain.md)、[执行与恢复](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/docs/003-execution.md)、[事务Engine](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/8c32a9dc5d72c033816c6a8110b37f40cf94fba4/src/core/engine.ts)。

## Harness → 能力边界与可回放编排

架构文档说明模型、工具、会话日志和Agent loop可替换；持久会话事件与实时执行事件有不同用途。Team任务图有revision、阻塞边和循环检查；writeScopes是建议范围并非锁。工具文档要求输入/输出契约、作用域、取消语义与显式并发安全。

迁移到 TypeForge：Java `AgentTool<I,O>` 与运行时schema双重校验、白名单能力注册、durable run/step/tool events、可回放上下文、可替换模型与渲染器、结构化结果和可取消工具。额外实现真正的作品scope lease与CAS，不能误用任务板警告作为互斥控制；任务状态变化后需要显式唤醒Agent，不能假设任务板自动发送消息。Harness仍为developer preview，锁定参考版本并隔离adapter，不直接依赖不断变化的全部框架。

依据：[架构](https://github.com/deepseek-ai/deepseek-harness/blob/39163dd30f703a79079e2d2446545cff3c453738/docs/architecture.md)、[任务图](https://github.com/deepseek-ai/deepseek-harness/blob/39163dd30f703a79079e2d2446545cff3c453738/docs/subsystems/agent-team.md)、[工具契约](https://github.com/deepseek-ai/deepseek-harness/blob/39163dd30f703a79079e2d2446545cff3c453738/docs/subsystems/tools.md)。

## 许可和研究边界

三个项目README均声明根许可证为MIT，具体复用时还要检查固定提交LICENSE和第三方文件。OpenMAIC的mathml2omml等子包有独立许可，不能只看根MIT。本次是架构研究与本项目原创骨架，没有直接复制三个仓库的实现或资源；若未来直接复用，登记文件/commit/改动与适用许可。

“高智能”是本项目要通过真实任务评测实现的目标。上述源码证明的是具体机制存在，不能代替本项目模型、教学质量、媒体质量和可靠性的实际验证。
