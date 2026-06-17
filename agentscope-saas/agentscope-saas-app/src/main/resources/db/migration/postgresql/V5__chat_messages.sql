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

-- Chat message history (Phase A). Stores every user and assistant message of a conversation so a
-- user can resume a session after refresh/re-login. Rows are scoped by org/user for isolation
-- (the service layer filters by org_id + user_id; RLS is a later-phase defense-in-depth).
-- content stores the plain text of the message (assistant tool calls are surfaced via the live
-- stream; the persisted text is the final assistant reply).

CREATE TABLE chat_messages (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID NOT NULL,
    user_id     UUID NOT NULL,
    session_id  UUID NOT NULL REFERENCES chat_sessions(id),
    agent_id    UUID NOT NULL,
    role        VARCHAR(16) NOT NULL,
    content     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_messages_session ON chat_messages(session_id, created_at);
CREATE INDEX ix_messages_org_user ON chat_messages(org_id, user_id);
