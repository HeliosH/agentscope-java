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

-- Phase F4 activation: grant the non-superuser `app` runtime role DML on all tables/sequences so it
-- can serve tenant queries under Row-Level Security (V6/V9). The `app` role itself is created by the
-- provisioning script (db/provisioning/postgres-app-role.sql), which must run before first boot.
-- This migration runs as the superuser `agentscope` (Flyway admin connection) and is idempotent.
-- ALTER DEFAULT PRIVILEGES covers tables added by future migrations created by the agentscope role.

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app;

ALTER DEFAULT PRIVILEGES FOR ROLE agentscope IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app;
ALTER DEFAULT PRIVILEGES FOR ROLE agentscope IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO app;
