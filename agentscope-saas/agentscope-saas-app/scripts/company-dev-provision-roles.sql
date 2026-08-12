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

-- One-time provisioning for the company-dev profile.
--
-- Flyway migrations reference two roles that the default profile expects to
-- pre-exist:
--   * `app`         - V10/V12 do  GRANT ... TO app
--   * `agentscope`  - V10 does    ALTER DEFAULT PRIVILEGES FOR ROLE agentscope
--
-- The company-dev profile connects as the `comac` superuser for BOTH the
-- primary and admin/Flyway datasources, so neither `app` nor `agentscope` is
-- used for connections. They only need to EXIST as roles for the migrations to
-- compile. Hence NOLOGIN (no dummy password, no login surface).
--
-- Run once as a superuser (comac) via psql / pgAdmin / DBeaver, e.g.:
--   PGPASSWORD='Comac@2025' psql -h 192.168.6.15 -U comac -d <db-name> \
--     -f agentscope-saas/agentscope-saas-app/scripts/company-dev-provision-roles.sql
--
-- Idempotent: safe to re-run. start-company-dev.sh runs this automatically
-- when psql is on PATH.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app') THEN
        EXECUTE 'CREATE ROLE app NOLOGIN';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentscope') THEN
        EXECUTE 'CREATE ROLE agentscope NOLOGIN';
    END IF;
END
$$;
