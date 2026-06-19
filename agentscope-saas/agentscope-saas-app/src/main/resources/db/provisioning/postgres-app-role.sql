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

-- Provisioning (NOT a Flyway migration): create the non-superuser `app` role the application
-- runtime connects as. This MUST run before first app boot, because the primary Hikari pool
-- validates its first connection (as `app`) at startup — before Flyway runs. Flyway itself
-- connects as the superuser `agentscope` (the @FlywayDataSource admin DataSource) and owns schema
-- changes.
--
-- `app` is a plain LOGIN role: NOT a superuser, NO BYPASSRLS, so PostgreSQL Row-Level Security
-- (V6/V9 policies) applies to every query it runs. The V10 migration grants it DML on all tables.
--
-- Run once as a superuser, e.g.:
--   psql -U agentscope -d agentscope_saas -v app_password=agentscope -f postgres-app-role.sql
-- The password MUST match the SAAS_DB_PASSWORD the app boots with (default "agentscope"). If the
-- role already exists the create is skipped (password left as-is; change it with ALTER ROLE).

-- psql does not substitute :vars inside dollar-quoted bodies, so use \gexec to run a CREATE ROLE
-- only when the role is missing. The query yields a CREATE ROLE statement (one row, one column)
-- when absent, or no rows when present; \gexec executes whatever it yields.
SELECT format(
        'CREATE ROLE app LOGIN PASSWORD %L',
        :'app_password'
       )
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app')
\gexec

GRANT CONNECT ON DATABASE agentscope_saas TO app;
GRANT USAGE ON SCHEMA public TO app;
