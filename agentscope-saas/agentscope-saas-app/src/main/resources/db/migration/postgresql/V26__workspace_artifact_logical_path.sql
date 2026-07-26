-- Make the artifact's restore path a first-class queryable attribute.

ALTER TABLE run_artifacts
    ADD COLUMN logical_path VARCHAR(2048);

UPDATE run_artifacts
   SET logical_path = evidence_json ->> 'logicalPath'
 WHERE artifact_type = 'WORKSPACE_FILE'
   AND logical_path IS NULL;

CREATE INDEX ix_run_artifacts_attempt_path
    ON run_artifacts(org_id, attempt_id, logical_path);
