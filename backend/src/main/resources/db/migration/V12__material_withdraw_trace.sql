ALTER TABLE rs_material_version
    ADD COLUMN withdraw_reason VARCHAR(500),
    ADD COLUMN withdrawn_by BIGINT REFERENCES rs_user(id),
    ADD COLUMN withdrawn_at TIMESTAMPTZ;

CREATE INDEX idx_rs_material_version_published
    ON rs_material_version (material_id, version DESC)
    WHERE publication_status = 'PUBLISHED';
