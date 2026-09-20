-- Durable recovery metadata for task attempts resumed by another worker or application instance.
ALTER TABLE task_nodes
    ADD COLUMN recovery_phase VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN recovery_reason VARCHAR(128),
    ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_recovery_at TIMESTAMPTZ,
    ADD COLUMN recovery_checkpoint_id UUID REFERENCES context_checkpoints(id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_task_nodes_recovery_count CHECK (recovery_count >= 0),
    ADD CONSTRAINT ck_task_nodes_recovery_phase
        CHECK (recovery_phase IN ('NONE', 'SCHEDULED', 'RUNNING', 'COMPLETED', 'TERMINAL'));

CREATE INDEX ix_task_nodes_recovery_ready
    ON task_nodes(next_recovery_at, priority DESC, created_at)
    WHERE recovery_phase = 'SCHEDULED' AND status = 'READY';

CREATE INDEX ix_task_nodes_recovery_checkpoint
    ON task_nodes(recovery_checkpoint_id)
    WHERE recovery_checkpoint_id IS NOT NULL;
