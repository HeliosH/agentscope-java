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

-- Sandbox tracking table (Phase 2). Records active sandbox instances per tenant/user
-- for quota enforcement and operational visibility. The framework's SessionSandboxStateStore
-- persists sandbox *state* (for resume) via AgentStateStore (Redis/JDBC); this table tracks
-- the operational lifecycle (who owns which sandbox, when it expires).

CREATE TABLE sandboxes (
    id            UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id        UUID NOT NULL,
    user_id       UUID NOT NULL,
    agent_id      UUID,
    session_id    VARCHAR(255),
    sandbox_type  VARCHAR(20) NOT NULL DEFAULT 'docker',
    external_id   VARCHAR(255),
    status        VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_used_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX ix_sandboxes_org_user ON sandboxes(org_id, user_id);
CREATE INDEX ix_sandboxes_status ON sandboxes(status);
CREATE INDEX ix_sandboxes_expires ON sandboxes(expires_at) WHERE status = 'active';
