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

-- Repair the original PostgreSQL demo seed hash. It did not match the documented
-- verification password ("password"), which broke admin-gated smoke checks on
-- databases that had already applied V2. Only rows still using the old broken
-- demo hash are updated, so manually changed demo passwords are preserved.
UPDATE users
SET password_hash = '$2a$10$WwF9lmAWvZhEf60W/U42zORFlrwF.BFRNoj1nlHJYyLaw/7qpwWJ.'
WHERE email IN ('admin@demo.local', 'alice@demo.local', 'bob@demo.local')
  AND password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';
