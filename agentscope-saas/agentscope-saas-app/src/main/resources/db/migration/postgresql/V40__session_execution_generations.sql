ALTER TABLE chat_sessions
    ADD COLUMN execution_generation BIGINT NOT NULL DEFAULT 0 CHECK (execution_generation >= 0);
ALTER TABLE assistant_runs
    ADD COLUMN session_generation BIGINT NOT NULL DEFAULT 0 CHECK (session_generation >= 0);

CREATE INDEX idx_runs_session_generation
    ON assistant_runs(org_id, session_id, session_generation);
