-- H2 counterpart of organization-managed model endpoints. H2 local tests do not implement RLS.
CREATE TABLE model_definitions (
    id                    UUID PRIMARY KEY,
    org_id                UUID NOT NULL,
    model_id              VARCHAR(64) NOT NULL,
    display_name          VARCHAR(128) NOT NULL,
    provider_type         VARCHAR(24) NOT NULL,
    base_url              VARCHAR(1024),
    api_key_ciphertext    CLOB,
    model_name            VARCHAR(255) NOT NULL,
    context_window_tokens INTEGER NOT NULL CHECK (context_window_tokens > 0),
    max_output_tokens     INTEGER NOT NULL CHECK (max_output_tokens > 0),
    safety_margin_tokens  INTEGER NOT NULL CHECK (safety_margin_tokens >= 0),
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    default_model         BOOLEAN NOT NULL DEFAULT FALSE,
    version               BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_model_definitions_org_model UNIQUE (org_id, model_id),
    CONSTRAINT ck_model_definitions_budget
        CHECK (context_window_tokens > max_output_tokens + safety_margin_tokens)
);

CREATE INDEX ix_model_definitions_org_model ON model_definitions(org_id, model_id);
