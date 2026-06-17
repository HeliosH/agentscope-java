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

-- Chat message history (Phase A) — H2 (local/gateway smoke-test profile).
-- Mirrors the PostgreSQL V5 migration with H2-compatible types.

CREATE TABLE chat_messages (
    id          UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id      UUID NOT NULL,
    user_id     UUID NOT NULL,
    session_id  UUID NOT NULL REFERENCES chat_sessions(id),
    agent_id    UUID NOT NULL,
    role        VARCHAR(16) NOT NULL,
    content     TEXT NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_messages_session ON chat_messages(session_id, created_at);
CREATE INDEX ix_messages_org_user ON chat_messages(org_id, user_id);
