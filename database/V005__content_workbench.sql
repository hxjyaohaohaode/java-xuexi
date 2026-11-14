-- Authenticated editorial workbench. No users or passwords are seeded.
-- Existing immutable content/release tables remain authoritative.
CREATE TABLE content_workbench_lock (
  singleton_id INT NOT NULL COMMENT 'Serializes low-volume two-person editorial mutations',
  PRIMARY KEY (singleton_id),
  CHECK (singleton_id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Editorial transaction admission lock';
INSERT INTO content_workbench_lock(singleton_id) VALUES(1);

CREATE TABLE content_ingestion (
  id CHAR(36) NOT NULL COMMENT 'Manual capture receipt UUID',
  source_id CHAR(36) NOT NULL COMMENT 'Immutable registered source',
  author_id CHAR(36) NOT NULL COMMENT 'Authenticated submitting editor',
  original_markdown MEDIUMTEXT NOT NULL COMMENT 'Actually supplied original text; no automatic URL fetch',
  cleaned_markdown MEDIUMTEXT NOT NULL COMMENT 'Editor supplied cleaned text',
  original_hash CHAR(64) NOT NULL COMMENT 'Server calculated SHA256 of normalized original',
  cleaned_hash CHAR(64) NOT NULL COMMENT 'Server calculated SHA256 of normalized cleaned text',
  processor_version VARCHAR(32) NOT NULL COMMENT 'Actual deterministic newline normalizer version',
  processing_note VARCHAR(4000) NOT NULL COMMENT 'Editor explanation of cleaning and usage scope',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC',
  PRIMARY KEY (id),
  UNIQUE (source_id,id),
  UNIQUE (source_id,original_hash,cleaned_hash),
  FOREIGN KEY (source_id) REFERENCES knowledge_source(id),
  FOREIGN KEY (author_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Immutable manual ingestion evidence and exact deduplication';

CREATE TABLE chunk_ingestion (
  chunk_id CHAR(36) NOT NULL COMMENT 'Knowledge identity',
  chunk_revision INT NOT NULL COMMENT 'Immutable knowledge revision',
  source_id CHAR(36) NOT NULL COMMENT 'Source used by this knowledge revision',
  ingestion_id CHAR(36) NOT NULL COMMENT 'Actual captured source revision',
  PRIMARY KEY (chunk_id,chunk_revision,source_id),
  FOREIGN KEY (chunk_id,chunk_revision,source_id) REFERENCES chunk_source(chunk_id,chunk_revision,source_id),
  FOREIGN KEY (source_id,ingestion_id) REFERENCES content_ingestion(source_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Pinned capture lineage for a knowledge revision';

CREATE TABLE exercise_knowledge (
  exercise_id CHAR(36) NOT NULL COMMENT 'Exercise identity',
  exercise_revision INT NOT NULL COMMENT 'Immutable exercise revision',
  chunk_id CHAR(36) NOT NULL COMMENT 'Supporting knowledge identity',
  chunk_revision INT NOT NULL COMMENT 'Exact supporting knowledge revision',
  PRIMARY KEY (exercise_id,exercise_revision,chunk_id),
  FOREIGN KEY (exercise_id,exercise_revision) REFERENCES exercise(id,revision),
  FOREIGN KEY (chunk_id,chunk_revision) REFERENCES knowledge_chunk(id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Versioned teaching evidence behind objective answer specifications';

ALTER TABLE content_review ADD CONSTRAINT uq_content_review_once UNIQUE(target_kind,target_id,target_revision);
CREATE TABLE content_review_evidence (
  review_id CHAR(36) NOT NULL COMMENT 'One immutable independent review decision',
  reviewed_hash CHAR(64) NOT NULL COMMENT 'Complete content and lineage hash actually reviewed',
  checker_version VARCHAR(32) NOT NULL COMMENT 'Deterministic admission checker',
  reviewer_roles JSON NOT NULL COMMENT 'Actual authorized roles at review time; not a model verdict',
  PRIMARY KEY (review_id),
  FOREIGN KEY (review_id) REFERENCES content_review(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Pins a real editorial decision to its exact inputs';

CREATE TABLE release_manifest (
  release_id CHAR(36) NOT NULL COMMENT 'Immutable course manifest owner',
  manifest_json JSON NOT NULL COMMENT 'Canonical concept/content/review references; no answer plaintext',
  checker_version VARCHAR(32) NOT NULL COMMENT 'Actual publishing validation implementation',
  PRIMARY KEY (release_id),
  FOREIGN KEY (release_id) REFERENCES content_release(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Reproducible release validation evidence';
