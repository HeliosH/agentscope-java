CREATE TABLE task_input_additions (
    task_id UUID NOT NULL REFERENCES task_input_manifests(task_id) ON DELETE CASCADE,
    org_id UUID NOT NULL,
    sequence_no BIGINT NOT NULL CHECK (sequence_no > 0),
    logical_path VARCHAR(1024) NOT NULL,
    file_id UUID NOT NULL,
    version_id UUID NOT NULL REFERENCES file_versions(id),
    backend VARCHAR(32) NOT NULL,
    object_key VARCHAR(2048) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(task_id,sequence_no),
    UNIQUE(task_id,logical_path),
    UNIQUE(task_id,version_id)
);
CREATE INDEX ix_task_input_additions_version ON task_input_additions(version_id);
CREATE INDEX ix_task_input_additions_file ON task_input_additions(file_id);
CREATE INDEX ix_task_input_additions_org ON task_input_additions(org_id,task_id);
