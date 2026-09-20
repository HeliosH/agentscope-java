-- H2 equivalent of durable task recovery metadata.
ALTER TABLE task_nodes ADD COLUMN recovery_phase VARCHAR(32) NOT NULL DEFAULT 'NONE';
ALTER TABLE task_nodes ADD COLUMN recovery_reason VARCHAR(128);
ALTER TABLE task_nodes ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE task_nodes ADD COLUMN next_recovery_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE task_nodes ADD COLUMN recovery_checkpoint_id UUID;
ALTER TABLE task_nodes ADD CONSTRAINT fk_task_nodes_recovery_checkpoint
    FOREIGN KEY (recovery_checkpoint_id) REFERENCES context_checkpoints(id) ON DELETE SET NULL;
ALTER TABLE task_nodes ADD CONSTRAINT ck_task_nodes_recovery_count CHECK (recovery_count >= 0);
ALTER TABLE task_nodes ADD CONSTRAINT ck_task_nodes_recovery_phase
    CHECK (recovery_phase IN ('NONE', 'SCHEDULED', 'RUNNING', 'COMPLETED', 'TERMINAL'));

CREATE INDEX ix_task_nodes_recovery_ready
    ON task_nodes(recovery_phase, status, next_recovery_at, priority DESC, created_at);
CREATE INDEX ix_task_nodes_recovery_checkpoint ON task_nodes(recovery_checkpoint_id);
