-- Additive learning interaction and text export persistence. Earlier migrations remain immutable.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

ALTER TABLE export_job ADD COLUMN failure_code VARCHAR(64) NULL COMMENT '受控失败原因码，不含原始异常或机密';

CREATE TABLE workspace_lab (
  workspace_id CHAR(36) NOT NULL COMMENT '固定课程工作空间',
  lab_id CHAR(36) NOT NULL COMMENT '不可变类型实验',
  PRIMARY KEY (lab_id),
  FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id),
  FOREIGN KEY (lab_id) REFERENCES lab_session(id),
  INDEX ix_workspace_lab (workspace_id,lab_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='类型实验与工作空间的归属';

CREATE TABLE notebook_action_receipt (
  id CHAR(36) NOT NULL COMMENT '动作结果UUID',
  artifact_id CHAR(36) NOT NULL COMMENT '被操作作品',
  artifact_revision INT NOT NULL COMMENT '不可变作品版本',
  block_key VARCHAR(64) NOT NULL COMMENT '固定块标识',
  action_key VARCHAR(32) NOT NULL COMMENT '白名单动作',
  result_json JSON NOT NULL COMMENT '真实判分或证据操作结果',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC',
  PRIMARY KEY (id),
  FOREIGN KEY (artifact_id,artifact_revision) REFERENCES artifact_revision(artifact_id,revision),
  CHECK (action_key IN ('predict','openEvidence','submitAnswer')),
  INDEX ix_notebook_action (artifact_id,artifact_revision,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='可幂等重放的错题交互结果';

CREATE TABLE artifact_export_content (
  export_id CHAR(36) NOT NULL COMMENT '导出作业',
  mime_type VARCHAR(64) NOT NULL COMMENT '白名单文本媒体类型',
  filename VARCHAR(128) NOT NULL COMMENT '服务端生成ASCII下载名',
  content_text MEDIUMTEXT NOT NULL COMMENT '经过转义的固定版本内容',
  content_hash CHAR(64) NOT NULL COMMENT '精确UTF8字节SHA256',
  byte_size INT NOT NULL COMMENT '下载字节数',
  PRIMARY KEY (export_id),
  FOREIGN KEY (export_id) REFERENCES export_job(id),
  CHECK (byte_size BETWEEN 1 AND 1048576),
  CHECK (mime_type IN ('text/markdown','text/html','image/svg+xml'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='受控文本渲染器的真实下载内容';
