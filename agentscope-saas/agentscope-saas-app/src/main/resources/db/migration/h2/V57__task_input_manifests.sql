CREATE TABLE task_input_manifests (
    task_id UUID PRIMARY KEY REFERENCES task_nodes(id) ON DELETE CASCADE,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    run_id UUID NOT NULL,
    session_id UUID NOT NULL,
    session_generation BIGINT NOT NULL CHECK (session_generation >= 0),
    version VARCHAR(128) NOT NULL,
    file_count INTEGER NOT NULL CHECK (file_count >= 0),
    total_bytes BIGINT NOT NULL CHECK (total_bytes >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_task_input_manifests_owner ON task_input_manifests(org_id,user_id,run_id);
CREATE TABLE task_input_entries (
    task_id UUID NOT NULL REFERENCES task_input_manifests(task_id) ON DELETE CASCADE,
    org_id UUID NOT NULL,
    logical_path VARCHAR(1024) NOT NULL,
    file_id UUID NOT NULL,
    version_id UUID NOT NULL REFERENCES file_versions(id),
    attachment_id UUID,
    source_task_id UUID,
    backend VARCHAR(32) NOT NULL,
    object_key VARCHAR(2048) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY(task_id,logical_path)
);
CREATE INDEX ix_task_input_entries_version ON task_input_entries(version_id);
CREATE INDEX ix_task_input_entries_file ON task_input_entries(file_id);
CREATE INDEX ix_task_input_entries_org ON task_input_entries(org_id,task_id);
CREATE INDEX ix_run_artifacts_version_pin ON run_artifacts(file_version_id);
CREATE INDEX ix_run_artifacts_file_pin ON run_artifacts(file_id);
