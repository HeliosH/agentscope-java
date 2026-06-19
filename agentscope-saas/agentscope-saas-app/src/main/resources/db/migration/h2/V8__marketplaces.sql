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

-- H2 variant of V8: org-scoped skill marketplaces. H2 has a native JSON type that Hibernate's
-- SqlTypes.JSON maps to, matching the PostgreSQL jsonb column; H2 has no RLS so no policy is added.

CREATE TABLE marketplaces (
    id             UUID PRIMARY KEY,
    org_id         UUID NOT NULL,
    marketplace_id VARCHAR(64) NOT NULL,
    type           VARCHAR(16) NOT NULL,
    properties     JSON NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (org_id, marketplace_id)
);
CREATE INDEX ix_marketplaces_org ON marketplaces(org_id);
