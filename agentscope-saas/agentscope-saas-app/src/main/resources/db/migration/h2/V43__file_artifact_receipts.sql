ALTER TABLE file_publications ADD COLUMN artifact_id UUID;
ALTER TABLE file_publications ADD COLUMN receipt_event_id UUID;
ALTER TABLE file_publications ADD CONSTRAINT ck_file_publications_receipt CHECK (
    (artifact_id IS NULL AND receipt_event_id IS NULL)
    OR (artifact_id IS NOT NULL AND receipt_event_id IS NOT NULL AND run_id IS NOT NULL AND status = 'PUBLISHED'));
CREATE INDEX ix_file_publications_artifact ON file_publications(org_id, run_id, artifact_id);
