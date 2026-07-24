-- Structured worker output is stored on the task; large artifacts remain in object storage.

ALTER TABLE task_nodes
    ADD COLUMN output_json JSONB NOT NULL DEFAULT '{}'::jsonb;
