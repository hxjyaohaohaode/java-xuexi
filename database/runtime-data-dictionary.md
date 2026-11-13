# V003 持久运行时数据字典

本字典逐项对应 [V003__runtime_integrity.sql](V003__runtime_integrity.sql)。它补充 [V001 课程字典](data-dictionary.md)与 [V002 Agent 字典](agent-data-dictionary.md)，不改写前两份迁移。目标数据库为 MySQL 8.4；H2 适配迁移仅用于本地开发与测试。UUID 使用 `CHAR(36)`，哈希使用 64 位十六进制 SHA-256，`DATETIME(3)` 由服务按 UTC 写入。

V003 新建 5 张表、23 个字段，并向 `outbox_event` 添加 2 个可空字段。数据库约束只能表达本节明确列出的关系；当前身份、发布版本、输入 hash 和资源状态仍须应用服务核验。

## workspace_attempt 作答的工作空间归属

| 字段 | SQL 类型 | 可空 / 默认 | 约束与含义 |
|---|---|---|---|
| `workspace_id` | `CHAR(36)` | 不可空 / 无默认 | 复合主键第一列；FK → `learning_workspace(id)`；发起本次学习交互的空间 |
| `attempt_id` | `CHAR(36)` | 不可空 / 无默认 | 复合主键第二列；单列 UNIQUE；FK → `exercise_attempt(id)`；固定的已判分提交 |

主键为 `(workspace_id,attempt_id)`，`UNIQUE(attempt_id)` 保证同一提交最多归属一个空间。`LearningService` 在判分事务中同时写入提交、归属和工作空间事件。两个 FK 不会自动证明 attempt 的 user/release 与空间一致，因此服务在写入和查询时检查 owner、release，学习投影也通过连接条件再次校验。跨空间关联失败不能把学习证据归到错误目标。

## learning_family_evidence 不可刷新的独立样本

| 字段 | SQL 类型 | 可空 / 默认 | 约束与含义 |
|---|---|---|---|
| `user_id` | `CHAR(36)` | 不可空 / 无默认 | 主键第一列；FK → `app_user(id)`；认证用户 |
| `concept_id` | `CHAR(36)` | 不可空 / 无默认 | 主键第二列；FK → `concept(id)`；被归因的概念 |
| `family_id` | `CHAR(36)` | 不可空 / 无默认 | 主键第三列；同一道题的家族身份，用于跨版本/跨空间去重；不是虚构的单列外键 |
| `origin_attempt_id` | `CHAR(36)` | 不可空 / 无默认 | FK → `exercise_attempt(id)`；首次独立样本的原始作答，可回溯判分与题目版本 |
| `correct_weight` | `DECIMAL(10,4)` | 不可空 / 无默认 | 首次独立样本的正确贡献；错误样本为 0，不能因之后重试答对而覆盖 |
| `total_weight` | `DECIMAL(10,4)` | 不可空 / 无默认 | 该题家族向此概念贡献的权重 |

主键 `(user_id,concept_id,family_id)` 保证每人每概念每题家族最多一个独立样本。CHECK 为 `total_weight>0` 且 `0<=correct_weight<=total_weight`。family 身份经 `exercise_attempt→exercise` 解析，数据库没有独立的 family 主表，也不能仅凭主键验证原始作答归属和 hint 状态。

当前 `family-first-v1` 投影只接收服务端 `ATTEMPT_GRADED` 事实，独立资格由服务端判定。看过本家族反馈后的重试属于练习活动；事件重放由消费凭据去重。`learner_concept` 是聚合投影，本表保留其来源。当前是透明的加权计数启发式，不是经教学实验校准的掌握度模型，也不是“最近五题”滚动窗口。后续算法升级须保留版本并从事实重建，不能就地改写历史样本。

## workspace_projection 活动与事件新鲜度

| 字段 | SQL 类型 | 可空 / 默认 | 约束与含义 |
|---|---|---|---|
| `workspace_id` | `CHAR(36)` | 不可空 / 无默认 | PK；FK → `learning_workspace(id)`；投影所属空间 |
| `processed_sequence` | `BIGINT` | 不可空 / `0` | 已事务提交处理的连续事件游标，不能用它替代实体 revision |
| `activity_count` | `BIGINT` | 不可空 / `0` | 已处理语义事件计数；不是分数或掌握度 |
| `updated_at` | `DATETIME(3)` | 不可空 / 无默认 | 最后一次成功投影更新的 UTC 时间，由服务端 Clock 提供 |

CHECK 要求两个计数均非负。`workspace-activity-v1` 消费者要求首条 sequence 为 1，后续严格连续；重复事件不重复加计，序号缺口会失败并触发 outbox 重试。服务对比工作空间最新事件序号与 `processed_sequence` 暴露投影滞后。这个表可从事件重建，不能取代 `workspace_event` 事实日志。

## artifact_input_reference 版本固定的传递输入血缘

| 字段 | SQL 类型 | 可空 / 默认 | 约束与含义 |
|---|---|---|---|
| `workspace_id` | `CHAR(36)` | 不可空 / 无默认 | 来源作用域；与 `artifact_id` 组成 FK → `learning_artifact(workspace_id,id)` |
| `artifact_id` | `CHAR(36)` | 不可空 / 无默认 | 依赖作品；复合主键第一列，也是两个复合 FK 的组成部分 |
| `artifact_revision` | `INT` | 不可空 / 无默认 | 复合主键第二列；与 `artifact_id` 组成 FK → `artifact_revision(artifact_id,revision)` |
| `source_kind` | `VARCHAR(16)` | 不可空 / 无默认 | 复合主键第三列；CHECK 限定 `GOAL/MEMORY/ARTIFACT/ATTEMPT/KNOWLEDGE_CHUNK` |
| `source_id` | `CHAR(36)` | 不可空 / 无默认 | 复合主键第四列；多态资源 ID，没有跨多个目标表的伪 FK |
| `source_revision` | `INT` | 不可空 / 无默认 | 直接来源或祖先来源的固定版本；CHECK `>0` |
| `source_hash` | `CHAR(64)` | 不可空 / 无默认 | 直接来源或祖先来源的规范化内容哈希；服务重新解析与计算后比较 |

主键为 `(artifact_id,artifact_revision,source_kind,source_id)`，同一作品版本不能为同一个来源身份同时登记两个冲突版本。索引 `ix_artifact_input(workspace_id,source_kind,source_id,source_revision)` 用于查找撤回/变更影响的下游作品。

作品版本的 `input_refs` 仍只保存调用者声明的直接输入，`artifact_dependency` 也保留直接作品边；本表保存展开后的直接与传递来源，作为撤回查询索引。多个祖先路径遇到同一 `source_kind/source_id` 时去重；revision 或 hash 不一致则返回 `LINEAGE_VERSION_CONFLICT`，不覆盖旧值，也不依赖主键异常处理冲突。

保存时，在既有 user → workspace 锁内检查来源归属、发布成员关系、revision/hash、撤回状态及派生环。一个新版本最多登记 256 个不同的直接与传递来源。对每个来源，新增后仍非 `STALE` 的依赖版本不能超过 256；同一事务替换根作品将使其旧下游失效时，这些确定被替代的版本不占新增额度。每次祖先或后代查询最多返回 257 行用于识别越界；后代直接使用传递索引和版本状态查询，不逐来源遍历整张依赖图。保存超限返回 `LINEAGE_LIMIT`，因此服务不能先接受第 257 个有效依赖，等用户撤回时再失败。

撤回一次最多将 256 个依赖版本标为 `STALE`，并与来源撤回、内容清空和事件写入一起提交。历史 `STALE` 版本保留内容及索引但不继续占有效扇出额度；传递索引使已失效的中间节点不会遮住仍有效的下游。数据库复合 FK 仅保证目标空间和版本存在，多态来源归属及闭包一致性由服务验证。

V003 首次随本轮 v0.3 交付；没有既有正式 V003 数据迁移要求。若开发库或人工导入数据仍只有直接索引，或已超过有效扇出上限，服务通过有界检查返回 `LINEAGE_REBUILD_REQUIRED`，整笔事务回滚，不声称撤回成功、也不发布部分失效结果。此类旧数据必须由维护者修复或重建后再使用；没有无界在线补图或隐式数据删除。

## local_evidence_workflow 本地证据工作流参数

| 字段 | SQL 类型 | 可空 / 默认 | 约束与含义 |
|---|---|---|---|
| `run_id` | `CHAR(36)` | 不可空 / 无默认 | PK；FK → `agent_run(id)`；已经准入的持久任务 |
| `query_text` | `VARCHAR(1000)` | 不可空 / 无默认 | 本人私有检索问题；不是可执行代码，也不应进入公开日志 |
| `adapter_version` | `VARCHAR(32)` | 不可空 / 无默认 | 固定的本地工作流实现版本；不代表模型版本 |
| `created_at` | `DATETIME(3)` | 不可空 / `CURRENT_TIMESTAMP(3)` | 创建时刻，UTC 约定 |

该表只支持明确启用的确定性本地检索工作流。`run_id` 对应的 owner/workspace、目标、快照、预算和执行状态仍由任务运行时控制；不能只插入本表就绕过准入。SQL 长度是存储上限，业务 API 与检索服务可采用更小上限，调用方必须同时满足当前契约。这个工作流不调用 LLM、不写自然语言导师答案、不生成媒体或导出文件。

## 6. `outbox_event` 的 ALTER

V001 原有字段与约束见课程字典；V003 只增加以下字段，不删除或重置已有事件：

| 新增字段 | SQL 类型 | 可空 / 默认 | 当前语义 |
|---|---|---|---|
| `next_attempt_at` | `DATETIME(3)` | 可空 / 隐含 `NULL` | 下次允许重投的 UTC 时间；`NULL` 表示没有该时间限制，不是最终任务 deadline |
| `last_error_code` | `VARCHAR(64)` | 可空 / 隐含 `NULL` | 安全错误类别；当前派发失败记录 `CONSUMER_FAILED`，不保存异常文本、SQL 或私人内容 |

现有 `attempts/published_at` 与新增字段共同控制重投。当前派发器在失败后延后 10 秒、累计最多 5 次；达到上限后保留未发布事件作为待处理死信。按空间顺序处理时，失败的前序事件会阻塞同空间后继，其他空间仍可继续。不能跳过前序故障后把投影标成最新。

消费者业务更新、`event_receipt` 去重凭据和 `published_at` 在同一事务提交；传输语义仍为至少一次。`retryDeadLetter` 是内部维护方法，没有学习者 HTTP 入口；运维人员须先修复原因再重投。重放/清理时必须共同考虑事件、消费凭据、投影与保留窗口。

## 7. 运行验证与未覆盖范围

代码使用上述关系实现学习证据、来源失效和可恢复任务链。实际测试命令、数据库引擎、通过/失败和未运行项目见 [v0.3 验收记录](../evidence/runtime-review.md)。本字典是字段与责任说明，不替代真实 MySQL 迁移、锁竞争、备份恢复或生产容量证据；正式内容发布、外部模型、编译沙箱和媒体导出也不能因表已存在而标成可用。
