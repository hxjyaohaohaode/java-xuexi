-- Additive binary export storage. Published V001-V006 remain unchanged.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

ALTER TABLE export_job ADD COLUMN lease_token CHAR(36) NULL COMMENT '渲染租约；取消及恢复使迟到写入无效';
ALTER TABLE export_job ADD COLUMN lease_until DATETIME(3) NULL COMMENT '受控渲染期限UTC';

CREATE TABLE artifact_export_binary (
  export_id CHAR(36) NOT NULL COMMENT '当前导出作业',
  mime_type VARCHAR(128) NOT NULL COMMENT '真实二进制媒体类型',
  filename VARCHAR(128) NOT NULL COMMENT '服务端生成ASCII文件名',
  content_bytes MEDIUMBLOB NOT NULL COMMENT '最多8MiB实际文件；无外部公开URL',
  content_hash CHAR(64) NOT NULL COMMENT '原始字节SHA256',
  byte_size INT NOT NULL COMMENT '下载字节数',
  PRIMARY KEY (export_id),
  FOREIGN KEY (export_id) REFERENCES export_job(id),
  CHECK (byte_size BETWEEN 1 AND 8388608)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='受控DOCX/PPTX/PDF导出实体';
