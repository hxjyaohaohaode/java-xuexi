# 接口规范与前后端约束

完整机器契约见 [OpenAPI](../contracts/openapi.json)，操作索引见 [contracts/README.md](../contracts/README.md)。只有 GET `/api/v1/health`、GET `/api/v1/framework` 已实现；其余契约均标 `x-status: planned`，当前请求会被拒绝，不能按完整业务接口演示。

## 1. 公共约定

JSON 使用 camelCase；外部 ID 为 UUID 字符串；G01 等为可读 concept code，不是 UUID。时间 ISO-8601 UTC。成功直接返回 DTO，错误用 `code/message/traceId/retryable`（规划）；框架安全过滤器的拒绝响应尚未采用业务错误包装，前端正式接入前要统一。

服务端使用 Bean Validation、显式 JSON 解析与业务校验；Java 泛型与 TypeScript 类型都会在运行时出现边界，不能替代输入验证。未知字段拒绝，枚举严格匹配；普通问答/练习 JSON 上限初始 64 KiB；作品DSL端点单独限2 MiB，媒体通过限定上传票据进入对象存储，不能扩大所有接口的正文上限。每种DSL另限节点数、嵌套深度和渲染时长。源码上限额外按 UTF-8 **字节**检查 32 KiB；OpenAPI maxLength 是字符数，不能当作字节限制。

返回 DTO 单独定义，数据库实体不直接序列化。题目列表/详情没有 answerSpec、隐藏测试；密码 writeOnly；不把 SQL、内部堆栈、完整上游错误或敏感提示词返回。

## 2. 身份与重试

认证采用同源会话 cookie；所有写入要求 CSRF header，包括登录/注册（先 GET csrf）。会话建立/权限变更后轮换会话，防止 fixation。普通 API userId 由服务端获取，不接受请求体自报身份；维护接口额外要求角色。

写入型作业/提交使用 `Idempotency-Key`（16–64 字符随机 key），作用域 `(user,operation,key)`。同输入重放返回同资源，不同输入同 key 返回 409；首次异步返回 202。幂等 key 不替代 userId 校验。轮询初始 500ms，再退避到 2 秒、最多 30 次，停留后台暂停；超过等待时间提示稍后查看而不重新提交。

输入中的 hintLevel 只表示请求哪一级提示；服务端维护本题历史最高提示级别，禁止客户端改成 0 重置为“独立作答”。导师读取 attempt/conversation 时必须核对相同用户与发布。COMPILE_AND_RUN 需要题型/权限允许，不能由任意客户端选择后自动获得运行权限。

## 3. 提交与反馈示例

```json
{
  "releaseId": "13e88a06-8116-4e51-a885-6242ad086660",
  "exerciseId": "30932c98-fc99-40aa-b382-4baa7d167f4d",
  "exerciseRevision": 2,
  "answer": {"kind": "CODE", "source": "class Demo { }"}
}
```

这些 UUID 是契约示例，不表示数据库真实记录。选择题提交 `kind=CHOICE, optionIds=[...]`，文本题用 `kind=TEXT,value=...`；服务端再核对题型与 answer 分支相符。状态 PENDING/GRADING 不显示分数；FAILED 表示系统未得出评分，不显示“答错”。错误反馈必须区分编译失败、预期编译失败、unchecked 警告、运行错误与资源故障。

## 4. 版本与并发

知识/题目查询必须给 releaseId；题目详情还需 revision。服务端检查 release 的成员关系，不能只看该 revision 存不存在。发布和回退带 expectedManifestHash，比较失败 409。

稿件修改通过创建新 revision 实现，提交 `baseRevision`（首版 0）且新 revision 必须为 base+1；应用锁定稳定 ID 的版本序列，旧 base 返回 409，不覆盖别人更改。用户偏好使用 If-Match 与 revision，不匹配 412。已发布清单禁止就地修改，修订后重新发布。

## 5. 错误语义

| HTTP | 典型 code | 客户端动作 |
|---|---|---|
| 400 | INVALID_JSON / VALIDATION_FAILED | 定位字段，不自动重试 |
| 401 | SESSION_REQUIRED | 保存草稿后引导登录 |
| 403 | FORBIDDEN / CSRF_FAILED | 说明权限或刷新会话，不能循环重试 |
| 404 | RESOURCE_NOT_FOUND | 不泄露其他用户资源存在性 |
| 409 | IDEMPOTENCY_CONFLICT / VERSION_CONFLICT / INVALID_TRANSITION | 刷新并比较，不丢失输入 |
| 412 | PRECONDITION_FAILED | 偏好版本冲突 |
| 422 | UNSUPPORTED_SYNTAX / INVALID_RELEASE_MEMBERSHIP | 显示支持范围或修正请求 |
| 429 | RATE_LIMITED / QUEUE_FULL | 尊重 Retry-After，保留草稿 |
| 503 | MODEL_UNAVAILABLE / WORKER_UNAVAILABLE / INDEX_UNAVAILABLE | 展示局部不可用和可继续的操作 |

## 6. 后续补充端点

当前契约固定关键链路。用户数据导出/删除、密码修改、详细后台筛选、批量导入和 SSE 等扩展在对应阶段先补契约再实现，不能自行绕开权限/幂等/版本规则。前端通过模块能力状态决定可用入口；不为尚未实现的端点返回演示成功。

## 7. 集群与作品契约

当前共65个操作/115个schema。工作空间事件只接受 VIEW_CHANGED/SELECTION_COMMITTED/HELP_REQUESTED/DRAFT_CHANGED；客户端不能提交成绩或“生成成功”。输入的所有ContextRef还需检查资源归属、revision、hash与release，schema通过不代表引用可信。

AgentRun 创建输入只有目标、上下文、输出类型与预算。计划由服务端形成；任何模型产生的role/tools/scopes仍需准入。RunCommand的STEER/PAUSE/RESUME/CANCEL都带expectedPlanRevision/expectedRunEpoch；并发失败返回409保留输入。运行事件采用afterSequence分页，未来SSE复用同一持久日志；事件保留期外的游标明确返回冲突并重取快照。

作品有八类kind和七个顶层DSL分支（INTERACTION/NOTEBOOK共用六类交互块）。基础DSL定义已存在；导出适配器升级需要同步schemaVersion，不允许悄悄接受任意对象。除了JSON Schema，还要验证图节点/边、slide元素x+width≤1/y+height≤1、时间线素材时长/轨道冲突、发音词典大小、blockId唯一、允许动作与block类型相符、资源kind与export格式兼容。

没有向客户端公开自由工具执行端点。前端只能发送业务意图/受控交互，由服务端映射能力；高自由度来自内容、媒介、工作路径和局部修改，不来自任意系统权限。M09的短问答接口继续存在，复合任务复用M13，不在每个页面各写一套Agent循环。
