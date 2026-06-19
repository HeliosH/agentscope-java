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

-- Phase F5: upgrade chat_messages.content (plain TEXT) to content_json (JSONB) holding the
-- structured content blocks (text + tool calls + reasoning) for faithful history replay. Existing
-- rows are migrated by wrapping their text in a single TextBlock JSON array. The column is renamed
-- so readers can't silently fall back to plain text.

ALTER TABLE chat_messages ADD COLUMN content_json JSONB;
UPDATE chat_messages
   SET content_json = jsonb_build_array(jsonb_build_object('text', content, 'type', 'text'));
ALTER TABLE chat_messages DROP COLUMN content;
ALTER TABLE chat_messages ALTER COLUMN content_json SET NOT NULL;
