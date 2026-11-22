CREATE TABLE rs_ai_job (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES rs_user(id),
    subject_id BIGINT NOT NULL DEFAULT 1 REFERENCES rs_subject(id),
    scenario VARCHAR(32) NOT NULL CHECK (scenario IN
        ('QUESTION_GENERATION','SUBJECTIVE_REVIEW','WRONG_VARIANT','GUIDANCE','EXPLANATION','MASTERY_JUDGEMENT')),
    context_hash CHAR(64) NOT NULL,
    active_key VARCHAR(120),
    snapshot_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    material_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_question_version_id BIGINT REFERENCES rs_question_version(id),
    knowledge_id BIGINT REFERENCES rs_knowledge_point(id),
    evidence_revision BIGINT,
    tutoring_epoch INTEGER,
    template_version VARCHAR(40) NOT NULL,
    model_config_version VARCHAR(40) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','EXPIRED')),
    deadline_at TIMESTAMPTZ,
    output_json JSONB,
    usage_json JSONB,
    error_code VARCHAR(60),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_rs_ai_inflight ON rs_ai_job(active_key) WHERE state IN ('QUEUED','RUNNING');
CREATE UNIQUE INDEX uq_rs_ai_mastery ON rs_ai_job(user_id, knowledge_id, evidence_revision)
    WHERE scenario = 'MASTERY_JUDGEMENT';
CREATE INDEX rs_ai_job_state_idx ON rs_ai_job(state, deadline_at);
CREATE INDEX rs_ai_job_user_time_idx ON rs_ai_job(user_id, created_at DESC);

CREATE TABLE rs_ai_quota (
    scope_key VARCHAR(120) NOT NULL,
    budget_date DATE NOT NULL,
    used_calls INTEGER NOT NULL DEFAULT 0 CHECK (used_calls >= 0),
    reserved_cost NUMERIC(12,4) NOT NULL DEFAULT 0 CHECK (reserved_cost >= 0),
    actual_cost NUMERIC(12,4) NOT NULL DEFAULT 0 CHECK (actual_cost >= 0),
    PRIMARY KEY (scope_key, budget_date)
);
