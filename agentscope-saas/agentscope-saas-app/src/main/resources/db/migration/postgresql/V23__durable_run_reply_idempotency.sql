--
-- Copyright 2024-2026 the original author or authors.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--      http://www.apache.org/licenses/LICENSE-2.0
--

ALTER TABLE chat_messages ADD COLUMN source_run_id UUID REFERENCES assistant_runs(id);
CREATE UNIQUE INDEX ux_chat_messages_source_run_id
    ON chat_messages(source_run_id);
