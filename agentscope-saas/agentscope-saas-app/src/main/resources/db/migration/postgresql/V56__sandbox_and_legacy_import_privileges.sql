-- V10 default privileges include table-wide UPDATE/DELETE. Column grants alone do not narrow them.
REVOKE UPDATE, DELETE ON sandbox_runtime_resources, sandbox_usage_refs,
    legacy_archive_imports, legacy_archive_entries FROM app;

GRANT UPDATE(external_id,status,release_token,release_until,release_attempts,updated_at)
    ON sandbox_runtime_resources TO app;
GRANT UPDATE(status,expires_at,updated_at) ON sandbox_usage_refs TO app;

-- Reset removes the import aggregate; the database cascades its immutable source-entry evidence.
GRANT DELETE ON legacy_archive_imports TO app;
GRANT UPDATE(byte_offset,line_no,imported_messages,skipped_entries,digest_chain,status,updated_at)
    ON legacy_archive_imports TO app;
