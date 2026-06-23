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

-- Durable source ledger for memory projection events. Mem0/vector memory is a derived index; this
-- table is the replayable and auditable source of truth for enterprise long-term memory writes.

CREATE TABLE memory_events (
    id            UUID PRIMARY KEY,
    org_id        UUID NOT NULL,
    user_id       UUID NOT NULL,
    agent_id      VARCHAR(255) NOT NULL,
    session_id    VARCHAR(255),
    source        VARCHAR(64) NOT NULL,
    event_type    VARCHAR(64) NOT NULL,
    content_json  JSONB NOT NULL,
    metadata_json JSONB,
    sync_status   VARCHAR(20) NOT NULL DEFAULT 'pending',
    sync_attempts INTEGER NOT NULL DEFAULT 0,
    synced_at     TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_memory_events_org_user ON memory_events(org_id, user_id, created_at DESC);
CREATE INDEX ix_memory_events_sync_status ON memory_events(sync_status, created_at);
CREATE INDEX ix_memory_events_session ON memory_events(session_id);

ALTER TABLE memory_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE memory_events FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON memory_events
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON memory_events TO app;
