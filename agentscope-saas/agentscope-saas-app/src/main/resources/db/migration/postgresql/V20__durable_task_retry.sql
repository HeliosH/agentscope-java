-- Retry scheduling metadata for durable task leases.

ALTER TABLE task_nodes
    ADD COLUMN retry_mode VARCHAR(32) NOT NULL DEFAULT 'IDEMPOTENT',
    ADD COLUMN retry_base_seconds INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(128),
    ADD COLUMN last_error_message VARCHAR(2000);

CREATE INDEX ix_task_nodes_ready_schedule
    ON task_nodes(status, next_attempt_at, priority DESC, created_at);
