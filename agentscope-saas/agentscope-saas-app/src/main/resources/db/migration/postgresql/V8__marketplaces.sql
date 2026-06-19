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

-- Phase F5: org-scoped skill marketplaces. Unlike the desktop paw variant (which stores a global
-- marketplaces map in a single agentscope.json file), the SaaS stores one row per (org, id) so
-- each tenant manages its own git/nacos skill sources. `marketplace_id` is the user-chosen id;
-- `type` is the discriminator ("git"/"nacos"); `properties` holds the type-specific config (incl.
-- credentials) as JSONB. A surrogate UUID `id` is the JPA primary key; (org_id, marketplace_id) is
-- the natural unique key. RLS isolates rows by org_id, matching the other tenant tables.

CREATE TABLE marketplaces (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID NOT NULL,
    marketplace_id VARCHAR(64) NOT NULL,
    type           VARCHAR(16) NOT NULL,
    properties     JSONB NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, marketplace_id)
);
CREATE INDEX ix_marketplaces_org ON marketplaces(org_id);

ALTER TABLE marketplaces ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplaces FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON marketplaces
    USING (org_id = current_setting('app.current_org', true)::uuid);
