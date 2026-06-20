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

-- Phase F6: extend agent / session / message tables for the full paw AgentDefinition shape and
-- the paw session-inbox / turn-transcript fields. Mirrors the H2 V11 migration with PostgreSQL
-- types (JSONB, TIMESTAMPTZ, NOW()). The agents table is already RLS-covered (V6); new columns
-- inherit the ENABLE+FORCE policies, and V10 already granted the app role DML on the table, so
-- the new columns are writable by the non-superuser app role without further grants.

ALTER TABLE agents ADD COLUMN description    TEXT;
ALTER TABLE agents ADD COLUMN sys_prompt     TEXT;
ALTER TABLE agents ADD COLUMN max_iters      INTEGER;
ALTER TABLE agents ADD COLUMN tools          JSONB;
ALTER TABLE agents ADD COLUMN workspace_path VARCHAR(512);
ALTER TABLE agents ADD COLUMN builtin        BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE agents ADD COLUMN updated_at     TIMESTAMPTZ DEFAULT NOW();
UPDATE agents SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE chat_sessions ADD COLUMN label        VARCHAR(255);
ALTER TABLE chat_sessions ADD COLUMN unread       BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE chat_sessions ADD COLUMN last_message  VARCHAR(2000);

ALTER TABLE chat_messages ADD COLUMN parent_id   UUID;
ALTER TABLE chat_messages ADD COLUMN tool_name   VARCHAR(128);
ALTER TABLE chat_messages ADD COLUMN tool_input  JSONB;
ALTER TABLE chat_messages ADD COLUMN tool_result JSONB;
