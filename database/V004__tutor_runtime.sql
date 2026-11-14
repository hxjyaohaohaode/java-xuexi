-- Forward extension of the original conversation/request/message tables.
-- Plaintext prompts are never copied here: only user questions and revocable reference identities persist.
CREATE TABLE workspace_conversation (
  conversation_id CHAR(36) NOT NULL COMMENT '原始导师会话UUID；一条会话只绑定一个工作空间',
  workspace_id CHAR(36) NOT NULL COMMENT '本人固定工作空间；课程版本同时由会话与空间核对',
  PRIMARY KEY (conversation_id),
  UNIQUE (workspace_id,conversation_id),
  FOREIGN KEY (conversation_id) REFERENCES conversation(id),
  FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id),
  INDEX ix_workspace_conversation (workspace_id,conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT '导师会话与固定工作空间绑定';

CREATE TABLE tutor_daily_budget (
  user_id CHAR(36) NOT NULL COMMENT '额度所属用户；在user行锁内跨工作空间串行扣减',
  budget_date DATE NOT NULL COMMENT '按UTC划分的准入日期；跨午夜结算仍回写原日',
  request_count INT NOT NULL DEFAULT 0 COMMENT '当日已接受请求次数；取消不退次数',
  reserved_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '当日尚未结算或外部结果未知的预留token总额',
  used_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '当日已经幂等结算的供应商自报token总额；不是货币账单',
  PRIMARY KEY (user_id,budget_date),
  FOREIGN KEY (user_id) REFERENCES app_user(id),
  CHECK (request_count>=0 AND reserved_tokens>=0 AND used_tokens>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT '按用户和UTC日计量的导师请求及token额度';

CREATE TABLE tutor_request_runtime (
  request_id CHAR(36) NOT NULL COMMENT '对应原始tutor_request；持久恢复主键',
  workspace_id CHAR(36) NOT NULL COMMENT '请求所属本人工作空间',
  snapshot_id CHAR(36) NOT NULL COMMENT '用户明确选定的不可变上下文快照；每次准入与返回复检',
  mode VARCHAR(16) NOT NULL COMMENT 'EVIDENCE_ONLY确定性证据整理或MODEL显式外部模型',
  request_epoch BIGINT NOT NULL DEFAULT 1 COMMENT '取消、过期及重新领取时递增的结果写入栅栏',
  lease_token CHAR(36) NULL COMMENT '本次领取随机令牌；过期不可提交，取消后仅可对账同次已派发用量',
  lease_until DATETIME(3) NULL COMMENT '当前工作租约UTC截止时间；终态清空',
  deadline_at DATETIME(3) NOT NULL COMMENT '含排队时间的请求绝对UTC截止时间',
  attempt_count INT NOT NULL DEFAULT 0 COMMENT '仅未派发外部调用的安全领取次数；最多3次',
  input_hash CHAR(64) NOT NULL COMMENT '规范化请求参数SHA256；与幂等键共同防请求内容混淆',
  context_hash CHAR(64) NOT NULL COMMENT '创建时固定快照的规范化内容hash；不复制私人记忆正文',
  evidence_refs JSON NOT NULL COMMENT '实际检索且固定revision与hash的公开证据；完成后缩为已引用集合',
  provider_fingerprint CHAR(64) NULL COMMENT '配置endpoint及model和输出上限hash；不含密钥；改变后拒绝旧队列派发',
  reserved_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '本请求尚未结算的保守token预留；未知结果保留，已结算归零',
  used_tokens BIGINT NULL COMMENT '供应商自报且通过边界校验的实际token总数；未确认时NULL',
  billing_state VARCHAR(16) NOT NULL COMMENT 'NONE无外部调用；RESERVED预留；SETTLED已结算；UNKNOWN结果未知；RELEASED确认未调用已释放',
  budget_date DATE NOT NULL COMMENT '本请求计入的UTC额度日期；结算不可转移日期',
  dispatched_at DATETIME(3) NULL COMMENT '派发外部HTTP前落盘的UTC标记；崩溃后不可盲重放',
  result_hash CHAR(64) NULL COMMENT '完整准入回答及引用与用量hash；同次不同内容的重复完成被拒绝',
  completed_at DATETIME(3) NULL COMMENT '终态写入UTC时间；不冒充供应商实际完成时间',
  PRIMARY KEY (request_id),
  FOREIGN KEY (request_id) REFERENCES tutor_request(id),
  FOREIGN KEY (workspace_id,snapshot_id) REFERENCES context_snapshot(workspace_id,id),
  CHECK (mode IN ('EVIDENCE_ONLY','MODEL')),
  CHECK (request_epoch>0 AND attempt_count BETWEEN 0 AND 3),
  CHECK (reserved_tokens>=0 AND (used_tokens IS NULL OR used_tokens>=0)),
  CHECK (billing_state IN ('NONE','RESERVED','SETTLED','UNKNOWN','RELEASED')),
  INDEX ix_tutor_runtime_lease (lease_until,request_id),
  INDEX ix_tutor_runtime_workspace (workspace_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT '持久导师请求的租约取消来源与计量状态';

CREATE INDEX ix_tutor_pending ON tutor_request(status,created_at,id);
CREATE INDEX ix_tutor_conversation ON tutor_request(conversation_id,created_at,id);

CREATE TABLE tutor_artifact_link (
  artifact_id CHAR(36) NOT NULL COMMENT '显式保存的DOCUMENT作品UUID；每个作品记录唯一导师来源',
  artifact_revision INT NOT NULL COMMENT '首次保存的不可变DRAFT版本；后续学生编辑不覆盖该链接',
  request_id CHAR(36) NOT NULL COMMENT '来源导师请求；保存时须COMPLETED且来源仍有效',
  snapshot_id CHAR(36) NOT NULL COMMENT '来源回答明确选择的上下文快照；作品展开保留全部实际依赖',
  response_hash CHAR(64) NOT NULL COMMENT '当时可见回答与已校验证据的规范化hash',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '导师回答保存为学习文档的UTC时间',
  PRIMARY KEY (artifact_id),
  FOREIGN KEY (artifact_id,artifact_revision) REFERENCES artifact_revision(artifact_id,revision),
  FOREIGN KEY (request_id) REFERENCES tutor_request(id),
  FOREIGN KEY (snapshot_id) REFERENCES context_snapshot(id),
  INDEX ix_tutor_artifact_request (request_id,artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT '导师回答到学习文档的可追溯来源链接';
