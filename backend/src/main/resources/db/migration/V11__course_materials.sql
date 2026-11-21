CREATE SEQUENCE rs_material_id_seq START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE rs_material_version (
    id BIGSERIAL PRIMARY KEY,
    material_id BIGINT NOT NULL,
    version INT NOT NULL CHECK (version >= 1),
    subject_id BIGINT NOT NULL REFERENCES rs_subject(id),
    knowledge_id BIGINT NOT NULL REFERENCES rs_knowledge_point(id),
    title VARCHAR(200) NOT NULL,
    source_label VARCHAR(300),
    content TEXT NOT NULL,
    publication_status VARCHAR(16) NOT NULL CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    approved_by BIGINT REFERENCES rs_user(id),
    approved_at TIMESTAMPTZ,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (material_id, version)
);

CREATE INDEX idx_rs_material_version_lookup
    ON rs_material_version (subject_id, knowledge_id, publication_status);

CREATE INDEX idx_rs_material_version_history
    ON rs_material_version (material_id, version DESC);

CREATE TABLE rs_material_chunk (
    id BIGSERIAL PRIMARY KEY,
    material_version_id BIGINT NOT NULL REFERENCES rs_material_version(id),
    position INT NOT NULL CHECK (position >= 0),
    text TEXT NOT NULL CHECK (length(btrim(text)) > 0),
    UNIQUE (material_version_id, position)
);
