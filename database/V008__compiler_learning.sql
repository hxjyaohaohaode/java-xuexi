CREATE TABLE workspace_compile (
  job_id CHAR(36) NOT NULL COMMENT '独立编译队列作业',
  workspace_id CHAR(36) NOT NULL COMMENT '固定所属学习空间',
  request_json JSON NOT NULL COMMENT '仅worker可见的固定编译检查，不返回隐藏测试',
  request_hash CHAR(64) NOT NULL COMMENT '任务清单规范化SHA256',
  worker_fingerprint CHAR(64) NOT NULL COMMENT '创建时固定的服务端worker协议与配置',
  error_code VARCHAR(64) NULL COMMENT '安全错误分类，不存系统异常正文',
  PRIMARY KEY (job_id),
  FOREIGN KEY (job_id) REFERENCES compile_job(id),
  FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id),
  INDEX ix_workspace_compile (workspace_id,job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='编译作业工作空间与私有检查清单';

CREATE TABLE compiler_admission (
  singleton_id INT NOT NULL COMMENT '串行化全局队列容量准入',
  PRIMARY KEY (singleton_id),
  CHECK (singleton_id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='有界编译队列准入锁';
INSERT INTO compiler_admission(singleton_id) VALUES(1);
CREATE UNIQUE INDEX ux_compile_attempt ON compile_job(attempt_id);

-- V006 has exactly one unnamed CHECK; MySQL assigns the documented table_chk_1 name.
-- Preserve existing receipts while admitting the two new server-backed learning actions.
ALTER TABLE notebook_action_receipt DROP CHECK notebook_action_receipt_chk_1;
ALTER TABLE notebook_action_receipt ADD CONSTRAINT ck_notebook_action_supported
  CHECK (action_key IN ('predict','openEvidence','submitAnswer','requestCompile','requestHint'));

CREATE TABLE exercise_hint_receipt (
  id CHAR(36) NOT NULL COMMENT '幂等提示领取回执',
  user_id CHAR(36) NOT NULL COMMENT '真实会话学习者',
  workspace_id CHAR(36) NOT NULL COMMENT '固定工作空间',
  exercise_id CHAR(36) NOT NULL COMMENT '题目',
  exercise_revision INT NOT NULL COMMENT '固定题目版本',
  hint_level INT NOT NULL COMMENT '提示层次1至3；任何提示均不作为独立作答',
  hint_text TEXT NOT NULL COMMENT '领取时固定提示正文',
  created_at DATETIME(3) NOT NULL COMMENT '服务端UTC时间',
  PRIMARY KEY (id),
  FOREIGN KEY (user_id) REFERENCES app_user(id),
  FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id),
  FOREIGN KEY (exercise_id,exercise_revision) REFERENCES exercise(id,revision),
  CHECK (hint_level BETWEEN 1 AND 3),
  INDEX ix_hint_owner_exercise (user_id,exercise_id,exercise_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='提示使用事实与辅助作答证据';
