CREATE TABLE workspace_archive_jobs (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    session_id UUID NOT NULL,
    session_generation BIGINT NOT NULL CHECK (session_generation >= 0),
    run_id UUID NOT NULL,
    task_id UUID NOT NULL,
    agent_run_id UUID NOT NULL,
    attempt_id UUID NOT NULL UNIQUE,
    original_owner VARCHAR(255) NOT NULL,
    manifest_id UUID NOT NULL UNIQUE,
    body_id UUID NOT NULL,
    body_bytes BIGINT NOT NULL CHECK (body_bytes > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, org_id, user_id),
    CHECK ((token IS NULL AND lease_until IS NULL) OR (token IS NOT NULL AND lease_until IS NOT NULL))
);
-- No cascading parent Run/session FK: abandoned IO and its bounded retention remain discoverable.
CREATE TABLE workspace_archive_items (
    job_id UUID NOT NULL,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    logical_path VARCHAR(1024) NOT NULL,
    publication_id UUID NOT NULL,
    PRIMARY KEY (job_id, logical_path),
    UNIQUE (job_id, publication_id),
    FOREIGN KEY (job_id, org_id, user_id) REFERENCES workspace_archive_jobs(id, org_id, user_id) ON DELETE CASCADE
);
CREATE INDEX ix_workspace_archive_due ON workspace_archive_jobs(status, next_attempt_at, lease_until, deadline_at);
CREATE INDEX ix_workspace_archive_body ON workspace_archive_jobs(body_id);
CREATE INDEX ix_workspace_archive_owner ON workspace_archive_jobs(org_id, user_id, run_id);
CREATE INDEX ix_workspace_archive_publication ON workspace_archive_items(publication_id);
