-- Organization-managed model endpoints. Credentials are AES-GCM ciphertext produced by the app.
CREATE TABLE model_definitions (
    id                    UUID PRIMARY KEY,
    org_id                UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    model_id              VARCHAR(64) NOT NULL,
    display_name          VARCHAR(128) NOT NULL,
    provider_type         VARCHAR(24) NOT NULL,
    base_url              VARCHAR(1024),
    api_key_ciphertext    TEXT,
    model_name            VARCHAR(255) NOT NULL,
    context_window_tokens INTEGER NOT NULL CHECK (context_window_tokens > 0),
    max_output_tokens     INTEGER NOT NULL CHECK (max_output_tokens > 0),
    safety_margin_tokens  INTEGER NOT NULL CHECK (safety_margin_tokens >= 0),
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    default_model         BOOLEAN NOT NULL DEFAULT FALSE,
    version               BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_model_definitions_org_model UNIQUE (org_id, model_id),
    CONSTRAINT ck_model_definitions_budget
        CHECK (context_window_tokens > max_output_tokens + safety_margin_tokens)
);

CREATE INDEX ix_model_definitions_org_model ON model_definitions(org_id, model_id);
CREATE UNIQUE INDEX uk_model_definitions_org_default
    ON model_definitions(org_id) WHERE default_model AND enabled;

ALTER TABLE model_definitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE model_definitions FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON model_definitions
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid)
    WITH CHECK (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
