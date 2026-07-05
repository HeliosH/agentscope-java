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

ALTER TABLE chat_messages ADD COLUMN seq BIGINT;

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at, id) AS rn
      FROM chat_messages
)
UPDATE chat_messages m
   SET seq = ranked.rn
  FROM ranked
 WHERE m.id = ranked.id;

ALTER TABLE chat_messages ALTER COLUMN seq SET NOT NULL;
CREATE UNIQUE INDEX ux_chat_messages_session_seq ON chat_messages(session_id, seq);
CREATE INDEX ix_chat_messages_session_seq_desc ON chat_messages(session_id, seq DESC);
CREATE INDEX ix_chat_messages_org_user_session_seq
    ON chat_messages(org_id, user_id, session_id, seq);

CREATE TABLE file_attachments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL,
    user_id         UUID NOT NULL,
    agent_id        UUID,
    session_id      UUID,
    message_id      UUID,
    task_id         UUID,
    file_id         UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    file_version_id UUID NOT NULL REFERENCES file_versions(id) ON DELETE CASCADE,
    kind            VARCHAR(64) NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_file_attachments_org_user_session
    ON file_attachments(org_id, user_id, session_id, created_at DESC);
CREATE INDEX ix_file_attachments_message ON file_attachments(message_id);
CREATE INDEX ix_file_attachments_task ON file_attachments(task_id);
CREATE INDEX ix_file_attachments_file ON file_attachments(file_id);

ALTER TABLE file_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE file_attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON file_attachments
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
