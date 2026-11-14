# 当前接口规范与前后端约束

本章说明 v0.4 的实际调用规则。完整字段、状态码、必填 header 与当前操作状态以 [OpenAPI](../contracts/openapi.json) 为准，按模块和路径检索见 [自动生成的接口索引](../contracts/README.md)。[开发接手指南](02-modules.md)区分后端、页面与后续目标；登录后的 `/api/v1/capabilities` 反映这次部署实际启用的能力。

## 1. 传输、身份与输入边界

API 前缀为 `/api/v1`，JSON 字段使用 camelCase。登录态为服务端会话，身份不从请求中的 userId、角色或工作空间 ID 推断。先 GET `/auth/csrf`，在写请求中发送返回的 CSRF header；登录、注册或退出后重新获取 token。私有响应不作为公开缓存。跨用户对象通常返回 404，管理接口必须同时通过会话角色检查和数据库中的当前角色检查。

请求使用 Bean Validation、严格 JSON Schema 和业务校验三层检查。Java 泛型、TypeScript 类型与前端禁用按钮均不能替代后端准入。未知字段、非法枚举、集合过大和缺少必要 header 会被拒绝。API 写请求 JSON 正文统一限制为 262144 字节；已知长度和分块传输都在解析前限制。Jackson 限制嵌套深度 64，字符串和数值还受各字段约束。

UUID 是资源标识，不是权限令牌。revision 使用 int32 范围；epoch、事件 sequence 不超过 JavaScript 安全整数范围。时间由服务器以 UTC 记录，学习事件不能用客户端时间伪造发生顺序。二进制素材上传、用户源码编译执行没有因为新的文本导出而被开放。

## 2. 幂等、并发和响应丢失

需要 `Idempotency-Key` 的操作已逐项写在 OpenAPI 中。键只在所属用户和业务操作范围内有效，服务端保存规范化请求 hash；同键同输入返回既有结果，同键不同输入返回冲突。前端遇到断线先保留原输入与键，不生成新键重复执行。幂等不是跨用户结果共享，也不是供应商保证不收费的证明。

| 资源 | 当前写入约束 | 冲突后的正确动作 |
|---|---|---|
| 用户偏好 | `If-Match: "revision"` 与请求体 revision 必须一致 | 412 后刷新当前偏好，再明确合并 |
| 工作空间和学习目标 | expectedRevision | 409 后保留本地内容、显示远端版本 |
| 作品源版本 | baseRevision；新版本严格为 base+1 | 409 不覆盖已保存内容；前端保留草稿 |
| Agent 运行命令 | expectedPlanRevision + expectedRunEpoch | 重新读取运行；旧 epoch 不能提交 |
| 导师取消 | expectedEpoch + 幂等键 | 重读请求终态；取消不代表供应商未计费 |
| 发布与历史激活 | 固定 manifest hash + expectedActiveRevision | 并发切换不能覆盖；幂等重放不能重新激活旧版 |

上下文创建产生新的不可变快照，没有通用快照幂等承诺。客户端事件以 UUID 和内容去重；它们只表示阅读/选区等允许的行为，不能声称已经判分或已经完成生成。

## 3. 可以直接运行的业务链

| 场景 | 调用方向 | 最终可信事实 |
|---|---|---|
| 学习与练习 | 发布/概念 → 工作空间 → 题目 → `/attempts` → 学习进度/复习 | 服务端固定题目版本与客观评分；题目家族去重 |
| 类型实验 | `/labs/sessions` → 本人实验历史 | 有限规则的 ALLOWED、DENIED 或 UNSUPPORTED，不冒充完整 javac |
| 阅读 | `/learning-events` → 工作空间事件 | READ_STARTED / READ_COMPLETED 只是活动，不直接增加掌握度 |
| 错题复练 | 本人作答 → `/notebook-drafts` → `/notebooks/{id}/actions` | 来源仍有效、块版本匹配后才调用客观判分 |
| 证据辅导 | 会话 → 快照 → `/tutor/requests` → 轮询结果 | 明确 EVIDENCE_ONLY 或 MODEL，保留来源与计量边界 |
| 回答转作品 | 完成且来源有效的导师请求 → `/tutor/requests/{id}/document` | 返回 DRAFT 的 artifactId/revision，保存完整引用血缘 |
| 下载 | 保存作品 → `/artifacts/{id}/exports` → export job → download | 固定 revision/renderer/hash 的真实字节；下载再次验证归属和来源 |
| 内容发布 | 来源 → 人工录入 → 卡/题版本 → 第二人审核 → 发布清单 → 发布/激活 | 审核证据和版本不可变；旧工作空间保持原 release |

题目详情必须绑定 releaseId 和 revision；客户端不能访问 answer_spec。CHOICE 的 answer 使用 `{ "kind": "CHOICE", "optionIds": ["选项ID"] }`。CODE/TEXT 合约分支是未来判分扩展，并不代表对应 grader 已启用。读答案、错题再练和导师提示不能重置同题家族为“首次独立作答”。

## 4. 导师与编排的两个执行入口

`/tutor/requests` 是持久队列。EVIDENCE_ONLY 整理真实 BM25 片段；MODEL 通过已配置的非流式 Chat Completions HTTP 适配器请求外部模型。没有配置时 MODEL 显式返回能力缺失，不能降级成模板却标为模型回答。模型输出经过结构、引用与长度校验；供应商响应、费用和教学质量仍需在实际部署中评测。

请求在同一用户、会话、工作空间、课程版本和快照边界内运行。领取、预留、提交分别使用短事务；HTTP 等待不持有数据库锁。超时、取消、来源撤回、账号禁用和空间归档都不能让迟到结果恢复为可见答案。已派发但结果不明的外部请求保留 UNKNOWN 预留，不能靠自动重试制造重复费用。

`/workspaces/{id}/evidence-runs` 运行固定的本地检索 DAG，可查步骤和事件并暂停、恢复或取消。通用自然语言任务规划、自由工具执行和多专家自主调度仍未接通；STEER 不提供伪实现。两个入口共享工作空间和来源约束，不能把一个导师 HTTP 适配器计为所有规划工具均已注册。

## 5. 作品、引用和导出

每次保存/使用作品都验证其当前 revision、实际 JSON hash、工作空间、审核状态与来源。直接输入最多 64 个，直接与传递来源合计最多 256 个。读取上下文、导师准入、错题动作、导出领取和下载复用同一有界来源闭包检查；自然到期的记忆即使尚未产生撤回事件也会阻断后续使用。

当前可导出 DOCUMENT 的 Markdown/HTML、文本及代码幻灯片的 HTML、DIAGRAM 的 SVG、NOTEBOOK/INTERACTION 的静态 HTML。包含未实现素材类型的导出明确拒绝，不静默丢掉图片。静态错题文件没有离线保密判分能力。PPTX、DOCX、PDF、PNG、音视频和对象存储素材链仍待接入。

导出提交返回 202 和持久 job，成功后返回私有下载路径。下载响应为附件，带 no-store、nosniff 和严格 CSP；实际字节有 SHA-256。被替换/撤回/到期的来源使下载失效；保存过的文件无法由平台追回。Markdown/HTML/SVG 渲染不会执行模型或用户提供的 JavaScript。

## 6. 当前错误与客户端处理

| HTTP | 典型错误/场景 | 客户端动作 |
|---|---|---|
| 400 | INVALID_INPUT、schema/header/字段非法 | 定位输入，不自动重试 |
| 401 | UNAUTHENTICATED / INVALID_CREDENTIALS | 保留可恢复输入，重新登录 |
| 403 | CSRF_INVALID / FORBIDDEN | 仅 CSRF 失效时刷新 token；权限问题不循环重试 |
| 404 | NOT_FOUND | 不泄露他人资源存在性 |
| 409 | VERSION_CONFLICT、来源失效、血缘版本冲突 | 刷新事实并比较；不能覆盖旧输入 |
| 412 | 偏好 If-Match 不匹配 | 读取当前 ETag 后合并 |
| 413 | REQUEST_TOO_LARGE | 缩小正文，保留草稿 |
| 422 | 不支持的操作、组合或语义 | 展示具体范围，修正请求 |
| 429 | 导师准入额度或待处理上限 | 显示限额，稍后查看已有请求 |
| 503 | CAPABILITY_UNAVAILABLE / COURSE_NOT_CONFIGURED | 呈现能力缺口，不显示成功 |
| 503 | CONTENT_INTEGRITY_FAILURE / ARTIFACT_INTEGRITY_FAILURE | 停止使用受影响结果，由维护者检查实际内容/hash |
| 503 | DATABASE_OPERATION_FAILED | 保留幂等键；先核对是否已提交，避免重复副作用 |

错误名以具体服务和契约为准。任务进入 FAILED、CANCELLED 或来源失效，与 HTTP 读取任务成功返回 200 是两回事；页面必须同时显示业务状态。

## 7. 开发新增接口的收口规则

先明确事实归属、身份/课程/工作空间边界、幂等和失败语义，再实现端点、schema、DTO、SQL、前端调用及回归。运行 `python scripts/sync_contract_index.py` 更新索引，再运行 `python scripts/verify.py` 核对路由和契约。密码修改、账户数据清理、批量导入、SSE、沙箱和完整媒体仍是后续工作，入口不得返回伪造成功。
