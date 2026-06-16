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

-- Durable sandbox workspace snapshots. Stores the tar archive of a user's sandbox workspace
-- (files, MEMORY.md, etc.) keyed by the sandbox sessionId. PgRemoteSnapshotClient writes here on
-- sandbox stop and reads on resume, so user memory survives sandbox TTL eviction and restarts.

CREATE TABLE agentscope_sandbox_snapshots (
    snapshot_id  VARCHAR(512) PRIMARY KEY,
    data         BYTEA NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
