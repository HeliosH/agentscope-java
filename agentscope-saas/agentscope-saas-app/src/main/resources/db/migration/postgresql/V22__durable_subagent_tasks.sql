-- Durable bridge for Harness background subagent tasks.

ALTER TABLE task_nodes
    ADD COLUMN external_task_id VARCHAR(255),
    ADD COLUMN sub_session_id VARCHAR(255),
    ADD COLUMN delivered_at TIMESTAMPTZ;

CREATE UNIQUE INDEX ux_task_nodes_run_external_task
    ON task_nodes(run_id, external_task_id)
    WHERE external_task_id IS NOT NULL;

CREATE INDEX ix_task_nodes_external_delivery
    ON task_nodes(org_id, external_task_id, delivered_at, completed_at);
