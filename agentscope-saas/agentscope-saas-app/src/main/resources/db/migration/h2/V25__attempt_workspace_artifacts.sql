-- H2 counterpart of V25.

ALTER TABLE run_artifacts
    ADD COLUMN attempt_id UUID REFERENCES run_attempts(id) ON DELETE SET NULL;

CREATE INDEX ix_run_artifacts_attempt
    ON run_artifacts(org_id, attempt_id, created_at);
