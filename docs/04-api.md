# 接口规范与前后端约束

完整机器契约见 [OpenAPI](../contracts/openapi.json)，操作索引见 [contracts/README.md](../contracts/README.md)。v0.3 已实现认证、课程读取、检索、选择题作答、工作空间、作品源版本、事件与受控本地运行等业务接口。每个操作以 `x-status` 为准，模块以 [modules.json](../contracts/modules.json) 为准；数量和实际验证记录统一见 [v0.3 验收报告](../evidence/runtime-review.md)。契约中的 planned 端点不能按已实现演示；登录后的 `/api/v1/capabilities` 返回当前已启用与不可用能力。

## 1. 公共约定

JSON 使用 camelCase；外部 ID 为 UUID 字符串；G01 等为可读 concept code，不是 UUID。时间 ISO-8601 UTC。成功直接返回 DTO；当前业务错误使用 `application/problem+json`，含 `status/detail/code`，由 `ProblemHandler` 处理的错误另有 `traceId`。认证过滤器返回最小的 status/code/detail；不能假设每个错误都有 `retryable` 或 traceId。前端按契约和 HTTP 状态分类，不解析内部异常文本。

当前服务端使用 Bean Validation、JSON Schema 与业务校验；Java 泛型与 TypeScript 类型不能替代运行时输入验证。未知字段拒绝、枚举严格匹配，字符串/集合限制按具体 schema 和服务校验执行。`/api/v1/` 下写方法的 JSON 请求有 **262144 字节（256 KiB）** 前置上限，对已知 Content-Length 与分块/未知长度正文都读取限额后判断；超过返回 413。Jackson 另限制嵌套深度 64、单个数值文本长度 64 和字符串长度，Schema 的结构化内容字符限制是另一层检查，不能与 UTF-8 字节混同。

revision 等字段按契约限制为 int32 范围，epoch/事件序号限制在 JavaScript 安全整数范围；调用方不能把 SQL BIGINT 的全部范围当作可无损传给浏览器的范围。原设计中的作品 2 MiB、用户源码 32 KiB 等是未来专用能力入口的候选预算，当前不能据此绕过统一 JSON 上限。媒体上传、用户编译与渲染入口仍不可用。

返回 DTO 单独定义，数据库实体不直接序列化。题目列表/详情没有 answerSpec、隐藏测试；密码 writeOnly；不把 SQL、内部堆栈、完整上游错误或敏感提示词返回。

## 2. 身份与重试

认证采用同源会话 cookie；所有写入要求 CSRF header，包括登录/注册（先 GET csrf）。当前登录轮换会话并清除旧 CSRF token，登录后重新获取；注册不会自动登录。普通 API userId 由服务端获取，不接受请求体自报身份；管理/内部接口当前默认拒绝，未来开放时还需角色校验。

契约中标注该header的创建/提交使用 `Idempotency-Key`（8–64 字符，字母/数字/`_.:-`，推荐UUID随机 key），作用域 `(user,operation,key)`。同输入重放返回同资源，不同输入同 key 返回 409；首次异步返回 202。幂等 key 不替代 userId 校验。轮询初始 500ms，再退避到 2 秒、最多 30 次，停留后台暂停；超过等待时间提示稍后查看而不重新提交。

工作空间更新、作品版本保存、记忆撤回和运行命令采用revision/epoch CAS，没有通用幂等header；响应丢失后先GET核对再处理冲突。工作空间客户端事件使用事件UUID与相同内容去重。上下文创建每次产生新快照，不承诺按幂等key返回旧快照。

当前作答入口不允许客户端自报独立资格；首次题目家族作答由服务端标记，反馈后的重试记录为练习。未来导师提示入口需由服务端维护本题历史最高提示级别，禁止重置为“独立作答”，读取 attempt/conversation 必须核对相同用户与发布。COMPILE_AND_RUN 尚未开放，未来仍需题型/权限允许，不能由客户端选一个模式自动获得执行权限。

## 3. 提交与反馈示例

```json
{
  "workspaceId": "2a0f0c3f-6d1b-4e4e-a11d-5046406d4638",
  "releaseId": "13e88a06-8116-4e51-a885-6242ad086660",
  "exerciseId": "30932c98-fc99-40aa-b382-4baa7d167f4d",
  "exerciseRevision": 1,
  "answer": {"kind": "CHOICE", "optionIds": ["B"]}
}
```

这些 UUID 是契约示例，不表示数据库真实记录；调用时先查询当前发布、题目并创建本人空间。当前选择题用 `kind=CHOICE, optionIds=[...]`，服务端核对选项集合与固定题目版本并返回客观判分。契约为未来题型保留的 CODE/TEXT 分支不代表对应 grader 可用。异步题型启用后，PENDING/GRADING 不显示分数，FAILED 不能显示成“答错”；编译失败、预期编译失败、警告、运行错误与基础设施故障须分开。

## 4. 版本与并发

知识/题目查询必须给 releaseId；题目详情按 revision 读取（未提供时默认1，前端应传当前题目版本）。服务端检查 release 的成员关系，不能只看该 revision 存不存在。正式发布/回退仍未实现；规划请求带 expectedManifestHash，比较失败不能覆盖。

当前作品修改通过创建新 revision 实现，提交 `baseRevision`（首版 0）且新 revision 必须为 base+1；应用锁定稳定 ID 的版本序列，旧 base 返回 409，不覆盖别人更改。工作空间修改另用 expectedRevision。用户偏好 If-Match/412 是后续契约，不与已实现字段混用。已发布清单禁止就地修改，修订后须重新发布。

## 5. 错误语义

| HTTP | 典型 code | 客户端动作 |
|---|---|---|
| 400 | INVALID_INPUT | 定位字段或补齐必需 query/header，不自动重试 |
| 401 | UNAUTHENTICATED / INVALID_CREDENTIALS | 保存草稿后引导登录或修正凭据 |
| 403 | FORBIDDEN | 说明权限或重新获取 CSRF，不能循环重试 |
| 404 | NOT_FOUND | 不泄露其他用户资源存在性 |
| 409 | VERSION_CONFLICT | 刷新并比较，不丢失输入；同键不同输入也不能直接重发 |
| 413 | REQUEST_TOO_LARGE | 缩小 JSON 正文；保留草稿，不无限重试 |
| 503 | CAPABILITY_UNAVAILABLE / COURSE_NOT_CONFIGURED | 展示真实能力缺口，不宣告任务成功 |
| 503 | CONTENT_INTEGRITY_FAILURE / INDEX_CAPACITY_EXCEEDED | 停止使用受影响证据，由维护者核对内容或索引 |
| 503 | DATABASE_OPERATION_FAILED | 保存请求与幂等键，核对未知结果后按约定重试 |

上表列举当前主要错误；各操作仍以契约和实际服务为准。未来偏好冲突、限流、隔离编译和外部模型可能增加 412/422/429 等分类，不能把这些规划代码误认为当前已经具备对应保护。

## 6. 后续补充端点

当前契约固定关键链路。用户数据导出/删除、密码修改、详细后台筛选、批量导入和 SSE 等扩展在对应阶段先补契约再实现，不能自行绕开权限/幂等/版本规则。前端通过模块能力状态决定可用入口；不为尚未实现的端点返回演示成功。

## 7. 集群与作品契约

操作与 Schema 会随实施扩展，准确数量由契约索引和本轮验收记录汇总，本章不另硬编码。当前工作空间客户端事件只接受 VIEW_CHANGED/SELECTION_COMMITTED/HELP_REQUESTED/DRAFT_CHANGED；客户端不能提交成绩或“生成成功”。所有 ContextRef 还需检查资源归属、revision、hash 与 release，schema 通过不代表引用可信。

当前本地证据入口接收 goalId、snapshotId 与 query，服务端生成固定检索任务图；通用自然语言 AgentRun 创建仍属规划。任何模型未来产生的 role/tools/scopes 都必须准入。RunCommand 带 expectedPlanRevision/expectedRunEpoch；PAUSE/RESUME/CANCEL 由运行时控制，STEER 缺少规划适配器时明确返回能力不可用。当前事件轮询参数是 `after` 与 `limit`，按各自持久 stream sequence 恢复；未来 SSE 复用同一事实日志，增加事件清理时须同时实现过期游标与快照恢复协议。

作品有八类kind和七个顶层DSL分支（INTERACTION/NOTEBOOK共用六类交互块）。基础DSL定义已存在；导出适配器升级需要同步schemaVersion，不允许悄悄接受任意对象。除了JSON Schema，还要验证图节点/边、slide元素x+width≤1/y+height≤1、时间线素材时长/轨道冲突、发音词典大小、blockId唯一、允许动作与block类型相符、资源kind与export格式兼容。

没有向客户端公开自由工具执行端点。未来前端只能发送业务意图/受控交互，由服务端映射能力；高自由度来自内容、媒介、工作路径和局部修改，不来自任意系统权限。M09 短问答接口继续保留为契约规划，尚未接真实模型；复合任务后续复用 M13，不在每个页面各写一套 Agent 循环。
