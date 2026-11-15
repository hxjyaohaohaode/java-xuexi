-- Additive private assets; release existing source-kind constraint by metadata, never by guessed generated name.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE asset_payload (
  asset_id CHAR(36) NOT NULL COMMENT '实际私有素材对象',
  filename VARCHAR(128) NOT NULL COMMENT '去除路径及控制字符的显示文件名',
  content_bytes MEDIUMBLOB NULL COMMENT '最多8MiB真实数据；撤回后置NULL立即释放内容',
  revision INT NOT NULL DEFAULT 1 COMMENT '素材不可编辑；撤回时递增',
  status VARCHAR(16) NOT NULL DEFAULT 'READY' COMMENT 'READY或REVOKED；不提供公开URL',
  pixel_width INT NULL COMMENT '经过受控解析的图片或视频宽度',
  pixel_height INT NULL COMMENT '经过受控解析的图片或视频高度',
  duration_millis BIGINT NULL COMMENT '可解析音视频时长；不推测教学质量',
  validation_method VARCHAR(64) NOT NULL COMMENT '容器结构验证，不冒充防病毒扫描',
  revoked_at DATETIME(3) NULL COMMENT '用户撤回时刻UTC',
  PRIMARY KEY (asset_id),
  FOREIGN KEY (asset_id) REFERENCES asset_object(id),
  CHECK (revision > 0),
  CHECK (status IN ('READY','REVOKED')),
  CHECK ((status='READY' AND content_bytes IS NOT NULL) OR (status='REVOKED' AND content_bytes IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='有界私有素材实体与撤回状态';

-- MYSQL-CHECK-REPLACEMENT-BEGIN
SET @typeforge_asset_constraint = (SELECT c.CONSTRAINT_NAME FROM information_schema.CHECK_CONSTRAINTS c JOIN information_schema.TABLE_CONSTRAINTS t ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE t.TABLE_SCHEMA=DATABASE() AND t.TABLE_NAME='artifact_input_reference' AND c.CHECK_CLAUSE LIKE '%source_kind%');
SET @typeforge_asset_ddl = CONCAT('ALTER TABLE artifact_input_reference DROP CHECK `',@typeforge_asset_constraint,'`');
PREPARE typeforge_asset_stmt FROM @typeforge_asset_ddl;
EXECUTE typeforge_asset_stmt;
DEALLOCATE PREPARE typeforge_asset_stmt;
-- MYSQL-CHECK-REPLACEMENT-END
ALTER TABLE artifact_input_reference ADD CONSTRAINT ck_artifact_input_source_kind CHECK (source_kind IN ('GOAL','MEMORY','ARTIFACT','ATTEMPT','KNOWLEDGE_CHUNK','ASSET'));
