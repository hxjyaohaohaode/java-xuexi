CREATE TABLE learning_context_checkpoint (
  id CHAR(36) NOT NULL COMMENT 'Deterministic versioned selection identity',
  workspace_id CHAR(36) NOT NULL COMMENT 'Private learner workspace',
  snapshot_id CHAR(36) NOT NULL COMMENT 'Complete immutable source manifest',
  concept_id CHAR(36) NULL COMMENT 'Optional published lesson within the workspace release',
  source_hash CHAR(64) NOT NULL COMMENT 'Original snapshot digest',
  digest CHAR(64) NOT NULL COMMENT 'Canonical checkpoint digest',
  payload JSON NOT NULL COMMENT 'Scoped typed facts, retention counts and source revisions',
  PRIMARY KEY (id),
  FOREIGN KEY (workspace_id) REFERENCES learning_workspace(id),
  FOREIGN KEY (snapshot_id) REFERENCES context_snapshot(id),
  FOREIGN KEY (concept_id) REFERENCES concept(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Durable scoped learning context with immutable provenance';

CREATE TABLE study_context_checkpoint (
  id CHAR(36) NOT NULL COMMENT 'Fenced teaching role checkpoint',
  run_id CHAR(36) NOT NULL COMMENT 'Owning study workflow',
  step_key VARCHAR(64) NOT NULL COMMENT 'Admitted task from the validated graph',
  plan_revision INT NOT NULL COMMENT 'Graph generation',
  run_epoch BIGINT NOT NULL COMMENT 'Pause and recovery fence',
  learning_context_id CHAR(36) NOT NULL COMMENT 'Revalidatable personal source checkpoint',
  request_id CHAR(36) NULL COMMENT 'Optional persisted tutor request',
  receipt JSON NOT NULL COMMENT 'Counts and predecessor checkpoint identities without private text',
  handoff_data JSON NOT NULL COMMENT 'Only pinned draft excerpts from incoming graph edges',
  handoff_hash CHAR(64) NOT NULL COMMENT 'Canonical receipt and incoming draft integrity',
  prepared_prompt JSON NULL COMMENT 'Frozen authorized typed tutor input before dispatch',
  wire_body MEDIUMTEXT NULL COMMENT 'Exact prepared model request without transport credentials',
  prepared_hash CHAR(64) NULL COMMENT 'Integrity digest of typed prompt and wire bytes',
  PRIMARY KEY (id),
  UNIQUE (run_id,plan_revision,run_epoch,step_key),
  UNIQUE (request_id),
  FOREIGN KEY (run_id) REFERENCES study_workflow(run_id),
  FOREIGN KEY (learning_context_id) REFERENCES learning_context_checkpoint(id),
  FOREIGN KEY (request_id) REFERENCES tutor_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Role-specific context lineage and durable prepared-input replay';
