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

-- Phase F4: harden the RLS policies from V6/V8 against the empty-string GUC case.
--
-- The original policies used `current_setting('app.current_org', true)::uuid` directly. The
-- TenantAwareDataSourceProxy sets the GUC to the empty string ('') when TenantContextHolder has no
-- org (login/register, system calls, Flyway). `SET app.current_org = ''` stores the empty string —
-- it does NOT unset the GUC, so current_setting returns '' (not NULL), and `''::uuid` throws
-- "invalid input syntax for type uuid: """ on every tenant-table query. RESET is no better for a
-- custom GUC: its default is also ''.
--
-- Wrapping the setting in NULLIF(..., '') collapses both the unset (NULL) and empty-string ('')
-- cases to NULL, which casts to NULL and matches no rows (safe deny) rather than throwing. Valid
-- UUIDs pass through untouched. This is defense-in-depth at the policy layer: the security boundary
-- stays correct regardless of how the GUC is set by the runtime or any future caller.

DROP POLICY IF EXISTS org_isolation ON orgs;
CREATE POLICY org_isolation ON orgs
    USING (id = NULLIF(current_setting('app.current_org', true), '')::uuid);

DROP POLICY IF EXISTS org_isolation ON users;
CREATE POLICY org_isolation ON users
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

DROP POLICY IF EXISTS org_isolation ON agents;
CREATE POLICY org_isolation ON agents
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

DROP POLICY IF EXISTS org_isolation ON chat_sessions;
CREATE POLICY org_isolation ON chat_sessions
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

DROP POLICY IF EXISTS org_isolation ON chat_messages;
CREATE POLICY org_isolation ON chat_messages
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

DROP POLICY IF EXISTS org_isolation ON sandboxes;
CREATE POLICY org_isolation ON sandboxes
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

DROP POLICY IF EXISTS org_isolation ON usage_records;
CREATE POLICY org_isolation ON usage_records
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

DROP POLICY IF EXISTS org_isolation ON audit_logs;
CREATE POLICY org_isolation ON audit_logs
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

DROP POLICY IF EXISTS org_isolation ON marketplaces;
CREATE POLICY org_isolation ON marketplaces
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
