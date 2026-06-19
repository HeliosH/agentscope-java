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

-- Row-Level Security (Phase F4): defense-in-depth tenant isolation on top of the service-layer
-- org_id filtering (Phase A) and org-scoped storage namespaces (Phase F1). Each tenant-scoped
-- table gets a policy that only exposes rows whose org_id equals the per-connection GUC
-- `app.current_org`, set at request time by TenantRlsConnectionBinder.
--
-- `current_setting('app.current_org', true)` with missing_ok=true returns NULL when unset, so the
-- policy matches no rows (safe default — denies rather than leaks). The application DB role is
-- granted BYPASSRLS-free access; Flyway/migration runs happen before RLS is active or use the
-- table owner (which FORCE ROW LEVEL SECURITY still applies to, so migrations that need to read
-- tenant tables must set the GUC or run as a superuser/BYPASSRLS role). Bootstrap queries (login:
-- resolve org/user by email) set the GUC to the resolved org_id first.
--
-- H2 does not support RLS; this migration is PostgreSQL-only (the H2 variant is a no-op stub).

-- orgs: the org's own row is visible when id = current org.
ALTER TABLE orgs ENABLE ROW LEVEL SECURITY;
ALTER TABLE orgs FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON orgs
    USING (id = current_setting('app.current_org', true)::uuid);

-- tenant-scoped tables: org_id = current org.
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON users
    USING (org_id = current_setting('app.current_org', true)::uuid);

ALTER TABLE agents ENABLE ROW LEVEL SECURITY;
ALTER TABLE agents FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON agents
    USING (org_id = current_setting('app.current_org', true)::uuid);

ALTER TABLE chat_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON chat_sessions
    USING (org_id = current_setting('app.current_org', true)::uuid);

ALTER TABLE chat_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON chat_messages
    USING (org_id = current_setting('app.current_org', true)::uuid);

ALTER TABLE sandboxes ENABLE ROW LEVEL SECURITY;
ALTER TABLE sandboxes FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON sandboxes
    USING (org_id = current_setting('app.current_org', true)::uuid);

ALTER TABLE usage_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_records FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON usage_records
    USING (org_id = current_setting('app.current_org', true)::uuid);

ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON audit_logs
    USING (org_id = current_setting('app.current_org', true)::uuid);

-- tier_policies is global (not tenant-scoped): no RLS policy.
