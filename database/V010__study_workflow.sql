SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE study_workflow (
  run_id CHAR(36) NOT NULL COMMENT '持久任务图，状态以agent_run为准',
  mode_key VARCHAR(24) NOT NULL COMMENT 'MODEL或明确标注的EVIDENCE_ONLY',
  requested_outputs JSON NOT NULL COMMENT '用户明确选择的作品种类',
  selected_attempts JSON NOT NULL COMMENT '入口已校验的错误或辅助作答，不静默扩大选区',
  plan_json JSON NULL COMMENT '已验证的有限教学分工计划',
  applied_revision INT NOT NULL DEFAULT 0 COMMENT '计划应用后的DAG版本',
  conversation_id CHAR(36) NOT NULL COMMENT '受配额和上下文约束的实际导师会话',
  next_poll_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '公平轮转调度时间',
  PRIMARY KEY (run_id),
  FOREIGN KEY (run_id) REFERENCES agent_run(id),
  FOREIGN KEY (conversation_id) REFERENCES conversation(id),
  CHECK (mode_key IN ('MODEL','EVIDENCE_ONLY')),
  INDEX ix_study_poll (next_poll_at,run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='从规划到草稿作品的可恢复执行适配器';

CREATE TABLE study_step_execution (
  run_id CHAR(36) NOT NULL COMMENT '任务图UUID',
  plan_revision INT NOT NULL COMMENT '执行所属计划版本',
  step_key VARCHAR(64) NOT NULL COMMENT '步骤键',
  run_epoch BIGINT NOT NULL COMMENT '暂停恢复后独立执行代次，历史不覆盖',
  request_id CHAR(36) NULL COMMENT '可对账的实际导师请求',
  tool_id CHAR(36) NOT NULL COMMENT '预算与执行回执',
  lease_json JSON NOT NULL COMMENT '内部工作进程租约，绝不返回客户端',
  artifact_id CHAR(36) NULL COMMENT '实际产生的草稿作品',
  PRIMARY KEY (run_id,plan_revision,step_key,run_epoch),
  FOREIGN KEY (run_id) REFERENCES agent_run(id),
  FOREIGN KEY (request_id) REFERENCES tutor_request(id),
  FOREIGN KEY (tool_id) REFERENCES tool_execution(id),
  FOREIGN KEY (artifact_id) REFERENCES learning_artifact(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='工作流中外部任务和内部步骤的恢复映射';
