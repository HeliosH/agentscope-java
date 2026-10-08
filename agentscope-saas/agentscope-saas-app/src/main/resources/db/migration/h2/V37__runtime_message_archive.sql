CREATE TABLE runtime_message_streams (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    agent_label VARCHAR(255) NOT NULL,
    session_key VARCHAR(512) NOT NULL,
    last_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    last_message_id VARCHAR(255),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (org_id, session_id, agent_label, session_key)
);
CREATE INDEX ix_runtime_streams_owner ON runtime_message_streams(org_id, user_id, agent_id, updated_at DESC);
CREATE INDEX ix_runtime_streams_session_fk ON runtime_message_streams(session_id);
CREATE TABLE runtime_messages (
    stream_id UUID NOT NULL REFERENCES runtime_message_streams(id) ON DELETE CASCADE,
    org_id UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    seq BIGINT NOT NULL CHECK (seq > 0),
    message_id VARCHAR(255) NOT NULL,
    parent_message_id VARCHAR(255),
    source_run_id UUID,
    source_agent_run_id UUID,
    role VARCHAR(32) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
    payload_json TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    content_bytes BIGINT NOT NULL CHECK (content_bytes > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stream_id, seq), UNIQUE (stream_id, message_id)
);
CREATE INDEX ix_runtime_messages_org_user ON runtime_messages(org_id, user_id, stream_id, seq);
CREATE INDEX ix_runtime_messages_window ON runtime_messages(stream_id, seq, content_bytes);
