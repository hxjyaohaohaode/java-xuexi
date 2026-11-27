-- Teaching briefs belong to one private production run, outside the learner's long-term goals.
ALTER TABLE learning_goal ADD COLUMN origin_kind VARCHAR(20) NOT NULL DEFAULT 'LEARNER' COMMENT 'LEARNER or immutable COURSEWARE brief';
ALTER TABLE learning_goal ADD CONSTRAINT ck_goal_origin CHECK (origin_kind IN ('LEARNER','COURSEWARE'));

ALTER TABLE learning_context_checkpoint ADD CONSTRAINT fk_checkpoint_snapshot_owner FOREIGN KEY (workspace_id,snapshot_id) REFERENCES context_snapshot(workspace_id,id);
ALTER TABLE learning_context_checkpoint ADD CONSTRAINT uq_checkpoint_lesson_source UNIQUE (workspace_id,snapshot_id,concept_id,id);
ALTER TABLE personal_courseware ADD CONSTRAINT fk_lesson_learning_manifest FOREIGN KEY (workspace_id,snapshot_id,concept_id,checkpoint_id) REFERENCES learning_context_checkpoint(workspace_id,snapshot_id,concept_id,id);
ALTER TABLE personal_courseware ADD CONSTRAINT fk_lesson_workspace_release FOREIGN KEY (workspace_id,release_id) REFERENCES learning_workspace(id,release_id);

CREATE TABLE courseware_study_run (
  workspace_id CHAR(36) NOT NULL COMMENT 'Owner-scoped workspace',
  plan_id CHAR(36) NOT NULL COMMENT 'Immutable courseware revision',
  run_id CHAR(36) NOT NULL COMMENT 'Durable teaching run',
  mode_key VARCHAR(16) NOT NULL COMMENT 'MODEL or EVIDENCE_ONLY',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC admission time',
  PRIMARY KEY (run_id),
  FOREIGN KEY (workspace_id,plan_id) REFERENCES personal_courseware(workspace_id,id),
  FOREIGN KEY (workspace_id,run_id) REFERENCES agent_run(workspace_id,id),
  CHECK (mode_key IN ('MODEL','EVIDENCE_ONLY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Resume the authorized study workflow for the selected personal lesson';
CREATE INDEX ix_courseware_study_plan ON courseware_study_run(workspace_id,plan_id,mode_key,created_at);
