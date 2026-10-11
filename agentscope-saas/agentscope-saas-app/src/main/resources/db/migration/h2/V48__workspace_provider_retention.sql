ALTER TABLE sandboxes ADD COLUMN retention_held BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sandbox_leases ADD COLUMN retention_held BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workspace_archive_jobs ALTER COLUMN body_id DROP NOT NULL;
ALTER TABLE workspace_archive_jobs ADD COLUMN result_json TEXT;
ALTER TABLE workspace_archive_jobs ADD CONSTRAINT ck_archive_result_source CHECK
    ((body_id IS NOT NULL AND result_json IS NULL) OR
     (body_id IS NULL AND ((result_json IS NOT NULL AND CHAR_LENGTH(result_json) <= 1048576)
       OR (result_json IS NULL AND status IN ('COMPLETED','FAILED','CANCELLED')))));
CREATE INDEX ix_workspace_archive_inline_quota ON workspace_archive_jobs(org_id, user_id);
CREATE TABLE workspace_provider_retentions (
    source_id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    manifest_id UUID NOT NULL UNIQUE,
    tracking_id UUID NOT NULL,
    sandbox_lease_id UUID NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    resource_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('STAGING', 'RETAINED', 'CLEANING', 'CLEANED')),
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    FOREIGN KEY (source_id, org_id, user_id) REFERENCES workspace_sources(id, org_id, user_id),
    CHECK ((token IS NULL AND lease_until IS NULL) OR (token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX ix_workspace_retention_due ON workspace_provider_retentions(status, deadline_at, lease_until);
CREATE INDEX ix_workspace_retention_quota ON workspace_provider_retentions(org_id, status);
