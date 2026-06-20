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

-- Phase F6: extend the agent / session / message tables to carry the full paw
-- AgentDefinition shape and the paw session-inbox / turn-transcript fields, so the
-- forked paw frontend works against the SaaS backend without shape translation.
--
-- New columns are nullable / defaulted so existing rows keep working. The agents table is
-- already RLS-covered (V6); new columns inherit the policies. H2-compatible types mirror the
-- PostgreSQL V11 migration (TIMESTAMP WITH TIME ZONE not TIMESTAMPTZ; JSON not JSONB).

-- agents: paw AgentDefinition fields (description / sysPrompt / maxIters / tools / workspacePath /
-- builtin / updatedAt). config + model_config JSONB columns are retained for the harness config.
ALTER TABLE agents ADD COLUMN description   TEXT;
ALTER TABLE agents ADD COLUMN sys_prompt    TEXT;
ALTER TABLE agents ADD COLUMN max_iters     INTEGER;
ALTER TABLE agents ADD COLUMN tools         JSON;
ALTER TABLE agents ADD COLUMN workspace_path VARCHAR(512);
ALTER TABLE agents ADD COLUMN builtin       BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE agents ADD COLUMN updated_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;
UPDATE agents SET updated_at = created_at WHERE updated_at IS NULL;

-- chat_sessions: paw inbox fields (label / unread / last_message preview).
ALTER TABLE chat_sessions ADD COLUMN label       VARCHAR(255);
ALTER TABLE chat_sessions ADD COLUMN unread      BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE chat_sessions ADD COLUMN last_message VARCHAR(2000);

-- chat_messages: paw turn-transcript fields (parent_id for threading; tool_* for tool turns).
ALTER TABLE chat_messages ADD COLUMN parent_id   UUID;
ALTER TABLE chat_messages ADD COLUMN tool_name   VARCHAR(128);
ALTER TABLE chat_messages ADD COLUMN tool_input  JSON;
ALTER TABLE chat_messages ADD COLUMN tool_result JSON;
