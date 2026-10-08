ALTER TABLE file_publications ADD COLUMN recoverable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE file_publications ADD COLUMN recovery_token UUID;
ALTER TABLE file_publications ADD COLUMN recovery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (recovery_attempts >= 0);
ALTER TABLE file_publications ADD COLUMN recovery_due_at TIMESTAMPTZ;
ALTER TABLE file_publications ADD COLUMN recovery_deadline_at TIMESTAMPTZ;
CREATE INDEX ix_file_publications_recovery ON file_publications(recovery_due_at, lease_until, id)
    WHERE recoverable AND status IN ('STAGED', 'STORED');

CREATE TABLE file_publication_intents (
    publication_id UUID PRIMARY KEY REFERENCES file_publications(id) ON DELETE CASCADE,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    base_file_id UUID,
    base_version_id UUID,
    base_status VARCHAR(32),
    content_type VARCHAR(255),
    source VARCHAR(64) NOT NULL,
    metadata_json JSONB NOT NULL,
    task_id UUID,
    agent_run_id UUID,
    attempt_id UUID,
    lease_owner VARCHAR(255),
    raw_write_required BOOLEAN NOT NULL,
    CONSTRAINT ck_publication_execution_scope CHECK (
        (task_id IS NULL AND agent_run_id IS NULL AND attempt_id IS NULL AND lease_owner IS NULL)
        OR (task_id IS NOT NULL AND agent_run_id IS NOT NULL AND attempt_id IS NOT NULL AND lease_owner IS NOT NULL))
);
CREATE INDEX ix_file_publication_intents_owner ON file_publication_intents(org_id, user_id);
ALTER TABLE file_publication_intents ENABLE ROW LEVEL SECURITY;
ALTER TABLE file_publication_intents FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON file_publication_intents
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid)
    WITH CHECK (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON file_publication_intents TO app;
