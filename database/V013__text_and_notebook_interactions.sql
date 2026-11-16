-- Independently reviewed exact short answers and version-scoped notebook interaction state.
CREATE TABLE exercise_text_review_receipt (
  exercise_id CHAR(36) NOT NULL COMMENT '固定题目UUID',
  exercise_revision INT NOT NULL COMMENT '不可变题目修订',
  review_id CHAR(36) NOT NULL COMMENT '作者之外编辑的真实批准记录',
  answer_hash CHAR(64) NOT NULL COMMENT '审核时完整私有answer_spec的规范JSON哈希',
  rubric_version VARCHAR(32) NOT NULL COMMENT '确定性短答案判定版本',
  PRIMARY KEY (exercise_id,exercise_revision),
  UNIQUE KEY uq_text_rubric_review (review_id),
  FOREIGN KEY (exercise_id,exercise_revision) REFERENCES exercise(id,revision),
  FOREIGN KEY (review_id) REFERENCES content_review(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='短答案私有判分规则的独立审核凭证';

ALTER TABLE notebook_action_receipt DROP CHECK ck_notebook_action_supported;
ALTER TABLE notebook_action_receipt ADD CONSTRAINT ck_notebook_action_supported CHECK (action_key IN ('predict','openEvidence','submitAnswer','requestCompile','requestHint','selectType','setParameter','navigateArtifact','appendBlock'));

CREATE TABLE notebook_block_state (
  artifact_id CHAR(36) NOT NULL COMMENT '本人错题或交互作品',
  artifact_revision INT NOT NULL COMMENT '状态仅属于此不可变源稿修订',
  block_key VARCHAR(64) NOT NULL COMMENT '源稿内的固定块标识',
  revision INT NOT NULL COMMENT '每次已提交动作递增，拒绝迟到选择覆盖',
  state_json JSON NOT NULL COMMENT '真实已选类型或题目、最高提示和最近动作结果；不含标准答案',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC最近持久动作时间',
  PRIMARY KEY (artifact_id,artifact_revision,block_key),
  FOREIGN KEY (artifact_id,artifact_revision) REFERENCES artifact_revision(artifact_id,revision),
  CHECK (revision BETWEEN 1 AND 2147483647)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='按作品版本与块隔离的可恢复学习交互状态';
