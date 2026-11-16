SET NAMES utf8mb4;
SET time_zone = '+00:00';

ALTER TABLE learning_workspace ADD CONSTRAINT uq_workspace_owner_release UNIQUE (id,owner_id,release_id);

CREATE TABLE evaluation_suite (
  id CHAR(36) NOT NULL COMMENT '本人检索评测集UUID',
  owner_id CHAR(36) NOT NULL COMMENT '真实会话所有者',
  workspace_id CHAR(36) NOT NULL COMMENT '私人工作空间',
  release_id CHAR(36) NOT NULL COMMENT '工作空间固定发布',
  current_revision INT NOT NULL DEFAULT 1 COMMENT '最新不可变评测修订',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC创建时间',
  updated_at DATETIME(3) NOT NULL COMMENT '最后修改或归档时间',
  PRIMARY KEY (id),
  UNIQUE (id,owner_id,workspace_id,release_id),
  FOREIGN KEY (workspace_id,owner_id,release_id) REFERENCES learning_workspace(id,owner_id,release_id),
  CHECK (current_revision BETWEEN 1 AND 100),
  CHECK (status IN ('ACTIVE','ARCHIVED')),
  INDEX ix_evaluation_owner (owner_id,status,id),
  INDEX ix_evaluation_workspace (workspace_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='私人评测集，不作为学生成绩或贡献';

CREATE TABLE evaluation_suite_revision (
  suite_id CHAR(36) NOT NULL COMMENT '评测集UUID',
  revision INT NOT NULL COMMENT '不可覆盖修订号',
  title VARCHAR(120) NOT NULL COMMENT '本版标题',
  description VARCHAR(2000) NOT NULL COMMENT '用户声明的评测目的',
  case_count INT NOT NULL COMMENT '实际样例数量',
  snapshot_json JSON NOT NULL COMMENT '固定查询、用户标注、发布及检索器版本',
  corpus_json JSON NOT NULL COMMENT '排序后课程卡片与出处版本清单',
  content_hash CHAR(64) NOT NULL COMMENT '规范化snapshot SHA256',
  corpus_hash CHAR(64) NOT NULL COMMENT '规范化corpus SHA256',
  byte_size INT NOT NULL COMMENT '快照和清单UTF8实际字节',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC创建时间',
  PRIMARY KEY (suite_id,revision),
  FOREIGN KEY (suite_id) REFERENCES evaluation_suite(id),
  CHECK (revision BETWEEN 1 AND 100),
  CHECK (case_count BETWEEN 1 AND 40),
  CHECK (byte_size BETWEEN 1 AND 262144)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='评测定义与金标准固定版本；不推定人工独立审核';

CREATE TABLE evaluation_run (
  id CHAR(36) NOT NULL COMMENT '真实评测任务UUID',
  suite_id CHAR(36) NOT NULL COMMENT '评测集',
  suite_revision INT NOT NULL COMMENT '执行的固定修订',
  owner_id CHAR(36) NOT NULL COMMENT '运行归属',
  workspace_id CHAR(36) NOT NULL COMMENT '固定工作空间',
  release_id CHAR(36) NOT NULL COMMENT '固定课程发布',
  suite_hash CHAR(64) NOT NULL COMMENT '准入时定义哈希',
  corpus_hash CHAR(64) NOT NULL COMMENT '准入时课程清单哈希',
  index_version VARCHAR(64) NOT NULL COMMENT '实际检索算法版本',
  evaluator_version VARCHAR(64) NOT NULL COMMENT '实际指标公式实现版本',
  top_k INT NOT NULL COMMENT '1至8个检索结果',
  case_count INT NOT NULL COMMENT '固定样例数',
  completed_cases INT NOT NULL DEFAULT 0 COMMENT '已真实执行并持久保存的样例',
  status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED',
  error_code VARCHAR(64) NULL COMMENT '不含查询或数据库细节的失败码',
  lease_token CHAR(36) NULL COMMENT '内部工作租约不公开',
  lease_until DATETIME(3) NULL COMMENT 'UTC租约期限',
  metrics_json JSON NULL COMMENT '仅完整真实结果产生聚合指标',
  result_hash CHAR(64) NULL COMMENT '排除耗时的确定性结果SHA256',
  report_json JSON NULL COMMENT '完成时冻结的可复核报告',
  report_hash CHAR(64) NULL COMMENT '实际报告字节SHA256',
  report_bytes INT NOT NULL DEFAULT 0 COMMENT '完成报告UTF8字节容量',
  result_bytes INT NOT NULL DEFAULT 0 COMMENT '实际逐例JSON字节，失败和取消仍计入私人容量',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC排队时间',
  started_at DATETIME(3) NULL COMMENT 'UTC开始执行时间',
  finished_at DATETIME(3) NULL COMMENT 'UTC终态时间',
  PRIMARY KEY (id),
  FOREIGN KEY (suite_id,suite_revision) REFERENCES evaluation_suite_revision(suite_id,revision),
  FOREIGN KEY (suite_id,owner_id,workspace_id,release_id) REFERENCES evaluation_suite(id,owner_id,workspace_id,release_id),
  CHECK (top_k BETWEEN 1 AND 8),
  CHECK (case_count BETWEEN 1 AND 40 AND completed_cases BETWEEN 0 AND case_count),
  CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
  CHECK ((status='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)),
  CHECK ((status='COMPLETED' AND metrics_json IS NOT NULL AND result_hash IS NOT NULL AND report_json IS NOT NULL AND report_hash IS NOT NULL AND report_bytes>0 AND completed_cases=case_count) OR (status<>'COMPLETED' AND metrics_json IS NULL AND result_hash IS NULL AND report_json IS NULL AND report_hash IS NULL AND report_bytes=0)),
  CHECK (report_bytes BETWEEN 0 AND 2097152),
  CHECK (result_bytes BETWEEN 0 AND 1048576),
  CHECK ((completed_cases=0 AND result_bytes=0) OR (completed_cases>0 AND result_bytes>0)),
  CHECK (status<>'QUEUED' OR completed_cases=0),
  INDEX ix_evaluation_queue (status,created_at,id),
  INDEX ix_evaluation_run_owner (owner_id,status,created_at),
  INDEX ix_evaluation_run_suite (suite_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='有界检索评测队列与已冻结真实结果';

CREATE TABLE evaluation_case_result (
  run_id CHAR(36) NOT NULL COMMENT '所属运行',
  case_key VARCHAR(64) NOT NULL COMMENT '定义中的稳定样例键',
  case_position INT NOT NULL COMMENT '定义中的零起始顺序',
  result_json JSON NOT NULL COMMENT '实际排名、证据、匹配和耗时',
  result_hash CHAR(64) NOT NULL COMMENT '实际规范化样例结果哈希',
  byte_size INT NOT NULL COMMENT '规范化样例JSON实际UTF8字节',
  PRIMARY KEY (run_id,case_key),
  UNIQUE (run_id,case_position),
  FOREIGN KEY (run_id) REFERENCES evaluation_run(id),
  CHECK (case_position BETWEEN 0 AND 39),
  CHECK (byte_size BETWEEN 1 AND 65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='真实已执行样例，取消保留部分结果但不生成完整分数';

CREATE TABLE evaluation_admission_lock (
  singleton_id INT NOT NULL COMMENT '全局队列有界准入锁',
  PRIMARY KEY (singleton_id),
  CHECK (singleton_id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不持锁执行检索；仅串行短期队列准入';
INSERT INTO evaluation_admission_lock(singleton_id) VALUES(1);
