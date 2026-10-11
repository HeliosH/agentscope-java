ALTER TABLE file_attachments ADD COLUMN logical_path VARCHAR(1024);
UPDATE file_attachments a SET logical_path = (
    SELECT f.logical_path FROM files f
      WHERE f.id = a.file_id AND f.org_id = a.org_id AND f.user_id = a.user_id
);
CREATE INDEX ix_file_attachments_version ON file_attachments(file_version_id);
