# Agent 工作空间扩展数据字典

20张表的字段/约束来自V002；应用层事务不变量见 [数据库设计](../docs/03-data.md)。

## learning_workspace — 个人学习工作空间

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 工作空间UUID |
| `owner_id` | `CHAR(36) NOT NULL` | 由服务端会话决定的所有者 |
| `release_id` | `CHAR(36) NOT NULL` | 固定课程发布 |
| `title` | `VARCHAR(128) NOT NULL` | 可编辑标题 |
| `guidance_mode` | `VARCHAR(16) NOT NULL DEFAULT 'COLLABORATIVE'` | ON_DEMAND/COLLABORATIVE/QUIET |
| `revision` | `INT NOT NULL DEFAULT 1` | 状态乐观锁 |
| `event_sequence` | `BIGINT NOT NULL DEFAULT 0` | 事务内单调递增的事件序号 |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'` | ACTIVE/ARCHIVED |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`UNIQUE (id,release_id)`；`FOREIGN KEY (owner_id) REFERENCES app_user(id)`；`FOREIGN KEY (release_id) REFERENCES content_release(id)`；`CHECK (guidance_mode IN ('ON_DEMAND','COLLABORATIVE','QUIET'))`；`CHECK (status IN ('ACTIVE','ARCHIVED'))`；`CHECK (revision > 0 AND event_sequence >= 0)`；`INDEX ix_workspace_owner (owner_id,status,created_at)`。

## learning_goal — 学习目标及验收条件

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 目标UUID |
| `workspace_id` | `CHAR(36) NOT NULL` | 所属空间 |
| `goal_text` | `VARCHAR(4000) NOT NULL` | 用户目标 |
| `acceptance` | `JSON NOT NULL` | 可验证学习/作品条件 |
| `revision` | `INT NOT NULL DEFAULT 1` | 目标版本 |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'` | ACTIVE/ACHIEVED/ARCHIVED |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (id)`；`UNIQUE (workspace_id,id)`；`FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id)`；`CHECK (revision > 0)`；`CHECK (status IN ('ACTIVE','ACHIEVED','ARCHIVED'))`。

## workspace_event — 去重且有序的语义事件

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 事件UUID |
| `workspace_id` | `CHAR(36) NOT NULL` | 所属空间 |
| `sequence_no` | `BIGINT NOT NULL` | 服务端分配序号 |
| `event_type` | `VARCHAR(64) NOT NULL` | 受控事件类型 |
| `origin` | `VARCHAR(16) NOT NULL` | CLIENT/SERVER/AGENT |
| `payload` | `JSON NOT NULL` | 最小必要引用；不记录每次按键 |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | 服务端UTC |

约束：`PRIMARY KEY (id)`；`UNIQUE (workspace_id,sequence_no)`；`UNIQUE (workspace_id,id)`；`FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id)`；`CHECK (sequence_no > 0)`；`CHECK (origin IN ('CLIENT','SERVER','AGENT'))`；`INDEX ix_workspace_event (workspace_id,created_at)`。

## context_snapshot — 一致且可追溯的上下文快照

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 快照UUID |
| `workspace_id` | `CHAR(36) NOT NULL` | 所属空间 |
| `workspace_revision` | `INT NOT NULL` | 读取时空间版本 |
| `event_sequence` | `BIGINT NOT NULL` | 包含至哪个事件 |
| `references_json` | `JSON NOT NULL` | 类型化资源id/revision/hash引用 |
| `content_hash` | `CHAR(64) NOT NULL` | 规范化快照哈希 |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (id)`；`UNIQUE (workspace_id,id)`；`FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id)`；`CHECK (workspace_revision > 0 AND event_sequence >= 0)`。

## agent_run — 可中断恢复的专家运行

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 运行UUID |
| `workspace_id` | `CHAR(36) NOT NULL` | 所属空间 |
| `goal_id` | `CHAR(36) NOT NULL` | 同空间目标 |
| `snapshot_id` | `CHAR(36) NOT NULL` | 同空间快照 |
| `trigger_event_id` | `CHAR(36) NULL` | 触发去重；显式请求可为空 |
| `status` | `VARCHAR(24) NOT NULL` | 运行状态 |
| `plan_revision` | `INT NOT NULL DEFAULT 1` | 计划CAS版本 |
| `run_epoch` | `BIGINT NOT NULL DEFAULT 1` | 停止或改计划后递增，拒绝迟到提交 |
| `plan_json` | `JSON NOT NULL` | 校验过的任务图 |
| `budget_micros` | `BIGINT NOT NULL` | 金额上限：百万分之一美元 |
| `reserved_micros` | `BIGINT NOT NULL DEFAULT 0` | 尚未结算预留 |
| `spent_micros` | `BIGINT NOT NULL DEFAULT 0` | 已结算费用 |
| `token_budget` | `BIGINT NOT NULL` | token总上限 |
| `reserved_tokens` | `BIGINT NOT NULL DEFAULT 0` | 并行调用预留token |
| `spent_tokens` | `BIGINT NOT NULL DEFAULT 0` | 已结算token |
| `deadline_at` | `DATETIME(3) NOT NULL` | 运行截止UTC |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (id)`；`UNIQUE (workspace_id,id)`；`UNIQUE (trigger_event_id)`；`FOREIGN KEY (workspace_id,goal_id) REFERENCES learning_goal(workspace_id,id)`；`FOREIGN KEY (workspace_id,snapshot_id) REFERENCES context_snapshot(workspace_id,id)`；`FOREIGN KEY (workspace_id,trigger_event_id) REFERENCES workspace_event(workspace_id,id)`；`CHECK (plan_revision > 0 AND run_epoch > 0 AND token_budget > 0)`；`CHECK (reserved_tokens >= 0 AND spent_tokens >= 0 AND spent_tokens <= token_budget - reserved_tokens)`；`CHECK (budget_micros > 0 AND reserved_micros >= 0 AND spent_micros >= 0 AND spent_micros <= budget_micros - reserved_micros)`；`CHECK (status IN ('PLANNING','QUEUED','RUNNING','WAITING_USER','PAUSED','COMPLETED','PARTIAL','FAILED','CANCELLED'))`；`INDEX ix_run_schedule (status,deadline_at)`。

## agent_step — 专家节点与执行租约

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 步骤UUID |
| `run_id` | `CHAR(36) NOT NULL` | 所属运行 |
| `plan_revision` | `INT NOT NULL` | 所属计划版本 |
| `step_key` | `VARCHAR(64) NOT NULL` | 任务图稳定key |
| `role_key` | `VARCHAR(64) NOT NULL` | 专家注册表key |
| `status` | `VARCHAR(24) NOT NULL` | 步骤状态 |
| `input_refs` | `JSON NOT NULL` | 固定输入与证据引用 |
| `output_refs` | `JSON NOT NULL` | 候选/正式产物引用 |
| `read_scopes` | `JSON NOT NULL` | 已校验读范围 |
| `write_scopes` | `JSON NOT NULL` | 已校验写范围 |
| `lease_token` | `CHAR(36) NULL` | worker租约token |
| `lease_until` | `DATETIME(3) NULL` | 租约截止 |
| `attempt_count` | `INT NOT NULL DEFAULT 0` | 有限重试次数 |
| `required_output` | `BOOLEAN NOT NULL` | 完成目标所需节点 |

约束：`PRIMARY KEY (id)`；`UNIQUE (run_id,plan_revision,id)`；`UNIQUE (run_id,plan_revision,step_key)`；`FOREIGN KEY (run_id) REFERENCES agent_run(id)`；`CHECK (plan_revision > 0 AND attempt_count >= 0)`；`CHECK (status IN ('PENDING','READY','RUNNING','WAITING_TOOL','REVIEWING','SUCCEEDED','FAILED','SKIPPED','CANCELLED'))`；`CHECK ((lease_token IS NULL AND lease_until IS NULL) OR (lease_token IS NOT NULL AND lease_until IS NOT NULL))`；`INDEX ix_step_lease (status,lease_until)`。

## agent_step_dependency — 同运行同计划版本的依赖边

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `run_id` | `CHAR(36) NOT NULL` | 所属运行 |
| `plan_revision` | `INT NOT NULL` | 计划版本 |
| `step_id` | `CHAR(36) NOT NULL` | 后继节点 |
| `depends_on_id` | `CHAR(36) NOT NULL` | 前置节点 |

约束：`PRIMARY KEY (run_id,plan_revision,step_id,depends_on_id)`；`FOREIGN KEY (run_id,plan_revision,step_id) REFERENCES agent_step(run_id,plan_revision,id)`；`FOREIGN KEY (run_id,plan_revision,depends_on_id) REFERENCES agent_step(run_id,plan_revision,id)`；`CHECK (step_id <> depends_on_id)`。

## agent_message — 带来源的专家间消息

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 消息UUID |
| `run_id` | `CHAR(36) NOT NULL` | 所属运行 |
| `sender_role` | `VARCHAR(64) NOT NULL` | 发送角色 |
| `receiver_role` | `VARCHAR(64) NOT NULL` | 接收角色 |
| `delivery_state` | `VARCHAR(16) NOT NULL DEFAULT 'PENDING'` | PENDING/DELIVERED/ACKNOWLEDGED |
| `dedupe_key` | `VARCHAR(128) NOT NULL` | 消息去重键 |
| `kind` | `VARCHAR(16) NOT NULL` | FACT/PROPOSAL/QUESTION/VERDICT |
| `payload` | `JSON NOT NULL` | 结构化结论，不存隐藏思维链 |
| `evidence_refs` | `JSON NOT NULL` | 证据引用 |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (run_id) REFERENCES agent_run(id)`；`UNIQUE (run_id,dedupe_key)`；`CHECK (delivery_state IN ('PENDING','DELIVERED','ACKNOWLEDGED'))`；`CHECK (kind IN ('FACT','PROPOSAL','QUESTION','VERDICT'))`；`INDEX ix_message_run (run_id,created_at)`。

## tool_execution — 能力调用与外部副作用对账

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 调用UUID |
| `step_id` | `CHAR(36) NOT NULL` | 所属步骤 |
| `execution_key` | `VARCHAR(128) NOT NULL` | 服务端幂等键 |
| `tool_key` | `VARCHAR(64) NOT NULL` | 能力注册key |
| `input_hash` | `CHAR(64) NOT NULL` | 固定请求哈希 |
| `provider_job_id` | `VARCHAR(255) NULL` | 外部作业号 |
| `status` | `VARCHAR(24) NOT NULL` | ADMITTED/RUNNING/SUCCEEDED/FAILED/UNKNOWN/CANCELLED |
| `reserved_micros` | `BIGINT NOT NULL` | 调用前预留金额 |
| `actual_micros` | `BIGINT NULL` | 对账后实际金额 |
| `reserved_tokens` | `BIGINT NOT NULL DEFAULT 0` | 此调用预留token |
| `actual_tokens` | `BIGINT NULL` | 此调用实际token |
| `result_refs` | `JSON NOT NULL` | 结果引用 |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (id)`；`UNIQUE (execution_key)`；`FOREIGN KEY (step_id) REFERENCES agent_step(id)`；`CHECK (reserved_micros >= 0 AND (actual_micros IS NULL OR actual_micros >= 0))`；`CHECK (reserved_tokens >= 0 AND (actual_tokens IS NULL OR actual_tokens >= 0))`；`CHECK (status IN ('ADMITTED','RUNNING','SUCCEEDED','FAILED','UNKNOWN','CANCELLED'))`。

## learning_artifact — 可编辑多模态学习作品

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 作品UUID |
| `workspace_id` | `CHAR(36) NOT NULL` | 所属空间 |
| `goal_id` | `CHAR(36) NULL` | 同空间学习目标；手工空白作品可为空 |
| `kind` | `VARCHAR(24) NOT NULL` | 作品DSL类型 |
| `title` | `VARCHAR(255) NOT NULL` | 标题 |
| `current_revision` | `INT NOT NULL DEFAULT 0` | 0表示尚无提交版本 |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (id)`；`UNIQUE (workspace_id,id)`；`FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id)`；`FOREIGN KEY (workspace_id,goal_id) REFERENCES learning_goal(workspace_id,id)`；`CHECK (current_revision >= 0)`；`CHECK (kind IN ('DOCUMENT','SLIDES','DIAGRAM','IMAGE','AUDIO','VIDEO','INTERACTION','NOTEBOOK'))`；`INDEX ix_artifact_workspace (workspace_id,created_at)`。

## artifact_revision — 不可变的作品DSL版本

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `artifact_id` | `CHAR(36) NOT NULL` | 作品 |
| `revision` | `INT NOT NULL` | 版本 |
| `schema_version` | `VARCHAR(32) NOT NULL` | DSL版本 |
| `content_json` | `JSON NOT NULL` | 严格schema内容 |
| `content_hash` | `CHAR(64) NOT NULL` | 规范化源哈希 |
| `input_refs` | `JSON NOT NULL` | 上游目标/素材/代码/证据版本 |
| `producer_step_id` | `CHAR(36) NULL` | 人工编辑时为空 |
| `review_status` | `VARCHAR(16) NOT NULL` | DRAFT/VERIFIED/REJECTED/STALE |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (artifact_id,revision)`；`FOREIGN KEY (artifact_id) REFERENCES learning_artifact(id)`；`FOREIGN KEY (producer_step_id) REFERENCES agent_step(id)`；`CHECK (revision > 0)`；`CHECK (review_status IN ('DRAFT','VERIFIED','REJECTED','STALE'))`。

## artifact_write_lease — 跨worker作品写入互斥

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `artifact_id` | `CHAR(36) NOT NULL` | 一份作品一个写租约 |
| `run_id` | `CHAR(36) NOT NULL` | 持有者运行 |
| `run_epoch` | `BIGINT NOT NULL` | 持有时epoch |
| `lease_token` | `CHAR(36) NOT NULL` | 不可猜测租约 |
| `base_revision` | `INT NOT NULL` | 获得时版本 |
| `lease_until` | `DATETIME(3) NOT NULL` | 截止UTC |

约束：`PRIMARY KEY (artifact_id)`；`FOREIGN KEY (artifact_id) REFERENCES learning_artifact(id)`；`FOREIGN KEY (run_id) REFERENCES agent_run(id)`；`CHECK (run_epoch > 0 AND base_revision >= 0)`；`INDEX ix_artifact_lease_expiry (lease_until)`。

## asset_object — 私有素材对象与安全检查

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 素材UUID |
| `workspace_id` | `CHAR(36) NOT NULL` | 所属空间 |
| `object_key` | `VARCHAR(255) NOT NULL` | 服务端生成存储key，非用户路径 |
| `mime_type` | `VARCHAR(128) NOT NULL` | 实测MIME |
| `byte_size` | `BIGINT NOT NULL` | 字节数 |
| `content_hash` | `CHAR(64) NOT NULL` | 文件SHA256 |
| `scan_status` | `VARCHAR(16) NOT NULL` | PENDING/CLEAN/REJECTED |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (id)`；`UNIQUE (object_key)`；`UNIQUE (workspace_id,id)`；`FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id)`；`CHECK (byte_size > 0)`；`CHECK (scan_status IN ('PENDING','CLEAN','REJECTED'))`。

## artifact_asset — 作品版本到素材映射

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `workspace_id` | `CHAR(36) NOT NULL` | 共同所属空间 |
| `artifact_id` | `CHAR(36) NOT NULL` | 作品 |
| `artifact_revision` | `INT NOT NULL` | 版本 |
| `asset_id` | `CHAR(36) NOT NULL` | 素材 |
| `usage_role` | `VARCHAR(32) NOT NULL` | 背景/音轨/缩略图等 |

约束：`PRIMARY KEY (artifact_id,artifact_revision,asset_id)`；`FOREIGN KEY (workspace_id,artifact_id) REFERENCES learning_artifact(workspace_id,id)`；`FOREIGN KEY (artifact_id,artifact_revision) REFERENCES artifact_revision(artifact_id,revision)`；`FOREIGN KEY (workspace_id,asset_id) REFERENCES asset_object(workspace_id,id)`。

## export_job — 固定作品版本的渲染导出

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 导出UUID |
| `artifact_id` | `CHAR(36) NOT NULL` | 作品 |
| `artifact_revision` | `INT NOT NULL` | 固定版本 |
| `format_key` | `VARCHAR(16) NOT NULL` | PPTX/DOCX/PDF/SVG/PNG/MP3/MP4/HTML |
| `renderer_version` | `VARCHAR(64) NOT NULL` | 渲染器版本 |
| `input_hash` | `CHAR(64) NOT NULL` | 输入含样式/字体/素材hash |
| `execution_key` | `VARCHAR(128) NOT NULL` | 导出去重键 |
| `status` | `VARCHAR(24) NOT NULL` | QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED |
| `output_asset_id` | `CHAR(36) NULL` | 成功后私有素材 |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (id)`；`UNIQUE (execution_key)`；`FOREIGN KEY (artifact_id,artifact_revision) REFERENCES artifact_revision(artifact_id,revision)`；`FOREIGN KEY (output_asset_id) REFERENCES asset_object(id)`；`CHECK (format_key IN ('PPTX','DOCX','PDF','MARKDOWN','SVG','PNG','MP3','WAV','MP4','WEBM','HTML'))`；`CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))`；`INDEX ix_export_queue (status,created_at)`。

## learner_memory — 有来源且可撤回的个性化记忆

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 记忆UUID |
| `workspace_id` | `CHAR(36) NOT NULL` | 同用户空间 |
| `kind` | `VARCHAR(32) NOT NULL` | 偏好/任务摘要/学习事实引用 |
| `basis` | `VARCHAR(16) NOT NULL` | OBSERVED/USER_STATED/INFERRED |
| `content_json` | `JSON NOT NULL` | 可展示可删除内容 |
| `support_refs` | `JSON NOT NULL` | 支持事件/来源 |
| `revision` | `INT NOT NULL DEFAULT 1` | 缓存失效版本 |
| `status` | `VARCHAR(16) NOT NULL` | ACTIVE/REVOKED/EXPIRED |
| `expires_at` | `DATETIME(3) NULL` | 可选TTL |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id)`；`CHECK (revision > 0)`；`CHECK (basis IN ('OBSERVED','USER_STATED','INFERRED'))`；`CHECK (status IN ('ACTIVE','REVOKED','EXPIRED'))`；`INDEX ix_memory_context (workspace_id,status,expires_at)`。

## notebook_entry — 错题作品中的可追溯学习块

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 块UUID |
| `artifact_id` | `CHAR(36) NOT NULL` | NOTEBOOK作品 |
| `artifact_revision` | `INT NOT NULL` | 不可变作品版本 |
| `attempt_id` | `CHAR(36) NOT NULL` | 真实原始作答 |
| `concept_id` | `CHAR(36) NOT NULL` | 核心误区概念 |
| `block_key` | `VARCHAR(64) NOT NULL` | DSL块key |
| `block_kind` | `VARCHAR(32) NOT NULL` | 六类受控块之一 |
| `content_json` | `JSON NOT NULL` | 受控交互DSL |

约束：`PRIMARY KEY (id)`；`UNIQUE (artifact_id,artifact_revision,block_key)`；`FOREIGN KEY (artifact_id,artifact_revision) REFERENCES artifact_revision(artifact_id,revision)`；`FOREIGN KEY (attempt_id) REFERENCES exercise_attempt(id)`；`FOREIGN KEY (concept_id) REFERENCES concept(id)`；`CHECK (block_kind IN ('ERROR_CONTEXT','TYPE_FLOW','REPAIR_DIFF','COUNTERFACTUAL','EXPERT_HELP','REVIEW_LINKS'))`。

## agent_verdict — 有标准和依据的质量门禁

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `id` | `CHAR(36) NOT NULL` | 核验UUID |
| `artifact_id` | `CHAR(36) NOT NULL` | 被核验作品 |
| `artifact_revision` | `INT NOT NULL` | 固定版本 |
| `reviewer_role` | `VARCHAR(64) NOT NULL` | 核验角色 |
| `gate_kind` | `VARCHAR(32) NOT NULL` | FACT/INTERACTION/LAYOUT/EXPORT |
| `decision` | `VARCHAR(16) NOT NULL` | PASS/FAIL/UNCERTAIN |
| `evidence_refs` | `JSON NOT NULL` | 规范/编译/行为证据 |
| `findings` | `JSON NOT NULL` | 结构化缺陷与位置 |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (artifact_id,artifact_revision) REFERENCES artifact_revision(artifact_id,revision)`；`CHECK (gate_kind IN ('FACT','INTERACTION','LAYOUT','EXPORT'))`；`CHECK (decision IN ('PASS','FAIL','UNCERTAIN'))`。

## artifact_dependency — 作品片段的派生与失效传播

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `workspace_id` | `CHAR(36) NOT NULL` | 共同空间 |
| `source_id` | `CHAR(36) NOT NULL` | 上游作品 |
| `source_revision` | `INT NOT NULL` | 上游版本 |
| `target_id` | `CHAR(36) NOT NULL` | 下游作品 |
| `target_revision` | `INT NOT NULL` | 下游版本 |
| `dependency_kind` | `VARCHAR(32) NOT NULL` | FACT/SCRIPT/LAYOUT/ASSET |
| `segment_refs` | `JSON NOT NULL` | 页/句/镜头/块级依赖 |

约束：`PRIMARY KEY (source_id,source_revision,target_id,target_revision,dependency_kind)`；`FOREIGN KEY (workspace_id,source_id) REFERENCES learning_artifact(workspace_id,id)`；`FOREIGN KEY (workspace_id,target_id) REFERENCES learning_artifact(workspace_id,id)`；`FOREIGN KEY (source_id,source_revision) REFERENCES artifact_revision(artifact_id,revision)`；`FOREIGN KEY (target_id,target_revision) REFERENCES artifact_revision(artifact_id,revision)`；`CHECK (source_id <> target_id)`；`CHECK (dependency_kind IN ('FACT','SCRIPT','LAYOUT','ASSET'))`。

## agent_run_event — 可重放的运行事件流

| 字段 | SQL 类型与约束 | 含义 |
|---|---|---|
| `run_id` | `CHAR(36) NOT NULL` | 所属运行 |
| `sequence_no` | `BIGINT NOT NULL` | 运行内单调序号 |
| `event_type` | `VARCHAR(64) NOT NULL` | 计划/步骤/作品/预算/状态变化 |
| `payload` | `JSON NOT NULL` | 允许展示的结构化事件，非隐藏推理 |
| `created_at` | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC |

约束：`PRIMARY KEY (run_id,sequence_no)`；`FOREIGN KEY (run_id) REFERENCES agent_run(id)`；`CHECK (sequence_no > 0)`。
