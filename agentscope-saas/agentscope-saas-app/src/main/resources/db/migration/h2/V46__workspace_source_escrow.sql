ALTER TABLE workspace_archive_items ALTER COLUMN publication_id DROP NOT NULL;
CREATE TABLE workspace_sources (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    manifest_id UUID NOT NULL UNIQUE,
    object_key VARCHAR(1024) NOT NULL UNIQUE,
    backend VARCHAR(32) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('STAGING', 'RETAINED', 'COLLECTING', 'COLLECTED')),
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    eligible_at TIMESTAMP WITH TIME ZONE NOT NULL,
    token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (id, org_id, user_id),
    FOREIGN KEY (manifest_id, org_id, user_id) REFERENCES workspace_publication_manifests(id, org_id, user_id),
    CHECK ((token IS NULL AND lease_until IS NULL) OR (token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX ix_workspace_sources_due ON workspace_sources(eligible_at, lease_until);
CREATE INDEX ix_workspace_sources_quota ON workspace_sources(org_id, status, deadline_at);
CREATE TABLE workspace_source_files (
    source_id UUID NOT NULL,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    logical_path VARCHAR(1024) NOT NULL,
    base_file_id UUID,
    base_version_id UUID,
    base_status VARCHAR(32),
    PRIMARY KEY (source_id, logical_path),
    FOREIGN KEY (source_id, org_id, user_id) REFERENCES workspace_sources(id, org_id, user_id) ON DELETE CASCADE
);
