--
-- Copyright 2024-2026 the original author or authors.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--      http://www.apache.org/licenses/LICENSE-2.0
--

-- Durable deletion queue. Metadata is removed transactionally only after every object key has
-- been queued; physical object deletion can then retry independently of MinIO availability.
CREATE TABLE file_object_gc_queue (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL,
    object_key      VARCHAR(1024) NOT NULL,
    storage_backend VARCHAR(32) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'pending',
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      VARCHAR(2000),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_file_gc_status ON file_object_gc_queue(status, attempts, created_at);
CREATE INDEX ix_file_gc_object ON file_object_gc_queue(org_id, object_key);
