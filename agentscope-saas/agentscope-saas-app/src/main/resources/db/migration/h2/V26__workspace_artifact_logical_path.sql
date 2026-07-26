-- H2 counterpart of V26. Existing rows retain the evidence JSON fallback.

ALTER TABLE run_artifacts
    ADD COLUMN logical_path VARCHAR(2048);

CREATE INDEX ix_run_artifacts_attempt_path
    ON run_artifacts(org_id, attempt_id, logical_path);
