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

-- Durable sandbox workspace snapshots (H2 variant, local profile). See the PostgreSQL migration for
-- the production schema. The local profile keeps sandbox execution disabled by default, so this
-- table is created for schema parity but not exercised by PgRemoteSnapshotClient.

CREATE TABLE agentscope_sandbox_snapshots (
    snapshot_id  VARCHAR(512) PRIMARY KEY,
    -- BYTEA (PostgreSQL binary type) is recognized by H2 in PostgreSQL mode; BLOB is not.
    -- PgRemoteSnapshotClient uses setBytes/getBytes, which work with BYTEA.
    data         BYTEA NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
