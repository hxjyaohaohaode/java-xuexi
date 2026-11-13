-- Forward-only additions for the durable runtime. Existing migrations are immutable.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

ALTER TABLE outbox_event ADD COLUMN next_attempt_at DATETIME(3) NULL COMMENT 'UTC bounded retry deadline';
ALTER TABLE outbox_event ADD COLUMN last_error_code VARCHAR(64) NULL COMMENT 'Safe error category only; never exception text';

CREATE TABLE workspace_attempt (
  workspace_id CHAR(36) NOT NULL COMMENT 'Workspace owning this learning interaction',
  attempt_id CHAR(36) NOT NULL COMMENT 'Immutable graded attempt',
  PRIMARY KEY (workspace_id,attempt_id),
  UNIQUE (attempt_id),
  FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id),
  FOREIGN KEY (attempt_id) REFERENCES exercise_attempt(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Explicit workspace to learning evidence link';

CREATE TABLE learning_family_evidence (
  user_id CHAR(36) NOT NULL COMMENT 'Authenticated owner',
  concept_id CHAR(36) NOT NULL COMMENT 'Target concept',
  family_id CHAR(36) NOT NULL COMMENT 'One independent sample per exercise family',
  origin_attempt_id CHAR(36) NOT NULL COMMENT 'First independent submitted result',
  correct_weight DECIMAL(10,4) NOT NULL COMMENT 'Correct contribution fixed at first independent attempt',
  total_weight DECIMAL(10,4) NOT NULL COMMENT 'Attribution weight for that family',
  PRIMARY KEY (user_id,concept_id,family_id),
  FOREIGN KEY (user_id) REFERENCES app_user(id),
  FOREIGN KEY (concept_id) REFERENCES concept(id),
  FOREIGN KEY (origin_attempt_id) REFERENCES exercise_attempt(id),
  CHECK (total_weight > 0 AND correct_weight >= 0 AND correct_weight <= total_weight)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Non-farmable learning evidence with explicit provenance';

CREATE TABLE workspace_projection (
  workspace_id CHAR(36) NOT NULL COMMENT 'Projection key',
  processed_sequence BIGINT NOT NULL DEFAULT 0 COMMENT 'Monotonic durable replay cursor',
  activity_count BIGINT NOT NULL DEFAULT 0 COMMENT 'Semantic event count; never mastery',
  updated_at DATETIME(3) NOT NULL COMMENT 'Last committed processing UTC',
  PRIMARY KEY (workspace_id),
  FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id),
  CHECK (processed_sequence >= 0 AND activity_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Rebuildable activity projection and freshness indicator';

CREATE TABLE artifact_input_reference (
  workspace_id CHAR(36) NOT NULL COMMENT 'Resolved workspace scope',
  artifact_id CHAR(36) NOT NULL COMMENT 'Dependent artifact',
  artifact_revision INT NOT NULL COMMENT 'Dependent immutable revision',
  source_kind VARCHAR(16) NOT NULL COMMENT 'GOAL MEMORY ARTIFACT ATTEMPT KNOWLEDGE_CHUNK',
  source_id CHAR(36) NOT NULL COMMENT 'Typed source identity checked by application',
  source_revision INT NOT NULL COMMENT 'Pinned source revision',
  source_hash CHAR(64) NOT NULL COMMENT 'Pinned canonical content hash',
  PRIMARY KEY (artifact_id,artifact_revision,source_kind,source_id),
  FOREIGN KEY (workspace_id,artifact_id) REFERENCES learning_artifact(workspace_id,id),
  FOREIGN KEY (artifact_id,artifact_revision) REFERENCES artifact_revision(artifact_id,revision),
  CHECK (source_revision > 0),
  CHECK (source_kind IN ('GOAL','MEMORY','ARTIFACT','ATTEMPT','KNOWLEDGE_CHUNK')),
  INDEX ix_artifact_input (workspace_id,source_kind,source_id,source_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Typed lineage index for revocation and stale propagation';

CREATE TABLE local_evidence_workflow (
  run_id CHAR(36) NOT NULL COMMENT 'Admitted deterministic read-only run',
  query_text VARCHAR(1000) NOT NULL COMMENT 'Private learner query used by the registered local search adapter',
  adapter_version VARCHAR(32) NOT NULL COMMENT 'Pinned workflow implementation',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC',
  PRIMARY KEY (run_id),
  FOREIGN KEY (run_id) REFERENCES agent_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Explicitly enabled local workflow; never an implicit model call';
