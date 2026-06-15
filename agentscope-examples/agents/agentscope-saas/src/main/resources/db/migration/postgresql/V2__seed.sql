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

-- Seed tier policies and a demo organization + users for verification.
-- Demo passwords are BCrypt hashes of "password" (cost 10). Change in production.

INSERT INTO tier_policies (tier, max_agents, max_sandboxes, monthly_token_quota, storage_gb, idle_ttl_seconds) VALUES
    ('standard',   5,  1, 2000000,   5,  600),
    ('advanced',   20, 3, 10000000,  20, 3600),
    ('privileged', 50, 5, 50000000,  50, 7200);

INSERT INTO orgs (id, name, slug, status) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Demo Organization', 'demo', 'active');

INSERT INTO users (id, org_id, email, display_name, password_hash, role, tier) VALUES
    ('00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000001',
     'admin@demo.local', 'Demo Admin',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'admin', 'privileged'),
    ('00000000-0000-0000-0000-0000000000a2',
     '00000000-0000-0000-0000-000000000001',
     'alice@demo.local', 'Alice',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'member', 'standard'),
    ('00000000-0000-0000-0000-0000000000a3',
     '00000000-0000-0000-0000-000000000001',
     'bob@demo.local', 'Bob',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'member', 'standard');
