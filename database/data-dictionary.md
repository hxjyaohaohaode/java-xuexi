# 数据字典（与 SQL 同源生成）

所有时间为 UTC，主键为 UUID 字符串。下列 FK 为 RESTRICT 默认行为；账号删除需显式数据保留/清理事务，不能静默级联销毁历史。

## app_user — 用户身份与角色

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| username | `VARCHAR(64) NOT NULL` | 规范化小写登录名 |
| password_hash | `VARCHAR(255) NOT NULL` | 自适应散列；禁止明文 |
| roles | `JSON NOT NULL` | 服务端控制的角色列表 |
| status | `VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'` | ACTIVE或DISABLED |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`UNIQUE (username)`；`CHECK (status IN ('ACTIVE','DISABLED'))`

## user_preference — 用户界面偏好

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| user_id | `CHAR(36) NOT NULL` | 用户 |
| theme | `VARCHAR(8) NOT NULL DEFAULT 'system'` | 主题 |
| reduced_motion | `BOOLEAN NOT NULL DEFAULT FALSE` | 减少动效 |
| code_font_size | `INT NOT NULL DEFAULT 15` | 代码字号 |
| revision | `INT NOT NULL DEFAULT 1` | 乐观锁 |

约束：`PRIMARY KEY (user_id)`；`FOREIGN KEY (user_id) REFERENCES app_user (id)`；`CHECK (theme IN ('system','light','dark'))`；`CHECK (code_font_size BETWEEN 14 AND 24)`

## concept — 稳定概念标识

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| code | `VARCHAR(16) NOT NULL` | G01等教学编号 |
| title | `VARCHAR(128) NOT NULL` | 概念标题 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`UNIQUE (code)`

## concept_edge — 概念前置关系

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| concept_id | `CHAR(36) NOT NULL` | 后续概念 |
| prerequisite_id | `CHAR(36) NOT NULL` | 前置概念 |

约束：`PRIMARY KEY (concept_id, prerequisite_id)`；`FOREIGN KEY (concept_id) REFERENCES concept (id)`；`FOREIGN KEY (prerequisite_id) REFERENCES concept (id)`；`CHECK (concept_id <> prerequisite_id)`

## knowledge_source — 原始资料来源

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| title | `VARCHAR(255) NOT NULL` | 名称 |
| url | `VARCHAR(2048) NOT NULL` | 权威出处；导入须白名单 |
| edition | `VARCHAR(128) NOT NULL` | 来源版本 |
| usage_note | `VARCHAR(512) NOT NULL` | 许可与使用范围 |
| raw_hash | `CHAR(64) NULL` | 实际采集文件哈希；未采集为NULL |
| collected_at | `DATETIME(3) NULL` | 实际采集时间 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`

## knowledge_chunk — 不可变知识卡版本

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| revision | `INT NOT NULL` | 版本 |
| concept_id | `CHAR(36) NOT NULL` | 所属概念 |
| title | `VARCHAR(255) NOT NULL` | 标题 |
| body | `MEDIUMTEXT NOT NULL` | 受控Markdown |
| content_hash | `CHAR(64) NOT NULL` | 正文哈希 |
| status | `VARCHAR(16) NOT NULL DEFAULT 'DRAFT'` | 草稿审核状态 |
| author_id | `CHAR(36) NOT NULL` | 作者 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id, revision)`；`FOREIGN KEY (concept_id) REFERENCES concept (id)`；`FOREIGN KEY (author_id) REFERENCES app_user (id)`；`CHECK (revision > 0)`；`CHECK (status IN ('DRAFT','REVIEWED','RETIRED'))`；`INDEX ix_chunk_concept (concept_id,status)`

## chunk_source — 知识卡出处映射

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| chunk_id | `CHAR(36) NOT NULL` | 卡片 |
| chunk_revision | `INT NOT NULL` | 版本 |
| source_id | `CHAR(36) NOT NULL` | 来源 |
| section_ref | `VARCHAR(255) NOT NULL` | 章节定位 |

约束：`PRIMARY KEY (chunk_id,chunk_revision,source_id)`；`FOREIGN KEY (chunk_id,chunk_revision) REFERENCES knowledge_chunk (id,revision)`；`FOREIGN KEY (source_id) REFERENCES knowledge_source (id)`

## exercise — 不可变题目版本

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| revision | `INT NOT NULL` | 版本 |
| family_id | `CHAR(36) NOT NULL` | 题目家族防止重复抬分 |
| kind | `VARCHAR(32) NOT NULL` | 题型 |
| difficulty | `INT NOT NULL` | 1至3 |
| prompt | `MEDIUMTEXT NOT NULL` | 题干 |
| answer_spec | `JSON NOT NULL` | 仅后端可见的答案与隐藏测试 |
| rubric_version | `VARCHAR(32) NOT NULL` | 评分规则版本 |
| status | `VARCHAR(16) NOT NULL DEFAULT 'DRAFT'` | 审核状态 |
| author_id | `CHAR(36) NOT NULL` | 作者 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id,revision)`；`FOREIGN KEY (author_id) REFERENCES app_user (id)`；`CHECK (revision > 0)`；`CHECK (difficulty BETWEEN 1 AND 3)`；`CHECK (status IN ('DRAFT','REVIEWED','RETIRED'))`；`INDEX ix_exercise_family (family_id)`

## exercise_concept — 题目与概念权重

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| exercise_id | `CHAR(36) NOT NULL` | 题目 |
| exercise_revision | `INT NOT NULL` | 版本 |
| concept_id | `CHAR(36) NOT NULL` | 概念 |
| weight | `DECIMAL(5,4) NOT NULL` | 归因权重；同题合计1由应用验证 |

约束：`PRIMARY KEY (exercise_id,exercise_revision,concept_id)`；`FOREIGN KEY (exercise_id,exercise_revision) REFERENCES exercise (id,revision)`；`FOREIGN KEY (concept_id) REFERENCES concept (id)`；`CHECK (weight > 0 AND weight <= 1)`

## content_review — 独立复核记录

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| target_kind | `VARCHAR(16) NOT NULL` | CHUNK或EXERCISE |
| target_id | `CHAR(36) NOT NULL` | 多态目标；服务端检查存在性 |
| target_revision | `INT NOT NULL` | 目标版本 |
| author_id | `CHAR(36) NOT NULL` | 内容作者 |
| reviewer_id | `CHAR(36) NOT NULL` | 独立复核人 |
| decision | `VARCHAR(16) NOT NULL` | APPROVED或REJECTED |
| note | `TEXT NOT NULL` | 核查依据 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (author_id) REFERENCES app_user (id)`；`FOREIGN KEY (reviewer_id) REFERENCES app_user (id)`；`CHECK (author_id <> reviewer_id)`；`CHECK (target_kind IN ('CHUNK','EXERCISE'))`；`CHECK (decision IN ('APPROVED','REJECTED'))`；`INDEX ix_review_target (target_kind,target_id,target_revision)`

## content_release — 发布清单与版本

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| version | `VARCHAR(32) NOT NULL` | 显示版本 |
| status | `VARCHAR(16) NOT NULL DEFAULT 'DRAFT'` | 发布状态 |
| manifest_hash | `CHAR(64) NOT NULL` | 清单哈希 |
| created_by | `CHAR(36) NOT NULL` | 创建者 |
| published_at | `DATETIME(3) NULL` | 发布时间 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`UNIQUE (version)`；`FOREIGN KEY (created_by) REFERENCES app_user (id)`；`CHECK (status IN ('DRAFT','VALIDATING','PUBLISHED','RETIRED'))`

## release_chunk — 发布的知识卡集合

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| release_id | `CHAR(36) NOT NULL` | 发布 |
| chunk_id | `CHAR(36) NOT NULL` | 知识卡 |
| chunk_revision | `INT NOT NULL` | 知识版本 |

约束：`PRIMARY KEY (release_id,chunk_id,chunk_revision)`；`UNIQUE (release_id,chunk_id)`；`FOREIGN KEY (release_id) REFERENCES content_release (id)`；`FOREIGN KEY (chunk_id,chunk_revision) REFERENCES knowledge_chunk (id,revision)`

## release_exercise — 发布的题目集合

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| release_id | `CHAR(36) NOT NULL` | 发布 |
| exercise_id | `CHAR(36) NOT NULL` | 题目 |
| exercise_revision | `INT NOT NULL` | 版本 |

约束：`PRIMARY KEY (release_id,exercise_id,exercise_revision)`；`UNIQUE (release_id,exercise_id)`；`FOREIGN KEY (release_id) REFERENCES content_release (id)`；`FOREIGN KEY (exercise_id,exercise_revision) REFERENCES exercise (id,revision)`

## active_release — 当前发布指针

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| singleton_id | `INT NOT NULL` | 只允许1 |
| release_id | `CHAR(36) NOT NULL` | 当前发布 |
| revision | `INT NOT NULL` | CAS版本 |

约束：`PRIMARY KEY (singleton_id)`；`FOREIGN KEY (release_id) REFERENCES content_release (id)`；`CHECK (singleton_id=1)`

## index_snapshot — 版本化检索索引

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| release_id | `CHAR(36) NOT NULL` | 发布 |
| index_version | `VARCHAR(32) NOT NULL` | 构建器版本 |
| tokenizer_version | `VARCHAR(32) NOT NULL` | 切词版本 |
| content_hash | `CHAR(64) NOT NULL` | 索引数据哈希 |
| storage_key | `VARCHAR(255) NOT NULL` | 服务端固定根下的逻辑键 |
| status | `VARCHAR(16) NOT NULL` | BUILDING/READY/FAILED |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (release_id) REFERENCES content_release (id)`；`UNIQUE (release_id,index_version)`；`CHECK (status IN ('BUILDING','READY','FAILED'))`

## exercise_attempt — 不可覆盖的学习提交

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| user_id | `CHAR(36) NOT NULL` | 所属用户 |
| release_id | `CHAR(36) NOT NULL` | 固定发布 |
| exercise_id | `CHAR(36) NOT NULL` | 题目 |
| exercise_revision | `INT NOT NULL` | 题目版本 |
| answer | `JSON NOT NULL` | 答案或代码 |
| status | `VARCHAR(16) NOT NULL` | PENDING/GRADING/GRADED/FAILED |
| hint_level | `INT NOT NULL DEFAULT 0` | 提示级别0至4 |
| score | `DECIMAL(6,2) NULL` | 分数 |
| maximum | `DECIMAL(6,2) NULL` | 满分 |
| grader_version | `VARCHAR(32) NULL` | 判分器版本 |
| feedback | `JSON NULL` | 解释和误区标签 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (user_id) REFERENCES app_user (id)`；`FOREIGN KEY (release_id,exercise_id,exercise_revision) REFERENCES release_exercise (release_id,exercise_id,exercise_revision)`；`CHECK (hint_level BETWEEN 0 AND 4)`；`CHECK (status IN ('PENDING','GRADING','GRADED','FAILED'))`；`CHECK (score IS NULL OR (maximum IS NOT NULL AND maximum > 0 AND score >= 0 AND score <= maximum))`；`INDEX ix_attempt_user_time (user_id,created_at,id)`

## compile_job — 编译执行队列与租约

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| user_id | `CHAR(36) NOT NULL` | 所属用户 |
| release_id | `CHAR(36) NOT NULL` | 发布 |
| attempt_id | `CHAR(36) NULL` | 可选关联提交 |
| retry_of | `CHAR(36) NULL` | 手动重试父作业 |
| mode | `VARCHAR(24) NOT NULL` | 编译或编译运行 |
| source_code | `TEXT NOT NULL` | 私有源码，清理策略见说明 |
| source_hash | `CHAR(64) NOT NULL` | 源码SHA256 |
| status | `VARCHAR(24) NOT NULL` | 执行状态 |
| lease_token | `CHAR(36) NULL` | 每次领取的新令牌 |
| lease_until | `DATETIME(3) NULL` | 租约到期时间 |
| attempt_no | `INT NOT NULL DEFAULT 0` | 领取次数 |
| jdk_version | `VARCHAR(64) NULL` | 实际JDK |
| compiler_flags | `JSON NULL` | 固定flags |
| result | `JSON NULL` | 结构化诊断与受限输出 |
| finished_at | `DATETIME(3) NULL` | 结束时间 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (user_id) REFERENCES app_user (id)`；`FOREIGN KEY (release_id) REFERENCES content_release (id)`；`FOREIGN KEY (attempt_id) REFERENCES exercise_attempt (id)`；`FOREIGN KEY (retry_of) REFERENCES compile_job (id)`；`CHECK (mode IN ('COMPILE_ONLY','COMPILE_AND_RUN'))`；`CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','COMPILE_ERROR','RUNTIME_ERROR','TIMED_OUT','RESOURCE_LIMIT','INFRA_ERROR','CANCELLED'))`；`INDEX ix_job_claim (status,lease_until,created_at,id)`；`INDEX ix_job_user (user_id,created_at,id)`

## lab_session — 类型实验快照

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| user_id | `CHAR(36) NOT NULL` | 实验归属 |
| release_id | `CHAR(36) NOT NULL` | 发布 |
| lab_type | `VARCHAR(32) NOT NULL` | 八类实验之一 |
| input | `JSON NOT NULL` | 受限DSL与预测 |
| rules_version | `VARCHAR(32) NOT NULL` | 规则引擎版本 |
| evaluation | `JSON NULL` | 判定及解释 |
| job_id | `CHAR(36) NULL` | 真实编译核验 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (user_id) REFERENCES app_user (id)`；`FOREIGN KEY (release_id) REFERENCES content_release (id)`；`FOREIGN KEY (job_id) REFERENCES compile_job (id)`；`INDEX ix_lab_user (user_id,created_at,id)`

## learning_event — 学习事实事件

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| user_id | `CHAR(36) NOT NULL` | 所属用户 |
| release_id | `CHAR(36) NOT NULL` | 发布 |
| concept_id | `CHAR(36) NOT NULL` | 概念 |
| attempt_id | `CHAR(36) NULL` | 服务端评分时关联 |
| event_type | `VARCHAR(32) NOT NULL` | 阅读或服务端评分事件 |
| payload | `JSON NOT NULL` | 版本化事件载荷 |
| occurred_at | `DATETIME(3) NOT NULL` | 发生时间 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (user_id) REFERENCES app_user (id)`；`FOREIGN KEY (release_id) REFERENCES content_release (id)`；`FOREIGN KEY (concept_id) REFERENCES concept (id)`；`FOREIGN KEY (attempt_id) REFERENCES exercise_attempt (id)`；`INDEX ix_event_user_concept (user_id,concept_id,occurred_at,id)`

## learner_concept — 可重建学习投影

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| user_id | `CHAR(36) NOT NULL` | 用户 |
| concept_id | `CHAR(36) NOT NULL` | 概念 |
| independent_samples | `INT NOT NULL DEFAULT 0` | 不同题目家族独立样本数 |
| weighted_correct | `DECIMAL(10,4) NOT NULL DEFAULT 0` | 加权正确分子 |
| weighted_total | `DECIMAL(10,4) NOT NULL DEFAULT 0` | 加权分母 |
| algorithm_version | `VARCHAR(32) NOT NULL` | 规则版本 |
| revision | `INT NOT NULL DEFAULT 1` | 并发版本 |
| updated_at | `DATETIME(3) NOT NULL` | 投影更新时间 |

约束：`PRIMARY KEY (user_id,concept_id)`；`FOREIGN KEY (user_id) REFERENCES app_user (id)`；`FOREIGN KEY (concept_id) REFERENCES concept (id)`；`CHECK (weighted_correct >= 0 AND weighted_total >= weighted_correct)`；`CHECK (independent_samples >= 0)`

## review_item — 个性化复练建议

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| user_id | `CHAR(36) NOT NULL` | 用户 |
| concept_id | `CHAR(36) NOT NULL` | 目标概念 |
| origin_attempt_id | `CHAR(36) NOT NULL` | 建议依据 |
| due_at | `DATETIME(3) NOT NULL` | 建议复练时间 |
| reason | `VARCHAR(512) NOT NULL` | 可解释原因 |
| status | `VARCHAR(16) NOT NULL` | PENDING/DONE/DISMISSED |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (user_id) REFERENCES app_user (id)`；`FOREIGN KEY (concept_id) REFERENCES concept (id)`；`FOREIGN KEY (origin_attempt_id) REFERENCES exercise_attempt (id)`；`UNIQUE (user_id,concept_id,origin_attempt_id)`；`CHECK (status IN ('PENDING','DONE','DISMISSED'))`；`INDEX ix_review_due (user_id,status,due_at,id)`

## outbox_event — 可靠事件待投递表

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| aggregate_id | `CHAR(36) NOT NULL` | 聚合标识 |
| event_type | `VARCHAR(64) NOT NULL` | 版本化事件类型 |
| payload | `JSON NOT NULL` | 载荷 |
| published_at | `DATETIME(3) NULL` | 投递标记；重复可重放 |
| attempts | `INT NOT NULL DEFAULT 0` | 重试次数 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`INDEX ix_outbox_pending (published_at,created_at,id)`

## event_receipt — 消费者去重凭据

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| consumer | `VARCHAR(64) NOT NULL` | 消费者名称 |
| event_id | `CHAR(36) NOT NULL` | 事件 |
| handled_at | `DATETIME(3) NOT NULL` | 完成时间 |

约束：`PRIMARY KEY (consumer,event_id)`；`FOREIGN KEY (event_id) REFERENCES outbox_event (id)`

## conversation — 固定发布的私人会话

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| user_id | `CHAR(36) NOT NULL` | 所属用户 |
| release_id | `CHAR(36) NOT NULL` | 发布 |
| title | `VARCHAR(128) NOT NULL` | 标题 |
| summary | `TEXT NULL` | 只保存事实的上下文摘要 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (user_id) REFERENCES app_user (id)`；`FOREIGN KEY (release_id) REFERENCES content_release (id)`；`INDEX ix_conversation_user (user_id,created_at,id)`

## tutor_request — 导师异步请求

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| conversation_id | `CHAR(36) NOT NULL` | 会话 |
| attempt_id | `CHAR(36) NULL` | 可选学习提交 |
| question | `TEXT NOT NULL` | 问题 |
| hint_level | `INT NOT NULL DEFAULT 0` | 提示级别 |
| status | `VARCHAR(32) NOT NULL` | 流程状态 |
| prompt_version | `VARCHAR(32) NOT NULL` | 提示版本 |
| model_id | `VARCHAR(128) NULL` | 实际模型 |
| usage_metrics | `JSON NULL` | tokens/消耗/延迟 |
| error_code | `VARCHAR(64) NULL` | 受控错误 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (conversation_id) REFERENCES conversation (id)`；`FOREIGN KEY (attempt_id) REFERENCES exercise_attempt (id)`；`CHECK (hint_level BETWEEN 0 AND 4)`；`CHECK (status IN ('QUEUED','RETRIEVING','GENERATING','VALIDATING','COMPLETED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED'))`

## tutor_message — 已校验的导师输出

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| request_id | `CHAR(36) NOT NULL` | 对应请求 |
| answer | `MEDIUMTEXT NOT NULL` | 可见回答 |
| limitations | `TEXT NOT NULL` | 证据与能力限制 |
| suggestions | `JSON NOT NULL` | 同发布的练习建议 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`UNIQUE (request_id)`；`FOREIGN KEY (request_id) REFERENCES tutor_request (id)`

## message_citation — 回答证据引用

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| message_id | `CHAR(36) NOT NULL` | 回答 |
| release_id | `CHAR(36) NOT NULL` | 发布 |
| chunk_id | `CHAR(36) NOT NULL` | 卡片 |
| chunk_revision | `INT NOT NULL` | 版本 |
| locator | `VARCHAR(255) NOT NULL` | 片段位置 |

约束：`PRIMARY KEY (message_id,chunk_id,chunk_revision)`；`FOREIGN KEY (message_id) REFERENCES tutor_message (id)`；`FOREIGN KEY (release_id,chunk_id,chunk_revision) REFERENCES release_chunk (release_id,chunk_id,chunk_revision)`

## retrieval_log — 检索诊断记录

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| request_id | `CHAR(36) NULL` | 导师请求；独立搜索可为空 |
| user_id | `CHAR(36) NOT NULL` | 请求者 |
| release_id | `CHAR(36) NOT NULL` | 发布 |
| query_hash | `CHAR(64) NOT NULL` | 查询哈希，避免无必要保存全文 |
| index_id | `CHAR(36) NOT NULL` | 索引版本 |
| result_refs | `JSON NOT NULL` | 引用ID及排序，不存私有正文 |
| latency_ms | `INT NOT NULL` | 检索耗时 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (request_id) REFERENCES tutor_request (id)`；`FOREIGN KEY (user_id) REFERENCES app_user (id)`；`FOREIGN KEY (release_id) REFERENCES content_release (id)`；`FOREIGN KEY (index_id) REFERENCES index_snapshot (id)`；`CHECK (latency_ms >= 0)`

## audit_event — 最小化审计记录

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| id | `CHAR(36) NOT NULL` | UUID主键 |
| actor_id | `CHAR(36) NULL` | 匿名/系统可为空 |
| action | `VARCHAR(64) NOT NULL` | 动作 |
| resource_id | `CHAR(36) NULL` | 目标 |
| trace_id | `VARCHAR(64) NOT NULL` | 追踪标识 |
| safe_metadata | `JSON NOT NULL` | 脱敏摘要 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (id)`；`FOREIGN KEY (actor_id) REFERENCES app_user (id)`；`INDEX ix_audit_time (created_at,id)`

## idempotency_record — 写操作幂等控制

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| user_id | `CHAR(36) NOT NULL` | 当前用户 |
| operation | `VARCHAR(64) NOT NULL` | 接口操作 |
| idempotency_key | `VARCHAR(64) NOT NULL` | 调用者提供的随机key |
| request_hash | `CHAR(64) NOT NULL` | 规范化输入哈希 |
| resource_id | `CHAR(36) NOT NULL` | 稳定结果资源 |
| expires_at | `DATETIME(3) NOT NULL` | 24小时重放窗口 |
| created_at | `DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | UTC创建时间 |

约束：`PRIMARY KEY (user_id,operation,idempotency_key)`；`FOREIGN KEY (user_id) REFERENCES app_user (id)`；`INDEX ix_idempotency_expiry (expires_at)`

## release_concept — 发布中的概念图快照

| 字段 | SQL 类型/空值/默认 | 说明 |
|---|---|---|
| release_id | `CHAR(36) NOT NULL` | 固定发布 |
| concept_id | `CHAR(36) NOT NULL` | 稳定概念标识 |
| title | `VARCHAR(128) NOT NULL` | 发布时名称 |
| prerequisite_ids | `JSON NOT NULL` | 同发布前置ID；发布时校验存在与无环 |

约束：`PRIMARY KEY (release_id,concept_id)`；`FOREIGN KEY (release_id) REFERENCES content_release (id)`；`FOREIGN KEY (concept_id) REFERENCES concept (id)`
