-- TypeForge architecture baseline. MySQL 8.4. Apply once to an EMPTY dedicated database.
-- No DROP/DELETE statements. UTC is an application/session invariant.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 用户身份与角色
CREATE TABLE app_user (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  username VARCHAR(64) NOT NULL COMMENT '规范化小写登录名',
  password_hash VARCHAR(255) NOT NULL COMMENT '自适应散列；禁止明文',
  roles JSON NOT NULL COMMENT '服务端控制的角色列表',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE或DISABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  UNIQUE (username),
  CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='用户身份与角色';

-- 用户界面偏好
CREATE TABLE user_preference (
  user_id CHAR(36) NOT NULL COMMENT '用户',
  theme VARCHAR(8) NOT NULL DEFAULT 'system' COMMENT '主题',
  reduced_motion BOOLEAN NOT NULL DEFAULT FALSE COMMENT '减少动效',
  code_font_size INT NOT NULL DEFAULT 15 COMMENT '代码字号',
  revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁',
  PRIMARY KEY (user_id),
  FOREIGN KEY (user_id) REFERENCES app_user (id),
  CHECK (theme IN ('system','light','dark')),
  CHECK (code_font_size BETWEEN 14 AND 24)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='用户界面偏好';

-- 稳定概念标识
CREATE TABLE concept (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  code VARCHAR(16) NOT NULL COMMENT 'G01等教学编号',
  title VARCHAR(128) NOT NULL COMMENT '概念标题',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='稳定概念标识';

-- 概念前置关系
CREATE TABLE concept_edge (
  concept_id CHAR(36) NOT NULL COMMENT '后续概念',
  prerequisite_id CHAR(36) NOT NULL COMMENT '前置概念',
  PRIMARY KEY (concept_id, prerequisite_id),
  FOREIGN KEY (concept_id) REFERENCES concept (id),
  FOREIGN KEY (prerequisite_id) REFERENCES concept (id),
  CHECK (concept_id <> prerequisite_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='概念前置关系';

-- 原始资料来源
CREATE TABLE knowledge_source (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  title VARCHAR(255) NOT NULL COMMENT '名称',
  url VARCHAR(2048) NOT NULL COMMENT '权威出处；导入须白名单',
  edition VARCHAR(128) NOT NULL COMMENT '来源版本',
  usage_note VARCHAR(512) NOT NULL COMMENT '许可与使用范围',
  raw_hash CHAR(64) NULL COMMENT '实际采集文件哈希；未采集为NULL',
  collected_at DATETIME(3) NULL COMMENT '实际采集时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='原始资料来源';

-- 不可变知识卡版本
CREATE TABLE knowledge_chunk (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  revision INT NOT NULL COMMENT '版本',
  concept_id CHAR(36) NOT NULL COMMENT '所属概念',
  title VARCHAR(255) NOT NULL COMMENT '标题',
  body MEDIUMTEXT NOT NULL COMMENT '受控Markdown',
  content_hash CHAR(64) NOT NULL COMMENT '正文哈希',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '草稿审核状态',
  author_id CHAR(36) NOT NULL COMMENT '作者',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id, revision),
  FOREIGN KEY (concept_id) REFERENCES concept (id),
  FOREIGN KEY (author_id) REFERENCES app_user (id),
  CHECK (revision > 0),
  CHECK (status IN ('DRAFT','REVIEWED','RETIRED')),
  INDEX ix_chunk_concept (concept_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不可变知识卡版本';

-- 知识卡出处映射
CREATE TABLE chunk_source (
  chunk_id CHAR(36) NOT NULL COMMENT '卡片',
  chunk_revision INT NOT NULL COMMENT '版本',
  source_id CHAR(36) NOT NULL COMMENT '来源',
  section_ref VARCHAR(255) NOT NULL COMMENT '章节定位',
  PRIMARY KEY (chunk_id,chunk_revision,source_id),
  FOREIGN KEY (chunk_id,chunk_revision) REFERENCES knowledge_chunk (id,revision),
  FOREIGN KEY (source_id) REFERENCES knowledge_source (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='知识卡出处映射';

-- 不可变题目版本
CREATE TABLE exercise (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  revision INT NOT NULL COMMENT '版本',
  family_id CHAR(36) NOT NULL COMMENT '题目家族防止重复抬分',
  kind VARCHAR(32) NOT NULL COMMENT '题型',
  difficulty INT NOT NULL COMMENT '1至3',
  prompt MEDIUMTEXT NOT NULL COMMENT '题干',
  answer_spec JSON NOT NULL COMMENT '仅后端可见的答案与隐藏测试',
  rubric_version VARCHAR(32) NOT NULL COMMENT '评分规则版本',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '审核状态',
  author_id CHAR(36) NOT NULL COMMENT '作者',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id,revision),
  FOREIGN KEY (author_id) REFERENCES app_user (id),
  CHECK (revision > 0),
  CHECK (difficulty BETWEEN 1 AND 3),
  CHECK (status IN ('DRAFT','REVIEWED','RETIRED')),
  INDEX ix_exercise_family (family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不可变题目版本';

-- 题目与概念权重
CREATE TABLE exercise_concept (
  exercise_id CHAR(36) NOT NULL COMMENT '题目',
  exercise_revision INT NOT NULL COMMENT '版本',
  concept_id CHAR(36) NOT NULL COMMENT '概念',
  weight DECIMAL(5,4) NOT NULL COMMENT '归因权重；同题合计1由应用验证',
  PRIMARY KEY (exercise_id,exercise_revision,concept_id),
  FOREIGN KEY (exercise_id,exercise_revision) REFERENCES exercise (id,revision),
  FOREIGN KEY (concept_id) REFERENCES concept (id),
  CHECK (weight > 0 AND weight <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='题目与概念权重';

-- 独立复核记录
CREATE TABLE content_review (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  target_kind VARCHAR(16) NOT NULL COMMENT 'CHUNK或EXERCISE',
  target_id CHAR(36) NOT NULL COMMENT '多态目标；服务端检查存在性',
  target_revision INT NOT NULL COMMENT '目标版本',
  author_id CHAR(36) NOT NULL COMMENT '内容作者',
  reviewer_id CHAR(36) NOT NULL COMMENT '独立复核人',
  decision VARCHAR(16) NOT NULL COMMENT 'APPROVED或REJECTED',
  note TEXT NOT NULL COMMENT '核查依据',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  FOREIGN KEY (author_id) REFERENCES app_user (id),
  FOREIGN KEY (reviewer_id) REFERENCES app_user (id),
  CHECK (author_id <> reviewer_id),
  CHECK (target_kind IN ('CHUNK','EXERCISE')),
  CHECK (decision IN ('APPROVED','REJECTED')),
  INDEX ix_review_target (target_kind,target_id,target_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='独立复核记录';

-- 发布清单与版本
CREATE TABLE content_release (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  version VARCHAR(32) NOT NULL COMMENT '显示版本',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '发布状态',
  manifest_hash CHAR(64) NOT NULL COMMENT '清单哈希',
  created_by CHAR(36) NOT NULL COMMENT '创建者',
  published_at DATETIME(3) NULL COMMENT '发布时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  UNIQUE (version),
  FOREIGN KEY (created_by) REFERENCES app_user (id),
  CHECK (status IN ('DRAFT','VALIDATING','PUBLISHED','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='发布清单与版本';

-- 发布的知识卡集合
CREATE TABLE release_chunk (
  release_id CHAR(36) NOT NULL COMMENT '发布',
  chunk_id CHAR(36) NOT NULL COMMENT '知识卡',
  chunk_revision INT NOT NULL COMMENT '知识版本',
  PRIMARY KEY (release_id,chunk_id,chunk_revision),
  UNIQUE (release_id,chunk_id),
  FOREIGN KEY (release_id) REFERENCES content_release (id),
  FOREIGN KEY (chunk_id,chunk_revision) REFERENCES knowledge_chunk (id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='发布的知识卡集合';

-- 发布的题目集合
CREATE TABLE release_exercise (
  release_id CHAR(36) NOT NULL COMMENT '发布',
  exercise_id CHAR(36) NOT NULL COMMENT '题目',
  exercise_revision INT NOT NULL COMMENT '版本',
  PRIMARY KEY (release_id,exercise_id,exercise_revision),
  UNIQUE (release_id,exercise_id),
  FOREIGN KEY (release_id) REFERENCES content_release (id),
  FOREIGN KEY (exercise_id,exercise_revision) REFERENCES exercise (id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='发布的题目集合';

-- 当前发布指针
CREATE TABLE active_release (
  singleton_id INT NOT NULL COMMENT '只允许1',
  release_id CHAR(36) NOT NULL COMMENT '当前发布',
  revision INT NOT NULL COMMENT 'CAS版本',
  PRIMARY KEY (singleton_id),
  FOREIGN KEY (release_id) REFERENCES content_release (id),
  CHECK (singleton_id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='当前发布指针';

-- 版本化检索索引
CREATE TABLE index_snapshot (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  release_id CHAR(36) NOT NULL COMMENT '发布',
  index_version VARCHAR(32) NOT NULL COMMENT '构建器版本',
  tokenizer_version VARCHAR(32) NOT NULL COMMENT '切词版本',
  content_hash CHAR(64) NOT NULL COMMENT '索引数据哈希',
  storage_key VARCHAR(255) NOT NULL COMMENT '服务端固定根下的逻辑键',
  status VARCHAR(16) NOT NULL COMMENT 'BUILDING/READY/FAILED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  FOREIGN KEY (release_id) REFERENCES content_release (id),
  UNIQUE (release_id,index_version),
  CHECK (status IN ('BUILDING','READY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='版本化检索索引';

-- 不可覆盖的学习提交
CREATE TABLE exercise_attempt (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  user_id CHAR(36) NOT NULL COMMENT '所属用户',
  release_id CHAR(36) NOT NULL COMMENT '固定发布',
  exercise_id CHAR(36) NOT NULL COMMENT '题目',
  exercise_revision INT NOT NULL COMMENT '题目版本',
  answer JSON NOT NULL COMMENT '答案或代码',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING/GRADING/GRADED/FAILED',
  hint_level INT NOT NULL DEFAULT 0 COMMENT '提示级别0至4',
  score DECIMAL(6,2) NULL COMMENT '分数',
  maximum DECIMAL(6,2) NULL COMMENT '满分',
  grader_version VARCHAR(32) NULL COMMENT '判分器版本',
  feedback JSON NULL COMMENT '解释和误区标签',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  FOREIGN KEY (user_id) REFERENCES app_user (id),
  FOREIGN KEY (release_id,exercise_id,exercise_revision) REFERENCES release_exercise (release_id,exercise_id,exercise_revision),
  CHECK (hint_level BETWEEN 0 AND 4),
  CHECK (status IN ('PENDING','GRADING','GRADED','FAILED')),
  CHECK (score IS NULL OR (maximum IS NOT NULL AND maximum > 0 AND score >= 0 AND score <= maximum)),
  INDEX ix_attempt_user_time (user_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不可覆盖的学习提交';

-- 编译执行队列与租约
CREATE TABLE compile_job (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  user_id CHAR(36) NOT NULL COMMENT '所属用户',
  release_id CHAR(36) NOT NULL COMMENT '发布',
  attempt_id CHAR(36) NULL COMMENT '可选关联提交',
  retry_of CHAR(36) NULL COMMENT '手动重试父作业',
  mode VARCHAR(24) NOT NULL COMMENT '编译或编译运行',
  source_code TEXT NOT NULL COMMENT '私有源码，清理策略见说明',
  source_hash CHAR(64) NOT NULL COMMENT '源码SHA256',
  status VARCHAR(24) NOT NULL COMMENT '执行状态',
  lease_token CHAR(36) NULL COMMENT '每次领取的新令牌',
  lease_until DATETIME(3) NULL COMMENT '租约到期时间',
  attempt_no INT NOT NULL DEFAULT 0 COMMENT '领取次数',
  jdk_version VARCHAR(64) NULL COMMENT '实际JDK',
  compiler_flags JSON NULL COMMENT '固定flags',
  result JSON NULL COMMENT '结构化诊断与受限输出',
  finished_at DATETIME(3) NULL COMMENT '结束时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  FOREIGN KEY (user_id) REFERENCES app_user (id),
  FOREIGN KEY (release_id) REFERENCES content_release (id),
  FOREIGN KEY (attempt_id) REFERENCES exercise_attempt (id),
  FOREIGN KEY (retry_of) REFERENCES compile_job (id),
  CHECK (mode IN ('COMPILE_ONLY','COMPILE_AND_RUN')),
  CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','COMPILE_ERROR','RUNTIME_ERROR','TIMED_OUT','RESOURCE_LIMIT','INFRA_ERROR','CANCELLED')),
  INDEX ix_job_claim (status,lease_until,created_at,id),
  INDEX ix_job_user (user_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='编译执行队列与租约';

-- 类型实验快照
CREATE TABLE lab_session (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  user_id CHAR(36) NOT NULL COMMENT '实验归属',
  release_id CHAR(36) NOT NULL COMMENT '发布',
  lab_type VARCHAR(32) NOT NULL COMMENT '八类实验之一',
  input JSON NOT NULL COMMENT '受限DSL与预测',
  rules_version VARCHAR(32) NOT NULL COMMENT '规则引擎版本',
  evaluation JSON NULL COMMENT '判定及解释',
  job_id CHAR(36) NULL COMMENT '真实编译核验',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  FOREIGN KEY (user_id) REFERENCES app_user (id),
  FOREIGN KEY (release_id) REFERENCES content_release (id),
  FOREIGN KEY (job_id) REFERENCES compile_job (id),
  INDEX ix_lab_user (user_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='类型实验快照';

-- 学习事实事件
CREATE TABLE learning_event (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  user_id CHAR(36) NOT NULL COMMENT '所属用户',
  release_id CHAR(36) NOT NULL COMMENT '发布',
  concept_id CHAR(36) NOT NULL COMMENT '概念',
  attempt_id CHAR(36) NULL COMMENT '服务端评分时关联',
  event_type VARCHAR(32) NOT NULL COMMENT '阅读或服务端评分事件',
  payload JSON NOT NULL COMMENT '版本化事件载荷',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  FOREIGN KEY (user_id) REFERENCES app_user (id),
  FOREIGN KEY (release_id) REFERENCES content_release (id),
  FOREIGN KEY (concept_id) REFERENCES concept (id),
  FOREIGN KEY (attempt_id) REFERENCES exercise_attempt (id),
  INDEX ix_event_user_concept (user_id,concept_id,occurred_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='学习事实事件';

-- 可重建学习投影
CREATE TABLE learner_concept (
  user_id CHAR(36) NOT NULL COMMENT '用户',
  concept_id CHAR(36) NOT NULL COMMENT '概念',
  independent_samples INT NOT NULL DEFAULT 0 COMMENT '不同题目家族独立样本数',
  weighted_correct DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '加权正确分子',
  weighted_total DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '加权分母',
  algorithm_version VARCHAR(32) NOT NULL COMMENT '规则版本',
  revision INT NOT NULL DEFAULT 1 COMMENT '并发版本',
  updated_at DATETIME(3) NOT NULL COMMENT '投影更新时间',
  PRIMARY KEY (user_id,concept_id),
  FOREIGN KEY (user_id) REFERENCES app_user (id),
  FOREIGN KEY (concept_id) REFERENCES concept (id),
  CHECK (weighted_correct >= 0 AND weighted_total >= weighted_correct),
  CHECK (independent_samples >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='可重建学习投影';

-- 个性化复练建议
CREATE TABLE review_item (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  user_id CHAR(36) NOT NULL COMMENT '用户',
  concept_id CHAR(36) NOT NULL COMMENT '目标概念',
  origin_attempt_id CHAR(36) NOT NULL COMMENT '建议依据',
  due_at DATETIME(3) NOT NULL COMMENT '建议复练时间',
  reason VARCHAR(512) NOT NULL COMMENT '可解释原因',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING/DONE/DISMISSED',
  PRIMARY KEY (id),
  FOREIGN KEY (user_id) REFERENCES app_user (id),
  FOREIGN KEY (concept_id) REFERENCES concept (id),
  FOREIGN KEY (origin_attempt_id) REFERENCES exercise_attempt (id),
  UNIQUE (user_id,concept_id,origin_attempt_id),
  CHECK (status IN ('PENDING','DONE','DISMISSED')),
  INDEX ix_review_due (user_id,status,due_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='个性化复练建议';

-- 可靠事件待投递表
CREATE TABLE outbox_event (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  aggregate_id CHAR(36) NOT NULL COMMENT '聚合标识',
  event_type VARCHAR(64) NOT NULL COMMENT '版本化事件类型',
  payload JSON NOT NULL COMMENT '载荷',
  published_at DATETIME(3) NULL COMMENT '投递标记；重复可重放',
  attempts INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  INDEX ix_outbox_pending (published_at,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='可靠事件待投递表';

-- 消费者去重凭据
CREATE TABLE event_receipt (
  consumer VARCHAR(64) NOT NULL COMMENT '消费者名称',
  event_id CHAR(36) NOT NULL COMMENT '事件',
  handled_at DATETIME(3) NOT NULL COMMENT '完成时间',
  PRIMARY KEY (consumer,event_id),
  FOREIGN KEY (event_id) REFERENCES outbox_event (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='消费者去重凭据';

-- 固定发布的私人会话
CREATE TABLE conversation (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  user_id CHAR(36) NOT NULL COMMENT '所属用户',
  release_id CHAR(36) NOT NULL COMMENT '发布',
  title VARCHAR(128) NOT NULL COMMENT '标题',
  summary TEXT NULL COMMENT '只保存事实的上下文摘要',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  FOREIGN KEY (user_id) REFERENCES app_user (id),
  FOREIGN KEY (release_id) REFERENCES content_release (id),
  INDEX ix_conversation_user (user_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='固定发布的私人会话';

-- 导师异步请求
CREATE TABLE tutor_request (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  conversation_id CHAR(36) NOT NULL COMMENT '会话',
  attempt_id CHAR(36) NULL COMMENT '可选学习提交',
  question TEXT NOT NULL COMMENT '问题',
  hint_level INT NOT NULL DEFAULT 0 COMMENT '提示级别',
  status VARCHAR(32) NOT NULL COMMENT '流程状态',
  prompt_version VARCHAR(32) NOT NULL COMMENT '提示版本',
  model_id VARCHAR(128) NULL COMMENT '实际模型',
  usage_metrics JSON NULL COMMENT 'tokens/消耗/延迟',
  error_code VARCHAR(64) NULL COMMENT '受控错误',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  FOREIGN KEY (conversation_id) REFERENCES conversation (id),
  FOREIGN KEY (attempt_id) REFERENCES exercise_attempt (id),
  CHECK (hint_level BETWEEN 0 AND 4),
  CHECK (status IN ('QUEUED','RETRIEVING','GENERATING','VALIDATING','COMPLETED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='导师异步请求';

-- 已校验的导师输出
CREATE TABLE tutor_message (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  request_id CHAR(36) NOT NULL COMMENT '对应请求',
  answer MEDIUMTEXT NOT NULL COMMENT '可见回答',
  limitations TEXT NOT NULL COMMENT '证据与能力限制',
  suggestions JSON NOT NULL COMMENT '同发布的练习建议',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  UNIQUE (request_id),
  FOREIGN KEY (request_id) REFERENCES tutor_request (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='已校验的导师输出';

-- 回答证据引用
CREATE TABLE message_citation (
  message_id CHAR(36) NOT NULL COMMENT '回答',
  release_id CHAR(36) NOT NULL COMMENT '发布',
  chunk_id CHAR(36) NOT NULL COMMENT '卡片',
  chunk_revision INT NOT NULL COMMENT '版本',
  locator VARCHAR(255) NOT NULL COMMENT '片段位置',
  PRIMARY KEY (message_id,chunk_id,chunk_revision),
  FOREIGN KEY (message_id) REFERENCES tutor_message (id),
  FOREIGN KEY (release_id,chunk_id,chunk_revision) REFERENCES release_chunk (release_id,chunk_id,chunk_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='回答证据引用';

-- 检索诊断记录
CREATE TABLE retrieval_log (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  request_id CHAR(36) NULL COMMENT '导师请求；独立搜索可为空',
  user_id CHAR(36) NOT NULL COMMENT '请求者',
  release_id CHAR(36) NOT NULL COMMENT '发布',
  query_hash CHAR(64) NOT NULL COMMENT '查询哈希，避免无必要保存全文',
  index_id CHAR(36) NOT NULL COMMENT '索引版本',
  result_refs JSON NOT NULL COMMENT '引用ID及排序，不存私有正文',
  latency_ms INT NOT NULL COMMENT '检索耗时',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  FOREIGN KEY (request_id) REFERENCES tutor_request (id),
  FOREIGN KEY (user_id) REFERENCES app_user (id),
  FOREIGN KEY (release_id) REFERENCES content_release (id),
  FOREIGN KEY (index_id) REFERENCES index_snapshot (id),
  CHECK (latency_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='检索诊断记录';

-- 最小化审计记录
CREATE TABLE audit_event (
  id CHAR(36) NOT NULL COMMENT 'UUID主键',
  actor_id CHAR(36) NULL COMMENT '匿名/系统可为空',
  action VARCHAR(64) NOT NULL COMMENT '动作',
  resource_id CHAR(36) NULL COMMENT '目标',
  trace_id VARCHAR(64) NOT NULL COMMENT '追踪标识',
  safe_metadata JSON NOT NULL COMMENT '脱敏摘要',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (id),
  FOREIGN KEY (actor_id) REFERENCES app_user (id),
  INDEX ix_audit_time (created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='最小化审计记录';

-- 写操作幂等控制
CREATE TABLE idempotency_record (
  user_id CHAR(36) NOT NULL COMMENT '当前用户',
  operation VARCHAR(64) NOT NULL COMMENT '接口操作',
  idempotency_key VARCHAR(64) NOT NULL COMMENT '调用者提供的随机key',
  request_hash CHAR(64) NOT NULL COMMENT '规范化输入哈希',
  resource_id CHAR(36) NOT NULL COMMENT '稳定结果资源',
  expires_at DATETIME(3) NOT NULL COMMENT '24小时重放窗口',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY (user_id,operation,idempotency_key),
  FOREIGN KEY (user_id) REFERENCES app_user (id),
  INDEX ix_idempotency_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='写操作幂等控制';

-- 发布中的概念图快照
CREATE TABLE release_concept (
  release_id CHAR(36) NOT NULL COMMENT '固定发布',
  concept_id CHAR(36) NOT NULL COMMENT '稳定概念标识',
  title VARCHAR(128) NOT NULL COMMENT '发布时名称',
  prerequisite_ids JSON NOT NULL COMMENT '同发布前置ID；发布时校验存在与无环',
  PRIMARY KEY (release_id,concept_id),
  FOREIGN KEY (release_id) REFERENCES content_release (id),
  FOREIGN KEY (concept_id) REFERENCES concept (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='发布中的概念图快照';
