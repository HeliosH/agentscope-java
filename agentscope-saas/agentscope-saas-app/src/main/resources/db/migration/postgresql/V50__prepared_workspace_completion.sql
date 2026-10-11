ALTER TABLE workspace_archive_jobs ADD COLUMN prepared BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workspace_archive_jobs ADD CONSTRAINT ck_workspace_prepared_result
    CHECK (NOT prepared OR (status = 'PENDING' AND body_id IS NULL AND result_json IS NOT NULL));
CREATE INDEX ix_workspace_prepared_due ON workspace_archive_jobs(next_attempt_at, id) WHERE prepared;
