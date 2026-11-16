-- Bounded private parsing previews; commit creates the existing immutable capture receipts.
CREATE TABLE import_batch (
  id CHAR(36) NOT NULL COMMENT 'Private preview identity',
  source_id CHAR(36) NOT NULL COMMENT 'Registered source; URLs are never fetched by this feature',
  owner_id CHAR(36) NOT NULL COMMENT 'Authenticated active editor who supplied the files',
  revision INT NOT NULL DEFAULT 1 COMMENT 'Compare-and-swap lifecycle revision',
  status VARCHAR(16) NOT NULL DEFAULT 'PREVIEW' COMMENT 'PREVIEW COMMITTED CANCELLED EXPIRED',
  processing_note VARCHAR(2000) NOT NULL COMMENT 'Editor supplied provenance and processing intent',
  created_at DATETIME(3) NOT NULL COMMENT 'Actual admission time UTC',
  expires_at DATETIME(3) NOT NULL COMMENT 'Uncommitted preview lifetime, twenty-four hours',
  PRIMARY KEY (id),
  UNIQUE (id,source_id),
  FOREIGN KEY (source_id) REFERENCES knowledge_source(id),
  FOREIGN KEY (owner_id) REFERENCES app_user(id),
  CHECK (revision > 0),
  CHECK (status IN ('PREVIEW','COMMITTED','CANCELLED','EXPIRED')),
  CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Private bounded batch document import and review lifecycle';
CREATE INDEX ix_import_batch_owner ON import_batch(owner_id,created_at);
CREATE INDEX ix_import_batch_expiry ON import_batch(status,expires_at);

CREATE TABLE import_batch_item (
  id CHAR(36) NOT NULL COMMENT 'Stable per-file preview identity',
  batch_id CHAR(36) NOT NULL COMMENT 'Owning private batch',
  source_id CHAR(36) NOT NULL COMMENT 'Same immutable registered source as the batch',
  ordinal_number INT NOT NULL COMMENT 'Preserves submitted file order',
  file_name VARCHAR(128) NOT NULL COMMENT 'Display name only; paths and control characters removed',
  media_type VARCHAR(128) NOT NULL COMMENT 'Actual supported extraction format or generic unrecognized file',
  byte_size INT NOT NULL COMMENT 'Actual uploaded byte count, at most one MiB',
  file_hash CHAR(64) NOT NULL COMMENT 'SHA256 of uploaded bytes; raw binary is not retained',
  original_hash CHAR(64) NULL COMMENT 'SHA256 of extracted normalized text',
  parser_version VARCHAR(40) NOT NULL COMMENT 'Actual deterministic extraction implementation',
  status VARCHAR(16) NOT NULL COMMENT 'READY FAILED COMMITTED SKIPPED',
  error_code VARCHAR(64) NULL COMMENT 'Stable safe per-file parser failure, never private parser details',
  message VARCHAR(512) NOT NULL COMMENT 'Extraction scope, limitations or safe error',
  original_markdown MEDIUMTEXT NOT NULL COMMENT 'Preview text; removed at commit cancellation expiry or account closure',
  cleaned_markdown MEDIUMTEXT NOT NULL COMMENT 'Initial or confirmed preview text; same retention as original',
  capture_id CHAR(36) NULL COMMENT 'Actual immutable content_ingestion receipt after commit',
  PRIMARY KEY (id),
  UNIQUE (batch_id,ordinal_number),
  FOREIGN KEY (batch_id,source_id) REFERENCES import_batch(id,source_id),
  FOREIGN KEY (source_id,capture_id) REFERENCES content_ingestion(source_id,id),
  CHECK (ordinal_number >= 0 AND ordinal_number < 8),
  CHECK (byte_size >= 0 AND byte_size <= 1048576),
  CHECK (status IN ('READY','FAILED','COMMITTED','SKIPPED')),
  CHECK ((status='COMMITTED' AND capture_id IS NOT NULL) OR (status<>'COMMITTED' AND capture_id IS NULL)),
  CHECK ((status='FAILED' AND error_code IS NOT NULL) OR (status<>'FAILED' AND error_code IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Per-file parsing results with upload hash and pinned capture lineage';
