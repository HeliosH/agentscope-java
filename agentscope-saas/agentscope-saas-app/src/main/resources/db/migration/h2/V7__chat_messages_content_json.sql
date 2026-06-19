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

-- H2 version of V7: same column rename, JSONB replaced with TEXT (H2 has no native JSONB).
ALTER TABLE chat_messages ADD COLUMN content_json TEXT;
UPDATE chat_messages
   SET content_json = CONCAT('[{"type":"text","text":', QUOTE(content), '}]');
ALTER TABLE chat_messages DROP COLUMN content;
ALTER TABLE chat_messages ALTER COLUMN content_json SET NOT NULL;
