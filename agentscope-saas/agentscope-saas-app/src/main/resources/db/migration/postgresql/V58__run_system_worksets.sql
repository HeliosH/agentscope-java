CREATE TABLE run_system_manifests (
    run_id UUID PRIMARY KEY REFERENCES assistant_runs(id) ON DELETE CASCADE,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    session_id UUID NOT NULL,
    session_generation BIGINT NOT NULL CHECK (session_generation >= 0),
    version VARCHAR(128) NOT NULL,
    file_count INTEGER NOT NULL CHECK (file_count >= 0),
    total_bytes BIGINT NOT NULL CHECK (total_bytes >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_run_system_manifests_org ON run_system_manifests(org_id,run_id);
CREATE TABLE run_system_entries (
    run_id UUID NOT NULL REFERENCES run_system_manifests(run_id) ON DELETE CASCADE,
    org_id UUID NOT NULL,
    logical_path VARCHAR(1024) NOT NULL,
    publication_id UUID NOT NULL REFERENCES file_publications(id),
    backend VARCHAR(32) NOT NULL,
    object_key VARCHAR(2048) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY(run_id,logical_path)
);
CREATE INDEX ix_run_system_entries_publication ON run_system_entries(publication_id);
CREATE INDEX ix_run_system_entries_org ON run_system_entries(org_id,run_id);
ALTER TABLE run_system_manifests ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_system_manifests FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON run_system_manifests
    USING (org_id = (SELECT NULLIF(current_setting('app.current_org',true),'')::uuid))
    WITH CHECK (org_id = (SELECT NULLIF(current_setting('app.current_org',true),'')::uuid));
ALTER TABLE run_system_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_system_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON run_system_entries
    USING (org_id = (SELECT NULLIF(current_setting('app.current_org',true),'')::uuid))
    WITH CHECK (org_id = (SELECT NULLIF(current_setting('app.current_org',true),'')::uuid));
REVOKE ALL ON run_system_manifests,run_system_entries FROM app;
GRANT SELECT,INSERT ON run_system_manifests,run_system_entries TO app;
