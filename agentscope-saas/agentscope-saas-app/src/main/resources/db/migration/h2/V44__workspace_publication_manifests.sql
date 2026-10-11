CREATE TABLE workspace_publication_manifests (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    session_id UUID NOT NULL,
    session_generation BIGINT NOT NULL CHECK (session_generation >= 0),
    run_id UUID NOT NULL,
    task_id UUID NOT NULL,
    agent_run_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    lease_owner VARCHAR(255) NOT NULL,
    manifest_seq BIGINT NOT NULL CHECK (manifest_seq > 0),
    plan_hash VARCHAR(64) NOT NULL,
    file_count INTEGER NOT NULL CHECK (file_count >= 0),
    total_bytes BIGINT NOT NULL CHECK (total_bytes >= 0),
    scanned_files INTEGER NOT NULL CHECK (scanned_files >= file_count),
    status VARCHAR(32) NOT NULL CHECK (status IN ('DISCOVERED', 'SCANNED', 'READY')),
    scan_complete BOOLEAN NOT NULL DEFAULT FALSE,
    projected_files INTEGER,
    unchanged_files INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (org_id, run_id, task_id, attempt_id, manifest_seq),
    UNIQUE (id, org_id, user_id)
);
CREATE TABLE workspace_manifest_entries (
    manifest_id UUID NOT NULL,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    source_path VARCHAR(1024) NOT NULL,
    logical_path VARCHAR(1024) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 VARCHAR(64) NOT NULL,
    unchanged BOOLEAN NOT NULL,
    publication_id UUID,
    PRIMARY KEY (manifest_id, logical_path),
    FOREIGN KEY (manifest_id, org_id, user_id)
        REFERENCES workspace_publication_manifests(id, org_id, user_id) ON DELETE CASCADE
);
CREATE INDEX ix_workspace_manifest_entries_owner ON workspace_manifest_entries(org_id, user_id, manifest_id);
CREATE INDEX ix_workspace_manifest_entries_publication ON workspace_manifest_entries(publication_id);
