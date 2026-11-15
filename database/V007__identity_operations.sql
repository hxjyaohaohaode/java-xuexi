-- Forward-only account lifecycle and bounded authentication admission.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

ALTER TABLE app_user ADD COLUMN display_name VARCHAR(80) NOT NULL DEFAULT '' COMMENT '用户可编辑昵称；不作为登录或权限标识';
ALTER TABLE app_user ADD COLUMN profile_revision INT NOT NULL DEFAULT 1 COMMENT '资料乐观锁版本';
ALTER TABLE app_user ADD COLUMN auth_epoch BIGINT NOT NULL DEFAULT 1 COMMENT '改密和关闭账户递增；旧认证不得创建新会话';
ALTER TABLE app_user ADD COLUMN closed_at DATETIME(3) NULL COMMENT '主动关闭UTC；保留不可变历史而非宣称物理擦除';

CREATE TABLE account_session (
  id CHAR(36) NOT NULL COMMENT '仅供本人撤销的会话记录UUID；不是登录凭据',
  user_id CHAR(36) NOT NULL COMMENT '登录认证所得用户ID',
  session_hash CHAR(64) NOT NULL COMMENT '服务端HttpSession ID的SHA256；永不公开',
  auth_epoch BIGINT NOT NULL COMMENT '创建会话时固定的账户认证版本',
  device_label VARCHAR(160) NOT NULL COMMENT '受控浏览器和平台标签；不保存原始User-Agent或IP',
  created_at DATETIME(3) NOT NULL COMMENT '服务端创建UTC',
  last_seen_at DATETIME(3) NOT NULL COMMENT '服务端最近活动UTC；最多每分钟写一次',
  expires_at DATETIME(3) NOT NULL COMMENT '固定最长有效时间UTC；容器另有空闲过期',
  revoked_at DATETIME(3) NULL COMMENT '撤销UTC；每个认证请求实时核验',
  PRIMARY KEY (id),
  UNIQUE (session_hash),
  FOREIGN KEY (user_id) REFERENCES app_user(id),
  CHECK (auth_epoch > 0),
  INDEX ix_account_session_user (user_id,revoked_at,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不存原始cookie的持久会话撤销记录';

CREATE TABLE authentication_limit (
  bucket_hash CHAR(64) NOT NULL COMMENT '策略和服务端观察客户端地址/账户的不可逆桶键',
  window_start BIGINT NOT NULL COMMENT '固定窗口起始Unix秒',
  hit_count INT NOT NULL COMMENT '本窗口已经准入次数；不存输入密码',
  expires_at DATETIME(3) NOT NULL COMMENT '到期后批量清理UTC',
  PRIMARY KEY (bucket_hash,window_start),
  CHECK (hit_count > 0),
  INDEX ix_auth_limit_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='跨实例原子认证限流；不信任客户端转发IP头';

CREATE TABLE workspace_reading (
  workspace_id CHAR(36) NOT NULL COMMENT '记录时固定的真实工作空间',
  learning_event_id CHAR(36) NOT NULL COMMENT '不可变阅读事件；用于旧记录幂等回填',
  concept_id CHAR(36) NOT NULL COMMENT '已校验固定课程概念',
  event_type VARCHAR(32) NOT NULL COMMENT 'READ_STARTED或READ_COMPLETED；均非掌握证据',
  occurred_at DATETIME(3) NOT NULL COMMENT '原事件时间；回填不修改时间',
  PRIMARY KEY (learning_event_id),
  FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id),
  FOREIGN KEY (learning_event_id) REFERENCES learning_event(id),
  FOREIGN KEY (concept_id) REFERENCES concept(id),
  INDEX ix_reading_workspace_concept (workspace_id,concept_id,event_type,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='跨页面恢复的真实阅读记录索引';

CREATE INDEX ix_learning_event_user_time ON learning_event(user_id,occurred_at,event_type);
CREATE INDEX ix_learning_event_reading_backfill ON learning_event(event_type,id);
