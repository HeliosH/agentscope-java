CREATE TABLE legacy_archive_imports (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    session_generation BIGINT NOT NULL CHECK (session_generation >= 0),
    format_version INTEGER NOT NULL CHECK (format_version = 1),
    source_version_id UUID NOT NULL REFERENCES file_versions(id),
    source_sha256 VARCHAR(64) NOT NULL CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    source_bytes BIGINT NOT NULL CHECK (source_bytes >= 0),
    byte_offset BIGINT NOT NULL DEFAULT 0 CHECK (byte_offset >= 0 AND byte_offset <= source_bytes),
    line_no BIGINT NOT NULL DEFAULT 0 CHECK (line_no >= 0),
    imported_messages BIGINT NOT NULL DEFAULT 0 CHECK (imported_messages >= 0),
    skipped_entries BIGINT NOT NULL DEFAULT 0 CHECK (skipped_entries >= 0),
    digest_chain VARCHAR(64) NOT NULL CHECK (digest_chain ~ '^[a-f0-9]{64}$'),
    status VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'COMPLETE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, user_id, session_id, source_version_id),
    CHECK (imported_messages + skipped_entries = line_no),
    CHECK (status <> 'COMPLETE' OR byte_offset = source_bytes)
);
CREATE INDEX ix_legacy_import_version ON legacy_archive_imports(source_version_id);
CREATE INDEX ix_legacy_import_session ON legacy_archive_imports(session_id);
CREATE INDEX ix_legacy_import_user ON legacy_archive_imports(user_id);
CREATE INDEX ix_legacy_import_agent ON legacy_archive_imports(agent_id);
CREATE TABLE legacy_archive_entries (
    job_id UUID NOT NULL REFERENCES legacy_archive_imports(id) ON DELETE CASCADE,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    line_no BIGINT NOT NULL CHECK (line_no > 0),
    byte_start BIGINT NOT NULL CHECK (byte_start >= 0),
    byte_end BIGINT NOT NULL CHECK (byte_end > byte_start),
    line_sha256 VARCHAR(64) NOT NULL,
    source_entry_id VARCHAR(255),
    parent_entry_id VARCHAR(255),
    tool_call_id VARCHAR(255),
    entry_type VARCHAR(32) NOT NULL,
    synthetic_identity BOOLEAN NOT NULL,
    message_id VARCHAR(255),
    archive_seq BIGINT,
    content_hash VARCHAR(64),
    PRIMARY KEY (job_id, line_no),
    CHECK ((message_id IS NULL AND archive_seq IS NULL AND content_hash IS NULL)
        OR (message_id IS NOT NULL AND archive_seq > 0 AND content_hash IS NOT NULL))
);
ALTER TABLE legacy_archive_imports ENABLE ROW LEVEL SECURITY;
ALTER TABLE legacy_archive_imports FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON legacy_archive_imports
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid)
    WITH CHECK (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
ALTER TABLE legacy_archive_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE legacy_archive_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON legacy_archive_entries
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid)
    WITH CHECK (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
GRANT SELECT, INSERT ON legacy_archive_imports, legacy_archive_entries TO app;
GRANT DELETE ON legacy_archive_imports TO app;
GRANT UPDATE(byte_offset, line_no, imported_messages, skipped_entries, digest_chain, status, updated_at)
    ON legacy_archive_imports TO app;
