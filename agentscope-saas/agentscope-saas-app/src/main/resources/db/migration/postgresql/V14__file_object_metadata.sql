--
-- Copyright 2024-2026 the original author or authors.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--      http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- Enterprise file catalog. PostgreSQL remains the authority for metadata, permissions, versions,
-- audit, and lookup. File bytes live in object storage (MinIO/S3 in production); the
-- file_object_blobs table is a PostgreSQL BYTEA fallback for local/dev environments.

CREATE TABLE files (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             UUID NOT NULL,
    user_id            UUID NOT NULL,
    agent_id           UUID,
    session_id         UUID,
    logical_path       VARCHAR(1024) NOT NULL,
    current_version_id UUID,
    source             VARCHAR(64) NOT NULL,
    status             VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, user_id, logical_path)
);
CREATE INDEX ix_files_org_user_path ON files(org_id, user_id, logical_path);
CREATE INDEX ix_files_org_user_updated ON files(org_id, user_id, updated_at DESC);

CREATE TABLE file_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id         UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    org_id          UUID NOT NULL,
    user_id         UUID NOT NULL,
    agent_id        UUID,
    session_id      UUID,
    version_no      BIGINT NOT NULL,
    object_key      VARCHAR(1024) NOT NULL,
    storage_backend VARCHAR(32) NOT NULL,
    content_type    VARCHAR(255),
    size_bytes      BIGINT NOT NULL,
    sha256          VARCHAR(64) NOT NULL,
    source          VARCHAR(64) NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (file_id, version_no)
);
CREATE INDEX ix_file_versions_org_user_created ON file_versions(org_id, user_id, created_at DESC);
CREATE INDEX ix_file_versions_file_created ON file_versions(file_id, created_at DESC);
CREATE INDEX ix_file_versions_sha256 ON file_versions(sha256);

CREATE TABLE file_object_blobs (
    object_key   VARCHAR(1024) PRIMARY KEY,
    org_id       UUID NOT NULL,
    content_type VARCHAR(255),
    size_bytes   BIGINT NOT NULL,
    sha256       VARCHAR(64) NOT NULL,
    data         BYTEA NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_file_object_blobs_org ON file_object_blobs(org_id);

ALTER TABLE files ENABLE ROW LEVEL SECURITY;
ALTER TABLE files FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON files
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE file_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE file_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON file_versions
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE file_object_blobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE file_object_blobs FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON file_object_blobs
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
