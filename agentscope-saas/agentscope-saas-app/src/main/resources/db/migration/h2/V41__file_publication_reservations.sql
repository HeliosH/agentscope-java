CREATE TABLE file_publications (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    agent_id UUID,
    session_id UUID,
    run_id UUID,
    session_generation BIGINT CONSTRAINT ck_file_publications_generation CHECK (session_generation >= 0),
    logical_path VARCHAR(1024) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    backend VARCHAR(32) NOT NULL CONSTRAINT ck_file_publications_backend CHECK (backend IN ('pg', 'minio')),
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CONSTRAINT ck_file_publications_size CHECK (size_bytes >= 0),
    reserved_bytes BIGINT NOT NULL CONSTRAINT ck_file_publications_reserved CHECK (reserved_bytes >= 0),
    owns_object BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL CONSTRAINT ck_file_publications_status CHECK (status IN ('STAGED', 'STORED', 'PUBLISHED', 'ABORTED', 'DELETING', 'DELETED')),
    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
    eligible_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claim_token UUID,
    attempts INTEGER NOT NULL DEFAULT 0 CONSTRAINT ck_file_publications_attempts CHECK (attempts >= 0),
    version_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_file_publications_owner ON file_publications(org_id, user_id, status, lease_until);
CREATE INDEX ix_file_publications_quota ON file_publications(status, org_id, user_id, lease_until);
CREATE INDEX ix_file_publications_path ON file_publications(org_id, user_id, logical_path, lease_until);
CREATE INDEX ix_file_publications_gc ON file_publications(status, eligible_at, id);
CREATE INDEX ix_file_publications_object ON file_publications(org_id, object_key);
